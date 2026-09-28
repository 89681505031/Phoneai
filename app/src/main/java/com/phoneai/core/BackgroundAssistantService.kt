package com.phoneai.core

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.phoneai.core.voice.AutoUtteranceRecorder
import com.phoneai.core.voice.LocalWhisper
import com.phoneai.core.voice.LightweightWakeListener
import com.phoneai.core.voice.OfflineTtsController
import com.phoneai.core.voice.SakhaLanguage
import com.phoneai.core.voice.SakhaNeuralTts
import com.phoneai.core.voice.WakeWordPackManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground microphone service for PhoneAI 0.13.
 *
 * Important Android rule: a microphone foreground service must be started while the app is
 * visible (except narrow platform exemptions). Once started, this service can keep the local
 * wake-word -> whisper.cpp -> LLM -> offline TTS loop alive while the UI is minimized.
 */
class BackgroundAssistantService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = AutoUtteranceRecorder()
    private val whisper = LocalWhisper()
    private val lightweightWakeListener by lazy { LightweightWakeListener(this) }

    private lateinit var engine: InferenceEngine
    private lateinit var offlineTts: OfflineTtsController
    private lateinit var sakhaNeuralTts: SakhaNeuralTts
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var running = false
    @Volatile private var shuttingDown = false
    private var modelReady = false
    private var speechModel: File? = null
    private var wakePack: WakeWordPackManager.Pack? = null
    private var lightweightWakeEnabled = false
    private var conversationDeadlineMs = 0L
    private var generationJob: Job? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        powerManager = getSystemService(PowerManager::class.java)
        offlineTts = OfflineTtsController(this) { state ->
            if (running && state.startsWith("Озвучка:")) updateStatus(state)
        }
        sakhaNeuralTts = SakhaNeuralTts(this) { state ->
            if (running && state.startsWith("Sakha TTS:")) updateStatus(state)
        }
        if (SAKHA_TTS_ENABLED) sakhaNeuralTts.reload()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                serviceScope.launch { shutdownAndStop() }
                return START_NOT_STICKY
            }
            ACTION_START -> startAssistant()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAssistant() {
        if (running || shuttingDown) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopWithError("Нет разрешения на микрофон")
            return
        }

        startForegroundNow("Запускаю локальный фоновый ассистент…")
        running = true
        prefs.edit().putBoolean(KEY_BACKGROUND_ACTIVE, true).apply()
        acquireWakeLock()

        serviceScope.launch {
            try {
                prepareModels()
                val wakeMode = if (lightweightWakeEnabled) "лёгкий KWS" else "Whisper fallback"
                updateStatus("Фон активен • $wakeMode • жду слово активации")
                scheduleListen(150L)
            } catch (t: Throwable) {
                updateStatus("Фоновый режим остановлен: ${t.message ?: t.javaClass.simpleName}")
                delay(600L)
                shutdownAndStop()
            }
        }
    }

    private suspend fun prepareModels() {
        val llmPath = prefs.getString(KEY_MODEL_PATH, null)
            ?: error("Сначала выберите GGUF-модель")
        val sttPath = prefs.getString(KEY_SPEECH_MODEL_PATH, null)
            ?: error("Сначала выберите Whisper-модель")
        val llm = File(llmPath)
        val stt = File(sttPath)
        require(llm.isFile) { "GGUF-модель не найдена" }
        require(stt.isFile) { "Whisper-модель не найдена" }
        speechModel = stt
        wakePack = WakeWordPackManager.current(this)
        lightweightWakeEnabled = prefs.getBoolean(KEY_LIGHTWEIGHT_WAKE, false) && wakePack != null

        engine = AiChat.getInferenceEngine(applicationContext)
        var state = engine.state.first {
            it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
        }

        if (state is InferenceEngine.State.Error) {
            engine.cleanUp()
            state = engine.state.first { it is InferenceEngine.State.Initialized }
        }

        if (state is InferenceEngine.State.Initialized) {
            updateStatus("Фон: загружаю ${llm.name} в RAM…")
            val wantsGpu = BuildConfig.OPENCL_BUILD && prefs.getBoolean(KEY_GPU_EXPERIMENTAL, false)
            try {
                engine.loadModel(llm.absolutePath, gpuLayers = if (wantsGpu) GPU_LAYERS_ALL else 0)
            } catch (gpuError: Throwable) {
                if (!wantsGpu) throw gpuError
                if (engine.state.value is InferenceEngine.State.Error) engine.cleanUp()
                updateStatus("GPU недоступен • переключаю фон на CPU…")
                engine.loadModel(llm.absolutePath, gpuLayers = 0)
            }
            engine.setSystemPrompt(runtimeSystemPrompt())
        }

        modelReady = engine.state.value is InferenceEngine.State.ModelReady
        require(modelReady) { "LLM-ядро не перешло в состояние ModelReady" }
    }

    private fun scheduleListen(delayMs: Long = 0L) {
        if (!running || shuttingDown) return
        serviceScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (running && !shuttingDown) beginListen()
        }
    }

    private fun beginListen() {
        if (!running || recorder.isActive || generationJob?.isActive == true) return
        val stt = speechModel
        if (stt == null || !stt.exists() || !modelReady) {
            serviceScope.launch { shutdownAndStop() }
            return
        }

        val now = SystemClock.elapsedRealtime()
        val conversationMode = conversationDeadlineMs > now
        val maxWaitMs = if (conversationMode) {
            (conversationDeadlineMs - now).coerceAtLeast(1_000L)
        } else {
            WAKE_LISTEN_SLICE_MS
        }

        if (!conversationMode && startLightweightWakeIfReady()) return

        try {
            recorder.start(
                maxWaitMs = maxWaitMs,
                onState = { state ->
                    when (state) {
                        AutoUtteranceRecorder.State.WAITING_FOR_SPEECH -> updateStatus(
                            if (conversationMode) "Фон • диалог активен, слушаю следующую фразу…"
                            else "Фон • жду слово активации: ${wakeWords().joinToString()}"
                        )
                        AutoUtteranceRecorder.State.SPEECH_DETECTED -> updateStatus("Фон • речь обнаружена локально…")
                        AutoUtteranceRecorder.State.UTTERANCE_READY -> updateStatus("Фон • распознаю фразу через whisper.cpp…")
                    }
                },
                onUtterance = { audio ->
                    serviceScope.launch { handleUtterance(audio, conversationMode) }
                },
                onTimeout = {
                    if (conversationMode) conversationDeadlineMs = 0L
                    scheduleListen(120L)
                },
                onError = { error ->
                    updateStatus("Ошибка микрофона: ${error.message ?: error.javaClass.simpleName}")
                    scheduleListen(700L)
                }
            )
        } catch (t: Throwable) {
            updateStatus("Не удалось слушать микрофон: ${t.message ?: t.javaClass.simpleName}")
            scheduleListen(1_000L)
        }
    }

    private fun startLightweightWakeIfReady(): Boolean {
        if (!lightweightWakeEnabled) return false
        val pack = wakePack ?: return false
        if (lightweightWakeListener.isActive) return true
        return try {
            updateStatus("Фон • KWS слушает локально • Whisper спит")
            lightweightWakeListener.start(
                pack = pack,
                onKeyword = { keyword ->
                    serviceScope.launch {
                        if (!running || shuttingDown) return@launch
                        conversationDeadlineMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
                        updateStatus("PhoneAI активирован локально: $keyword")
                        val spoke = speakLocally("Слушаю") { scheduleListen(100L) }
                        if (!spoke) scheduleListen(100L)
                    }
                },
                onError = { error ->
                    serviceScope.launch {
                        lightweightWakeEnabled = false
                        updateStatus("KWS недоступен • Whisper fallback: ${error.message ?: error.javaClass.simpleName}")
                        scheduleListen(350L)
                    }
                }
            )
            true
        } catch (t: Throwable) {
            lightweightWakeEnabled = false
            updateStatus("KWS не запустился • Whisper fallback")
            false
        }
    }

    private suspend fun handleUtterance(audio: FloatArray, conversationMode: Boolean) {
        if (!running) return
        try {
            if (audio.size < AutoUtteranceRecorder.SAMPLE_RATE / 3) {
                scheduleListen(120L)
                return
            }
            val stt = speechModel ?: return
            val transcript = whisper.transcribe(stt, audio).trim()
            if (transcript.isBlank()) {
                scheduleListen(160L)
                return
            }

            if (conversationMode) {
                updateStatus("Фон • команда: ${transcript.take(100)}")
                generate(transcript)
                return
            }

            val hit = findWakeHit(transcript)
            if (hit == null) {
                updateStatus("Фон • слова активации нет, продолжаю слушать")
                scheduleListen(120L)
                return
            }

            val command = transcript.substring((hit.index + hit.length).coerceAtMost(transcript.length))
                .trim { it.isWhitespace() || it in charArrayOf(',', '.', '!', '?', ':', ';', '-', '—') }
            conversationDeadlineMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS

            if (command.isBlank()) {
                updateStatus("PhoneAI активирован • слушаю команду")
                val spoke = speakLocally("Слушаю") { scheduleListen(120L) }
                if (!spoke) scheduleListen(120L)
            } else {
                updateStatus("Фон • активация: ${command.take(100)}")
                generate(command)
            }
        } catch (t: Throwable) {
            updateStatus("Распознавание: ${t.message ?: t.javaClass.simpleName}")
            scheduleListen(300L)
        }
    }

    private fun speakLocally(text: String, onDone: (() -> Unit)? = null): Boolean {
        if (SakhaLanguage.looksLikeSakha(text) && !SAKHA_TTS_ENABLED) {
            updateStatus("Саха-ответ готов текстом • озвучка временно отключена")
            onDone?.invoke()
            return onDone != null
        }
        if (SAKHA_TTS_ENABLED && SakhaLanguage.looksLikeSakha(text) && sakhaNeuralTts.isReady()) {
            if (sakhaNeuralTts.speak(text, onDone)) return true
        }
        return offlineTts.speak(text, onDone)
    }

    private fun generate(prompt: String) {
        if (!running || !modelReady || generationJob?.isActive == true) return
        recorder.cancel()
        lightweightWakeListener.cancel()
        offlineTts.stop()
        if (::sakhaNeuralTts.isInitialized) sakhaNeuralTts.stop()

        if (!hasSafeMemory()) {
            updateStatus("Фон: мало свободной RAM • пропускаю команду")
            conversationDeadlineMs = 0L
            scheduleListen(500L)
            return
        }
        if (powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            updateStatus("Фон: телефон сильно нагрет • генерация отложена")
            conversationDeadlineMs = 0L
            scheduleListen(2_000L)
            return
        }

        generationJob = serviceScope.launch {
            val answer = StringBuilder()
            try {
                updateStatus("Фон • PhoneAI отвечает локально…")
                engine.sendUserPrompt(SakhaLanguage.routePrompt(prompt), predictLength = BACKGROUND_PREDICT_LENGTH).collect { piece ->
                    answer.append(piece)
                    if (answer.length % 120 < piece.length) {
                        updateStatus("PhoneAI: ${answer.toString().replace('\n', ' ').take(120)}")
                    }
                }
                val text = answer.toString().trim()
                conversationDeadlineMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
                if (text.isBlank()) {
                    updateStatus("Фон • пустой ответ, продолжаю слушать")
                    scheduleListen(120L)
                } else {
                    updateStatus("PhoneAI: ${text.replace('\n', ' ').take(150)}")
                    val spoke = speakLocally(text) { scheduleListen(150L) }
                    if (!spoke) scheduleListen(150L)
                }
            } catch (_: CancellationException) {
                updateStatus("Фоновый ответ остановлен")
            } catch (t: Throwable) {
                updateStatus("Ошибка локального ответа: ${t.message ?: t.javaClass.simpleName}")
                scheduleListen(400L)
            } finally {
                generationJob = null
            }
        }
    }

    private fun hasSafeMemory(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return !info.lowMemory && info.availMem >= MIN_FREE_MEMORY_BYTES
    }

    private fun wakeWords(): List<String> =
        (prefs.getString(KEY_WAKE_WORDS, DEFAULT_WAKE_WORDS) ?: DEFAULT_WAKE_WORDS)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun findWakeHit(transcript: String): WakeHit? {
        val comparable = transcript.lowercase().replace('ё', 'е')
        return wakeWords().sortedByDescending { it.length }.firstNotNullOfOrNull { wake ->
            val token = wake.lowercase().replace('ё', 'е').trim()
            val index = comparable.indexOf(token)
            if (index >= 0) WakeHit(index, token.length) else null
        }
    }

    private fun runtimeSystemPrompt(): String {
        val identity = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)
            ?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
        val memory = prefs.getString(KEY_LOCAL_MEMORY, "").orEmpty().trim()
        return if (memory.isBlank()) identity
        else "$identity\n\nЛокальная память пользователя. Используй её только когда она относится к запросу:\n${memory.take(MAX_MEMORY_CHARS)}"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PhoneAI фоновый ассистент",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает, когда PhoneAI использует микрофон для локального фонового ассистента"
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("PhoneAI • фоновый режим")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openAppPendingIntent())
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPendingIntent())
        .build()

    private fun startForegroundNow(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        saveStatus(text)
    }

    private fun updateStatus(text: String) {
        saveStatus(text)
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun saveStatus(text: String) {
        prefs.edit().putString(KEY_BACKGROUND_STATUS, text).apply()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, BackgroundAssistantService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            11,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneAI:BackgroundAssistant")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopWithError(message: String) {
        prefs.edit()
            .putBoolean(KEY_BACKGROUND_ACTIVE, false)
            .putString(KEY_BACKGROUND_STATUS, message)
            .apply()
        stopSelf()
    }

    private suspend fun shutdownAndStop() {
        if (shuttingDown) return
        shuttingDown = true
        running = false
        recorder.cancel()
        lightweightWakeListener.cancel()
        generationJob?.cancel()
        offlineTts.stop()
        if (::sakhaNeuralTts.isInitialized) sakhaNeuralTts.stop()
        runCatching {
            if (::engine.isInitialized) {
                delay(100L)
                when (engine.state.value) {
                    is InferenceEngine.State.ModelReady,
                    is InferenceEngine.State.Error -> engine.cleanUp()
                    else -> Unit
                }
            }
        }
        modelReady = false
        conversationDeadlineMs = 0L
        prefs.edit()
            .putBoolean(KEY_BACKGROUND_ACTIVE, false)
            .putString(KEY_BACKGROUND_STATUS, "Фоновый режим выключен")
            .apply()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        recorder.cancel()
        lightweightWakeListener.cancel()
        generationJob?.cancel()
        offlineTts.shutdown()
        if (::sakhaNeuralTts.isInitialized) sakhaNeuralTts.shutdown()
        releaseWakeLock()
        prefs.edit().putBoolean(KEY_BACKGROUND_ACTIVE, false).apply()
        serviceScope.cancel()
        super.onDestroy()
    }

    data class WakeHit(val index: Int, val length: Int)

    companion object {
        const val ACTION_START = "com.phoneai.core.action.START_BACKGROUND"
        const val ACTION_STOP = "com.phoneai.core.action.STOP_BACKGROUND"

        private const val CHANNEL_ID = "phoneai_background_assistant"
        private const val NOTIFICATION_ID = 7007
        private const val PREFS_NAME = "phoneai_core"
        private const val SAKHA_TTS_ENABLED = false
        const val KEY_BACKGROUND_ACTIVE = "background_active"
        const val KEY_BACKGROUND_STATUS = "background_status"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_SPEECH_MODEL_PATH = "speech_model_path"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_LOCAL_MEMORY = "local_memory"
        private const val KEY_GPU_EXPERIMENTAL = "gpu_experimental"
        private const val KEY_WAKE_WORDS = "wake_words"
        private const val KEY_LIGHTWEIGHT_WAKE = "lightweight_wake_enabled"

        private const val GPU_LAYERS_ALL = 99
        private const val BACKGROUND_PREDICT_LENGTH = 384
        private const val MAX_MEMORY_CHARS = 3000
        private const val MIN_FREE_MEMORY_BYTES = 384L * 1024L * 1024L
        private const val CONVERSATION_WINDOW_MS = 30_000L
        private const val WAKE_LISTEN_SLICE_MS = 15_000L
        private const val DEFAULT_WAKE_WORDS = "PhoneAI, Phone AI, Фонай, Фонэй, Джарвис"
        private const val DEFAULT_SYSTEM_PROMPT =
            "Ты PhoneAI, локальный мобильный ИИ. Отвечай полезно, ясно и кратко. " +
                "Если пользователь пишет или говорит на якутском (саха) языке, отвечай на якутском (саха) и не переходи на русский без просьбы. " +
                "Сохраняй буквы ҕ, ҥ, ө, һ, ү и не выдумывай неизвестные якутские слова или формы. " +
                "Если пользователь обращается по-русски, отвечай по-русски. " +
                "Ты работаешь непосредственно на устройстве пользователя без облачного API. " +
                "Не выдавай догадки за факты и сообщай, когда тебе не хватает данных."
    }
}
