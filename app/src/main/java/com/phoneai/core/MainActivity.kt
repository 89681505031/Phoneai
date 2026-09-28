package com.phoneai.core

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.switchmaterial.SwitchMaterial
import com.phoneai.core.feedback.FeedbackStore
import com.phoneai.core.chat.ChatActivity
import com.phoneai.core.voice.AutoUtteranceRecorder
import com.phoneai.core.voice.KwsCorpusStore
import com.phoneai.core.voice.LocalAudioRecorder
import com.phoneai.core.voice.LocalWhisper
import com.phoneai.core.voice.OfflineTtsController
import com.phoneai.core.voice.SakhaLanguage
import com.phoneai.core.voice.SakhaNeuralTts
import com.phoneai.core.voice.SakhaTtsPackManager
import com.phoneai.core.voice.WakeWordPackManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var engine: InferenceEngine

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var profileText: TextView
    private lateinit var modelText: TextView
    private lateinit var promptInput: EditText
    private lateinit var openChatButton: Button
    private lateinit var modelButton: Button
    private lateinit var unloadButton: Button
    private lateinit var benchmarkButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var answerText: TextView
    private lateinit var perfText: TextView
    private lateinit var gpuSwitch: SwitchMaterial
    private lateinit var backendStateText: TextView
    private lateinit var qualityTestButton: Button
    private lateinit var qualityResultText: TextView

    private lateinit var systemPromptInput: EditText
    private lateinit var saveProfileButton: Button
    private lateinit var memoryInput: EditText
    private lateinit var saveMemoryButton: Button
    private lateinit var clearMemoryButton: Button
    private lateinit var memoryStateText: TextView

    private lateinit var teachPromptInput: EditText
    private lateinit var teachAnswerInput: EditText
    private lateinit var addTrainingExampleButton: Button
    private lateinit var exportDatasetButton: Button
    private lateinit var clearDatasetButton: Button
    private lateinit var datasetStateText: TextView

    private lateinit var asrCorrectionInput: EditText
    private lateinit var saveAsrCorrectionButton: Button
    private lateinit var llmCorrectionInput: EditText
    private lateinit var saveLlmCorrectionButton: Button
    private lateinit var exportFeedbackButton: Button
    private lateinit var clearFeedbackButton: Button
    private lateinit var analyzeFeedbackButton: Button
    private lateinit var feedbackStateText: TextView
    private lateinit var feedbackAnalysisText: TextView

    private lateinit var manageModelsButton: Button
    private lateinit var speechModelButton: Button
    private lateinit var speechModelText: TextView
    private lateinit var voiceButton: Button
    private lateinit var autoVoiceSendSwitch: SwitchMaterial
    private lateinit var speakSwitch: SwitchMaterial
    private lateinit var handsFreeSwitch: SwitchMaterial
    private lateinit var backgroundSwitch: SwitchMaterial
    private lateinit var backgroundStateText: TextView
    private lateinit var wakeWordsInput: EditText
    private lateinit var saveWakeWordsButton: Button
    private lateinit var voiceStatusText: TextView
    private lateinit var sakhaTtsPackButton: Button
    private lateinit var sakhaTtsStatusText: TextView
    private lateinit var kwsPackButton: Button
    private lateinit var lightweightWakeSwitch: SwitchMaterial
    private lateinit var kwsStatusText: TextView
    private lateinit var kwsTargetInput: EditText
    private lateinit var kwsSpeakerInput: EditText
    private lateinit var kwsNegativeTextInput: EditText
    private lateinit var kwsConsentSwitch: SwitchMaterial
    private lateinit var kwsPositiveButton: Button
    private lateinit var kwsNegativeButton: Button
    private lateinit var kwsExportCorpusButton: Button
    private lateinit var kwsAuditButton: Button
    private lateinit var kwsClearCorpusButton: Button
    private lateinit var kwsCorpusStatusText: TextView
    private lateinit var kwsAuditText: TextView

    private val localRecorder = LocalAudioRecorder()
    private val autoUtteranceRecorder = AutoUtteranceRecorder()
    private val kwsCorpusRecorder = AutoUtteranceRecorder()
    private val localWhisper = LocalWhisper()
    private lateinit var offlineTts: OfflineTtsController
    private lateinit var sakhaNeuralTts: SakhaNeuralTts
    private lateinit var feedbackStore: FeedbackStore
    private lateinit var kwsCorpusStore: KwsCorpusStore

    private var engineInitialized = false
    private var modelReady = false
    private var generationJob: Job? = null
    private var qualityJob: Job? = null
    private var qualityRunning = false
    private var currentModel: File? = null
    private var gpuModeRequested = false
    private var activeGpuLayers = 0
    private var voiceBusy = false
    private var currentSpeechModel: File? = null
    private var handsFreeEnabled = false
    private var conversationDeadlineMs = 0L
    private var pendingHandsFreePermission = false
    private var pendingBackgroundPermission = false
    private var pendingBackgroundNotificationPermission = false
    private var suppressHandsFreeListener = false
    private var suppressBackgroundListener = false
    private var backgroundTransition = false
    private var lastVoiceAudio: FloatArray? = null
    private var lastVoiceTranscript: String? = null
    private var lastUserPrompt: String? = null
    private var lastAssistantAnswer: String? = null
    private var pendingKwsCorpusLabel: KwsCorpusStore.Label? = null

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { inspectThenImport(uri) }
    }

    private val speechModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { importSpeechModel(uri) }
    }

    private val sakhaTtsPackPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { importSakhaTtsPack(uri) }
    }

    private val kwsPackPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { importKwsPack(uri) }
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        when {
            granted && pendingKwsCorpusLabel != null -> {
                val label = pendingKwsCorpusLabel
                pendingKwsCorpusLabel = null
                if (label != null) startKwsCorpusRecording(label)
            }
            granted && pendingBackgroundPermission -> {
                pendingBackgroundPermission = false
                startBackgroundModeAfterPermissions()
            }
            granted && pendingHandsFreePermission -> {
                pendingHandsFreePermission = false
                suppressHandsFreeListener = true
                handsFreeSwitch.isChecked = true
                suppressHandsFreeListener = false
                startHandsFreeMode()
            }
            granted -> startVoiceRecordingInternal()
            else -> {
                pendingKwsCorpusLabel = null
                pendingBackgroundPermission = false
                pendingHandsFreePermission = false
                syncBackgroundUi()
                voiceStatusText.text = "Микрофон: разрешение не выдано"
                toast("Для локального распознавания нужен доступ к микрофону")
            }
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingBackgroundNotificationPermission) {
            pendingBackgroundNotificationPermission = false
            if (!granted) toast("Уведомления отключены: Android может скрыть карточку фонового режима")
            launchBackgroundService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        feedbackStore = FeedbackStore(this)
        kwsCorpusStore = KwsCorpusStore(this)
        restoreSpeechModelSelection()
        offlineTts = OfflineTtsController(this) { state ->
            runOnUiThread {
                voiceStatusText.text = state
                setControls()
            }
        }
        sakhaNeuralTts = SakhaNeuralTts(this) { state ->
            runOnUiThread {
                sakhaTtsStatusText.text = state
                setControls()
            }
        }
        if (SAKHA_TTS_ENABLED) sakhaNeuralTts.reload() else sakhaTtsStatusText.text = "Sakha TTS: приостановлен в 0.19 — развиваем текст и распознавание"
        gpuModeRequested = BuildConfig.OPENCL_BUILD && prefs.getBoolean(KEY_GPU_EXPERIMENTAL, false)
        gpuSwitch.isChecked = gpuModeRequested
        restoreCustomizationFields()
        restoreWakeWords()
        restoreKwsCorpusFields()
        refreshKwsState()
        refreshKwsCorpusState()
        refreshDeviceCard()
        refreshBackendState()
        refreshDatasetState()
        refreshFeedbackState()
        setControls()

        modelButton.setOnClickListener {
            modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }

        manageModelsButton.setOnClickListener { showModelManager() }
        speechModelButton.setOnClickListener {
            speechModelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }
        sakhaTtsPackButton.isEnabled = false
        kwsPackButton.setOnClickListener {
            kwsPackPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        lightweightWakeSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled && WakeWordPackManager.current(this) == null) {
                lightweightWakeSwitch.isChecked = false
                toast("Сначала импортируйте KWS-пакет")
            } else {
                prefs.edit().putBoolean(KEY_LIGHTWEIGHT_WAKE, enabled).apply()
                refreshKwsState()
            }
        }
        kwsPositiveButton.setOnClickListener { requestKwsCorpusRecording(KwsCorpusStore.Label.POSITIVE) }
        kwsNegativeButton.setOnClickListener { requestKwsCorpusRecording(KwsCorpusStore.Label.NEGATIVE) }
        kwsExportCorpusButton.setOnClickListener { exportKwsCorpus() }
        kwsAuditButton.setOnClickListener { showKwsAudit() }
        kwsClearCorpusButton.setOnClickListener { confirmClearKwsCorpus() }
        voiceButton.setOnClickListener { toggleVoiceRecording() }
        saveWakeWordsButton.setOnClickListener { saveWakeWords() }
        handsFreeSwitch.setOnCheckedChangeListener { _, enabled ->
            if (suppressHandsFreeListener) return@setOnCheckedChangeListener
            if (enabled) requestHandsFreeMode() else stopHandsFreeMode(userInitiated = true)
        }
        backgroundSwitch.setOnCheckedChangeListener { _, enabled ->
            if (suppressBackgroundListener) return@setOnCheckedChangeListener
            if (enabled) requestBackgroundMode() else stopBackgroundMode()
        }
        syncBackgroundUi()

        unloadButton.setOnClickListener {
            lifecycleScope.launch { unloadModel() }
        }

        benchmarkButton.setOnClickListener {
            runBenchmark()
        }

        qualityTestButton.setOnClickListener {
            runQualitySuite()
        }

        gpuSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!BuildConfig.OPENCL_BUILD) {
                gpuSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (enabled && engineInitialized && !openClGpuAvailable()) {
                gpuSwitch.isChecked = false
                toast("OpenCL GPU не найден. Остаёмся на CPU")
                return@setOnCheckedChangeListener
            }
            gpuModeRequested = enabled
            prefs.edit().putBoolean(KEY_GPU_EXPERIMENTAL, enabled).apply()
            refreshBackendState()
            val file = currentModel
            if (file != null && !isWorking()) {
                lifecycleScope.launch {
                    loadModelFile(file, remember = false)
                }
            }
        }

        openChatButton.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        sendButton.setOnClickListener {
            val prompt = promptInput.text.toString().trim()
            if (prompt.isNotBlank()) generate(prompt)
        }

        stopButton.setOnClickListener {
            generationJob?.cancel()
        }

        saveProfileButton.setOnClickListener { saveIdentityProfile() }
        saveMemoryButton.setOnClickListener { saveLocalMemory() }
        clearMemoryButton.setOnClickListener { clearLocalMemory() }
        addTrainingExampleButton.setOnClickListener { addTrainingExample() }
        exportDatasetButton.setOnClickListener { exportTrainingDataset() }
        clearDatasetButton.setOnClickListener { confirmClearDataset() }
        saveAsrCorrectionButton.setOnClickListener { saveAsrCorrection() }
        saveLlmCorrectionButton.setOnClickListener { saveLlmCorrection() }
        exportFeedbackButton.setOnClickListener { exportFeedbackBundle() }
        analyzeFeedbackButton.setOnClickListener { showFeedbackAnalysis() }
        clearFeedbackButton.setOnClickListener { confirmClearFeedback() }

        initializeEngine()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        deviceText = findViewById(R.id.deviceText)
        profileText = findViewById(R.id.profileText)
        modelText = findViewById(R.id.modelText)
        promptInput = findViewById(R.id.promptInput)
        openChatButton = findViewById(R.id.openChatButton)
        modelButton = findViewById(R.id.modelButton)
        unloadButton = findViewById(R.id.unloadButton)
        benchmarkButton = findViewById(R.id.benchmarkButton)
        sendButton = findViewById(R.id.sendButton)
        stopButton = findViewById(R.id.stopButton)
        answerText = findViewById(R.id.answerText)
        perfText = findViewById(R.id.perfText)
        gpuSwitch = findViewById(R.id.gpuSwitch)
        backendStateText = findViewById(R.id.backendStateText)
        qualityTestButton = findViewById(R.id.qualityTestButton)
        qualityResultText = findViewById(R.id.qualityResultText)

        systemPromptInput = findViewById(R.id.systemPromptInput)
        saveProfileButton = findViewById(R.id.saveProfileButton)
        memoryInput = findViewById(R.id.memoryInput)
        saveMemoryButton = findViewById(R.id.saveMemoryButton)
        clearMemoryButton = findViewById(R.id.clearMemoryButton)
        memoryStateText = findViewById(R.id.memoryStateText)

        teachPromptInput = findViewById(R.id.teachPromptInput)
        teachAnswerInput = findViewById(R.id.teachAnswerInput)
        addTrainingExampleButton = findViewById(R.id.addTrainingExampleButton)
        exportDatasetButton = findViewById(R.id.exportDatasetButton)
        clearDatasetButton = findViewById(R.id.clearDatasetButton)
        datasetStateText = findViewById(R.id.datasetStateText)
        asrCorrectionInput = findViewById(R.id.asrCorrectionInput)
        saveAsrCorrectionButton = findViewById(R.id.saveAsrCorrectionButton)
        llmCorrectionInput = findViewById(R.id.llmCorrectionInput)
        saveLlmCorrectionButton = findViewById(R.id.saveLlmCorrectionButton)
        exportFeedbackButton = findViewById(R.id.exportFeedbackButton)
        clearFeedbackButton = findViewById(R.id.clearFeedbackButton)
        analyzeFeedbackButton = findViewById(R.id.analyzeFeedbackButton)
        feedbackStateText = findViewById(R.id.feedbackStateText)
        feedbackAnalysisText = findViewById(R.id.feedbackAnalysisText)

        manageModelsButton = findViewById(R.id.manageModelsButton)
        speechModelButton = findViewById(R.id.speechModelButton)
        speechModelText = findViewById(R.id.speechModelText)
        voiceButton = findViewById(R.id.voiceButton)
        autoVoiceSendSwitch = findViewById(R.id.autoVoiceSendSwitch)
        speakSwitch = findViewById(R.id.speakSwitch)
        handsFreeSwitch = findViewById(R.id.handsFreeSwitch)
        backgroundSwitch = findViewById(R.id.backgroundSwitch)
        backgroundStateText = findViewById(R.id.backgroundStateText)
        wakeWordsInput = findViewById(R.id.wakeWordsInput)
        saveWakeWordsButton = findViewById(R.id.saveWakeWordsButton)
        voiceStatusText = findViewById(R.id.voiceStatusText)
        sakhaTtsPackButton = findViewById(R.id.sakhaTtsPackButton)
        sakhaTtsStatusText = findViewById(R.id.sakhaTtsStatusText)
        kwsPackButton = findViewById(R.id.kwsPackButton)
        lightweightWakeSwitch = findViewById(R.id.lightweightWakeSwitch)
        kwsStatusText = findViewById(R.id.kwsStatusText)
        kwsTargetInput = findViewById(R.id.kwsTargetInput)
        kwsSpeakerInput = findViewById(R.id.kwsSpeakerInput)
        kwsNegativeTextInput = findViewById(R.id.kwsNegativeTextInput)
        kwsConsentSwitch = findViewById(R.id.kwsConsentSwitch)
        kwsPositiveButton = findViewById(R.id.kwsPositiveButton)
        kwsNegativeButton = findViewById(R.id.kwsNegativeButton)
        kwsExportCorpusButton = findViewById(R.id.kwsExportCorpusButton)
        kwsAuditButton = findViewById(R.id.kwsAuditButton)
        kwsClearCorpusButton = findViewById(R.id.kwsClearCorpusButton)
        kwsCorpusStatusText = findViewById(R.id.kwsCorpusStatusText)
        kwsAuditText = findViewById(R.id.kwsAuditText)
    }

    private fun restoreCustomizationFields() {
        systemPromptInput.setText(trainingSystemPrompt())
        val memory = prefs.getString(KEY_LOCAL_MEMORY, "").orEmpty()
        memoryInput.setText(memory)
        refreshMemoryState(memory)
    }

    private fun restoreSpeechModelSelection() {
        val path = prefs.getString(KEY_SPEECH_MODEL_PATH, null)
        currentSpeechModel = path?.let(::File)?.takeIf { it.exists() && it.isFile }
        if (path != null && currentSpeechModel == null) {
            prefs.edit().remove(KEY_SPEECH_MODEL_PATH).apply()
        }
        refreshSpeechModelState()
    }

    private fun refreshSpeechModelState() {
        val model = currentSpeechModel
        speechModelText.text = if (model == null) {
            "Модель распознавания не выбрана. Для телефона рекомендуется whisper tiny или base."
        } else {
            "STT: ${model.name} • ${formatBytes(model.length())} • whisper.cpp"
        }
    }

    private suspend fun importSpeechModel(uri: Uri) {
        if (isWorking()) return
        val sourceName = queryDisplayName(uri) ?: "ggml-tiny.bin"
        if (!sourceName.lowercase().endsWith(".bin")) {
            toast("Для whisper.cpp выберите модель .bin")
            return
        }

        val sourceSize = querySize(uri)
        if (sourceSize > MAX_SPEECH_MODEL_BYTES) {
            toast("Эта Whisper-модель слишком большая для мобильного профиля PhoneAI")
            return
        }
        if (sourceSize > 0 && filesDir.usableSpace < (sourceSize * 1.12).toLong()) {
            toast("Недостаточно свободной памяти для модели распознавания")
            return
        }

        voiceBusy = true
        setControls()
        voiceStatusText.text = "Копирую Whisper-модель в локальное хранилище…"
        try {
            val dir = File(filesDir, "speech_models").apply { mkdirs() }
            val target = File(dir, sanitizeSpeechFileName(sourceName))
            val temp = File(dir, target.name + ".importing")
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть Whisper-модель" }
                    temp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "Не удалось сохранить Whisper-модель" }
            }
            currentSpeechModel = target
            prefs.edit().putString(KEY_SPEECH_MODEL_PATH, target.absolutePath).apply()
            refreshSpeechModelState()
            voiceStatusText.text = "Распознавание готово: ${target.name}. Модель запускается локально по нажатию микрофона."
        } catch (e: Exception) {
            voiceStatusText.text = "Ошибка Whisper-модели: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            voiceBusy = false
            setControls()
        }
    }

    private suspend fun importSakhaTtsPack(uri: Uri) {
        if (isWorking()) return
        voiceBusy = true
        setControls()
        sakhaTtsStatusText.text = "Sakha TTS: устанавливаю локальный пакет…"
        try {
            val pack = withContext(Dispatchers.IO) { SakhaTtsPackManager.importZip(this@MainActivity, uri) }
            val ready = sakhaNeuralTts.reload()
            sakhaTtsStatusText.text = if (ready) {
                "Sakha TTS: ${pack.model.name} • ${formatBytes(pack.model.length())} • sherpa-onnx"
            } else {
                "Sakha TTS: пакет сохранён, но движок не смог его загрузить"
            }
        } catch (t: Throwable) {
            sakhaTtsStatusText.text = "Sakha TTS: ошибка импорта • ${t.message ?: t.javaClass.simpleName}"
        } finally {
            voiceBusy = false
            setControls()
        }
    }

    private suspend fun importKwsPack(uri: Uri) {
        if (isWorking()) return
        voiceBusy = true
        setControls()
        kwsStatusText.text = "KWS: устанавливаю локальный пакет…"
        try {
            val pack = withContext(Dispatchers.IO) { WakeWordPackManager.importZip(this@MainActivity, uri) }
            prefs.edit().putBoolean(KEY_LIGHTWEIGHT_WAKE, true).apply()
            lightweightWakeSwitch.isChecked = true
            kwsStatusText.text =
                "KWS: ${pack.modelType} • ${formatBytes(pack.encoder.length() + pack.decoder.length() + pack.joiner.length())} • лёгкое фоновое ожидание"
        } catch (t: Throwable) {
            prefs.edit().putBoolean(KEY_LIGHTWEIGHT_WAKE, false).apply()
            lightweightWakeSwitch.isChecked = false
            kwsStatusText.text = "KWS: ошибка импорта • ${t.message ?: t.javaClass.simpleName}"
        } finally {
            voiceBusy = false
            setControls()
        }
    }

    private fun refreshKwsState() {
        val pack = WakeWordPackManager.current(this)
        val enabled = prefs.getBoolean(KEY_LIGHTWEIGHT_WAKE, false) && pack != null
        if (pack == null && prefs.getBoolean(KEY_LIGHTWEIGHT_WAKE, false)) {
            prefs.edit().putBoolean(KEY_LIGHTWEIGHT_WAKE, false).apply()
        }
        lightweightWakeSwitch.isChecked = enabled
        lightweightWakeSwitch.isEnabled = pack != null && !backgroundActive()
        kwsStatusText.text = when {
            pack == null -> "KWS: пакет не установлен • фон использует Whisper fallback"
            enabled -> "KWS: включён • Whisper будет запускаться только после слова активации"
            else -> "KWS: пакет установлен, но экономичное ожидание выключено"
        }
    }

    private fun restoreKwsCorpusFields() {
        val defaultTarget = wakeWords().firstOrNull().orEmpty().ifBlank { "PhoneAI" }
        kwsTargetInput.setText(prefs.getString(KEY_KWS_TARGET, defaultTarget) ?: defaultTarget)
        kwsSpeakerInput.setText(prefs.getString(KEY_KWS_SPEAKER, "speaker_local") ?: "speaker_local")
        kwsNegativeTextInput.setText("")
        kwsConsentSwitch.isChecked = false
    }

    private fun requestKwsCorpusRecording(label: KwsCorpusStore.Label) {
        if (backgroundActive() || handsFreeEnabled || isWorking() || kwsCorpusRecorder.isActive) {
            toast("Сначала остановите активный голосовой режим")
            return
        }
        val target = kwsTargetInput.text.toString().trim()
        val speaker = kwsSpeakerInput.text.toString().trim()
        if (target.isBlank()) {
            toast("Введите точное слово или фразу активации")
            return
        }
        if (speaker.isBlank()) {
            toast("Введите ID говорящего, например speaker_01")
            return
        }
        if (label == KwsCorpusStore.Label.NEGATIVE && kwsNegativeTextInput.text.toString().trim().isBlank()) {
            toast("Для отрицательного примера введите текст фразы, которую собираетесь сказать")
            return
        }
        if (!kwsConsentSwitch.isChecked) {
            toast("Подтвердите согласие говорящего на использование этой записи для обучения")
            return
        }
        prefs.edit()
            .putString(KEY_KWS_TARGET, target)
            .putString(KEY_KWS_SPEAKER, speaker)
            .apply()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingKwsCorpusLabel = label
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startKwsCorpusRecording(label)
    }

    private fun startKwsCorpusRecording(label: KwsCorpusStore.Label) {
        val target = kwsTargetInput.text.toString().trim()
        val speaker = kwsSpeakerInput.text.toString().trim()
        val transcript = if (label == KwsCorpusStore.Label.POSITIVE) target else kwsNegativeTextInput.text.toString().trim()
        val consent = kwsConsentSwitch.isChecked
        voiceBusy = true
        kwsCorpusStatusText.text = if (label == KwsCorpusStore.Label.POSITIVE) {
            "KWS-корпус: скажите только «$target» один раз…"
        } else {
            "KWS-корпус: скажите обычную фразу БЕЗ «$target»…"
        }
        setControls()
        try {
            kwsCorpusRecorder.start(
                maxWaitMs = 10_000L,
                onState = { state ->
                    runOnUiThread {
                        kwsCorpusStatusText.text = when (state) {
                            AutoUtteranceRecorder.State.WAITING_FOR_SPEECH -> if (label == KwsCorpusStore.Label.POSITIVE) {
                                "KWS-корпус: жду «$target»…"
                            } else {
                                "KWS-корпус: жду отрицательную фразу без ключевого слова…"
                            }
                            AutoUtteranceRecorder.State.SPEECH_DETECTED -> "KWS-корпус: речь обнаружена…"
                            AutoUtteranceRecorder.State.UTTERANCE_READY -> "KWS-корпус: сохраняю локальную запись…"
                        }
                    }
                },
                onUtterance = { audio ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            kwsCorpusStore.saveSample(
                                audio = audio,
                                sampleRate = AutoUtteranceRecorder.SAMPLE_RATE,
                                label = label,
                                targetKeyword = target,
                                transcript = transcript,
                                speakerId = speaker,
                                consentConfirmed = consent,
                            )
                            withContext(Dispatchers.Main) {
                                voiceBusy = false
                                if (label == KwsCorpusStore.Label.NEGATIVE) kwsNegativeTextInput.setText("")
                                refreshKwsCorpusState()
                                setControls()
                                toast(if (label == KwsCorpusStore.Label.POSITIVE) "Положительный KWS-пример сохранён" else "Отрицательный KWS-пример сохранён")
                            }
                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                voiceBusy = false
                                refreshKwsCorpusState()
                                setControls()
                                toast("Не удалось сохранить KWS-пример: ${t.message ?: t.javaClass.simpleName}")
                            }
                        }
                    }
                },
                onTimeout = {
                    runOnUiThread {
                        voiceBusy = false
                        refreshKwsCorpusState()
                        setControls()
                        toast("Речь не обнаружена")
                    }
                },
                onError = { t ->
                    runOnUiThread {
                        voiceBusy = false
                        refreshKwsCorpusState()
                        setControls()
                        toast("Ошибка записи KWS: ${t.message ?: t.javaClass.simpleName}")
                    }
                },
            )
        } catch (t: Throwable) {
            voiceBusy = false
            refreshKwsCorpusState()
            setControls()
            toast("Не удалось запустить запись KWS: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun refreshKwsCorpusState() {
        if (!::kwsCorpusStore.isInitialized || !::kwsCorpusStatusText.isInitialized) return
        val summary = kwsCorpusStore.summary()
        val audit = kwsCorpusStore.audit()
        val readiness = when (audit.readiness) {
            KwsCorpusStore.Readiness.NOT_READY -> "ещё не готов к обучению"
            KwsCorpusStore.Readiness.PERSONAL_EXPERIMENT -> "готов к персональному эксперименту"
            KwsCorpusStore.Readiness.MULTI_SPEAKER_EXPERIMENT -> "готов к мультиспикерному эксперименту"
        }
        kwsCorpusStatusText.text = buildString {
            append("KWS-корпус: +${summary.positive} / −${summary.negative} • говорящих ${summary.speakers}")
            if (summary.audioBytes > 0) append(" • ${formatBytes(summary.audioBytes)}")
            append("\nГотовность: $readiness. Данные остаются на телефоне до ручного экспорта.")
        }
        if (::kwsAuditText.isInitialized && summary.total == 0) {
            kwsAuditText.text = "Аудит: добавьте записи, затем нажмите «Проверить готовность корпуса»."
        }
        val available = summary.total > 0 && !kwsCorpusRecorder.isActive && !backgroundActive() && !handsFreeEnabled && !isWorking()
        kwsExportCorpusButton.isEnabled = available
        kwsAuditButton.isEnabled = available
        kwsClearCorpusButton.isEnabled = available
    }

    private fun showKwsAudit() {
        val audit = kwsCorpusStore.audit()
        val readiness = when (audit.readiness) {
            KwsCorpusStore.Readiness.NOT_READY -> "НЕ ГОТОВ"
            KwsCorpusStore.Readiness.PERSONAL_EXPERIMENT -> "ПЕРСОНАЛЬНЫЙ ЭКСПЕРИМЕНТ"
            KwsCorpusStore.Readiness.MULTI_SPEAKER_EXPERIMENT -> "МУЛЬТИСПИКЕРНЫЙ ЭКСПЕРИМЕНТ"
        }
        kwsAuditText.text = buildString {
            append("Готовность: $readiness")
            append("\nЦели: ${audit.targetKeywords.ifEmpty { listOf("—") }.joinToString()}")
            append("\nТочные дубли: ${audit.duplicateAudio} • leakage в negative: ${audit.negativeKeywordLeakage}")
            append("\nОшибки positive-транскрипта: ${audit.positiveTranscriptMismatch} • необычная длительность: ${audit.durationOutliers}")
            if (audit.speakers.isNotEmpty()) {
                append("\nГоворящие: ")
                append(audit.speakers.joinToString { "${it.speakerId}(+${it.positive}/−${it.negative})" })
            }
            if (audit.recommendations.isNotEmpty()) {
                append("\nЧто улучшить:")
                audit.recommendations.take(6).forEach { append("\n• $it") }
            } else {
                append("\nCollection-gate пройден. После обучения проверьте FAR/FRR на held-out test.")
            }
        }
    }

    private fun exportKwsCorpus() {
        val summary = kwsCorpusStore.summary()
        if (summary.total == 0) {
            toast("KWS-корпус пока пуст")
            return
        }
        try {
            val shareDir = File(cacheDir, "shared").apply { mkdirs() }
            val exported = kwsCorpusStore.exportZip(File(shareDir, KWS_CORPUS_ZIP_NAME))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", exported)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Экспорт Sakha KWS-корпуса"))
        } catch (t: Throwable) {
            toast("Не удалось экспортировать KWS-корпус: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun confirmClearKwsCorpus() {
        if (kwsCorpusStore.summary().total == 0) return
        AlertDialog.Builder(this)
            .setTitle("Удалить локальный KWS-корпус?")
            .setMessage("Будут удалены только записанные примеры слова активации и отрицательные фразы. Установленный KWS-пакет останется.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                kwsCorpusStore.clear()
                refreshKwsCorpusState()
                setControls()
                toast("KWS-корпус очищен")
            }
            .show()
    }

    private fun speakLocally(text: String, onDone: (() -> Unit)? = null): Boolean {
        if (SakhaLanguage.looksLikeSakha(text) && !SAKHA_TTS_ENABLED) {
            voiceStatusText.text = "Саха-озвучка пока отключена • текст и распознавание продолжают работать"
            onDone?.invoke()
            return onDone != null
        }
        if (SAKHA_TTS_ENABLED && SakhaLanguage.looksLikeSakha(text) && sakhaNeuralTts.isReady()) {
            if (sakhaNeuralTts.speak(text, onDone)) return true
        }
        return offlineTts.speak(text, onDone)
    }

    private fun toggleVoiceRecording() {
        if (handsFreeEnabled) {
            toast("Сначала выключите режим ассистента")
            return
        }
        if (localRecorder.isRecording) {
            stopAndTranscribeVoice()
            return
        }
        if (voiceBusy || generationJob?.isActive == true || qualityRunning) return

        val model = currentSpeechModel
        if (model == null || !model.exists()) {
            toast("Сначала выберите локальную Whisper-модель .bin")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startVoiceRecordingInternal()
        }
    }

    private fun startVoiceRecordingInternal() {
        if (localRecorder.isRecording || voiceBusy) return
        val model = currentSpeechModel ?: return
        if (!model.exists()) {
            restoreSpeechModelSelection()
            return
        }

        try {
            offlineTts.stop()
        if (::sakhaNeuralTts.isInitialized) sakhaNeuralTts.stop()
            localRecorder.start { error ->
                runOnUiThread {
                    voiceStatusText.text = "Ошибка микрофона: ${error.message ?: error.javaClass.simpleName}"
                    voiceButton.text = "🎤 Начать запись"
                    setControls()
                }
            }
            voiceStatusText.text = "Микрофон: запись 16 кГц идёт локально. Нажмите ещё раз для распознавания."
            voiceButton.text = "■ Остановить и распознать"
            setControls()
        } catch (e: Exception) {
            voiceStatusText.text = "Не удалось начать запись: ${e.message ?: e.javaClass.simpleName}"
            voiceButton.text = "🎤 Начать запись"
            setControls()
        }
    }

    private fun stopAndTranscribeVoice() {
        if (!localRecorder.isRecording) return
        val model = currentSpeechModel ?: return
        voiceBusy = true
        voiceButton.text = "Распознаю…"
        setControls()
        voiceButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val audio = withContext(Dispatchers.IO) { localRecorder.stop() }
                if (audio.size < LocalAudioRecorder.SAMPLE_RATE / 2) {
                    throw IllegalArgumentException("Запись слишком короткая")
                }
                voiceStatusText.text = "whisper.cpp распознаёт речь на телефоне…"
                val transcript = localWhisper.transcribe(model, audio).trim()
                if (transcript.isBlank()) throw IllegalStateException("Речь не распознана")

                lastVoiceAudio = audio.copyOf()
                lastVoiceTranscript = transcript
                asrCorrectionInput.setText(transcript)
                promptInput.setText(transcript)
                promptInput.setSelection(transcript.length)
                voiceStatusText.text = "Распознано локально: ${transcript.take(180)}"

                val autoSend = autoVoiceSendSwitch.isChecked && modelReady
                voiceBusy = false
                voiceButton.text = "🎤 Начать запись"
                setControls()
                if (autoSend) generate(transcript)
            } catch (e: Exception) {
                voiceBusy = false
                voiceButton.text = "🎤 Начать запись"
                voiceStatusText.text = "Ошибка распознавания: ${e.message ?: e.javaClass.simpleName}"
                setControls()
            }
        }
    }

    private fun restoreWakeWords() {
        wakeWordsInput.setText(prefs.getString(KEY_WAKE_WORDS, DEFAULT_WAKE_WORDS) ?: DEFAULT_WAKE_WORDS)
    }

    private fun saveWakeWords() {
        val words = wakeWordsInput.text.toString()
            .split(',')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { normalizeWakeToken(it) }
            .take(12)
        if (words.isEmpty()) {
            toast("Добавьте хотя бы одно слово активации")
            return
        }
        val stored = words.joinToString(", ")
        prefs.edit().putString(KEY_WAKE_WORDS, stored).apply()
        wakeWordsInput.setText(stored)
        toast("Слова активации сохранены локально")
    }

    private fun wakeWords(): List<String> =
        (prefs.getString(KEY_WAKE_WORDS, DEFAULT_WAKE_WORDS) ?: DEFAULT_WAKE_WORDS)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    @Suppress("DEPRECATION")
    private fun backgroundActive(): Boolean {
        val requested = prefs.getBoolean(BackgroundAssistantService.KEY_BACKGROUND_ACTIVE, false)
        if (!requested) return false
        if (backgroundTransition) return true
        val manager = getSystemService(ActivityManager::class.java)
        val running = manager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == BackgroundAssistantService::class.java.name
        }
        if (!running) {
            prefs.edit()
                .putBoolean(BackgroundAssistantService.KEY_BACKGROUND_ACTIVE, false)
                .putString(BackgroundAssistantService.KEY_BACKGROUND_STATUS, "Фоновый сервис не запущен")
                .apply()
        }
        return running
    }

    private fun requestBackgroundMode() {
        val llm = prefs.getString(KEY_MODEL_PATH, null)?.let(::File)
        val stt = prefs.getString(KEY_SPEECH_MODEL_PATH, null)?.let(::File)
        if (llm?.isFile != true || stt?.isFile != true) {
            syncBackgroundUi()
            toast("Для фонового режима сначала выберите LLM и Whisper-модель")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingBackgroundPermission = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startBackgroundModeAfterPermissions()
    }

    private fun startBackgroundModeAfterPermissions() {
        if (handsFreeEnabled) stopHandsFreeMode(userInitiated = false)
        localRecorder.cancel()
        offlineTts.stop()
        if (::sakhaNeuralTts.isInitialized) sakhaNeuralTts.stop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBackgroundNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchBackgroundService()
    }

    private fun launchBackgroundService() {
        backgroundTransition = true
        try {
            val intent = Intent(this, BackgroundAssistantService::class.java)
                .setAction(BackgroundAssistantService.ACTION_START)
            ContextCompat.startForegroundService(this, intent)
            prefs.edit()
                .putBoolean(BackgroundAssistantService.KEY_BACKGROUND_ACTIVE, true)
                .putString(BackgroundAssistantService.KEY_BACKGROUND_STATUS, "Запускаю фоновый режим…")
                .apply()
            modelReady = false
            currentModel = null
            statusText.text = "Модель передана фоновому сервису PhoneAI."
            syncBackgroundUi()
            setControls()
            lifecycleScope.launch {
                delay(700L)
                backgroundTransition = false
                syncBackgroundUi()
                setControls()
            }
        } catch (e: Exception) {
            backgroundTransition = false
            prefs.edit().putBoolean(BackgroundAssistantService.KEY_BACKGROUND_ACTIVE, false).apply()
            syncBackgroundUi()
            toast("Не удалось запустить фон: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopBackgroundMode() {
        pendingBackgroundPermission = false
        pendingBackgroundNotificationPermission = false
        backgroundTransition = true
        runCatching {
            startService(Intent(this, BackgroundAssistantService::class.java).setAction(BackgroundAssistantService.ACTION_STOP))
        }
        modelReady = false
        currentModel = null
        prefs.edit()
            .putBoolean(BackgroundAssistantService.KEY_BACKGROUND_ACTIVE, false)
            .putString(BackgroundAssistantService.KEY_BACKGROUND_STATUS, "Останавливаю фоновый режим…")
            .apply()
        syncBackgroundUi()
        setControls()
        lifecycleScope.launch {
            for (attempt in 0 until 12) {
                delay(200L)
                if (!engineInitialized || engine.state.value is InferenceEngine.State.Initialized) break
            }
            if (engineInitialized && engine.state.value is InferenceEngine.State.Initialized && currentModel == null) {
                restoreLastModel()
            }
            backgroundTransition = false
            prefs.edit().putString(BackgroundAssistantService.KEY_BACKGROUND_STATUS, "Фоновый режим выключен").apply()
            syncBackgroundUi()
            setControls()
        }
    }

    private fun syncBackgroundUi() {
        if (!::backgroundSwitch.isInitialized) return
        val active = backgroundActive()
        suppressBackgroundListener = true
        backgroundSwitch.isChecked = active
        suppressBackgroundListener = false
        val saved = prefs.getString(BackgroundAssistantService.KEY_BACKGROUND_STATUS, null)
        backgroundStateText.text = saved ?: if (active) {
            "Фоновый сервис активен"
        } else {
            "Фоновый режим выключен. Запускайте его, пока PhoneAI открыт на экране."
        }
    }

    private fun requestHandsFreeMode() {
        if (currentSpeechModel == null || !modelReady) {
            suppressHandsFreeListener = true
            handsFreeSwitch.isChecked = false
            suppressHandsFreeListener = false
            toast("Для режима ассистента загрузите и LLM, и Whisper-модель")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            suppressHandsFreeListener = true
            handsFreeSwitch.isChecked = false
            suppressHandsFreeListener = false
            pendingHandsFreePermission = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startHandsFreeMode()
    }

    private fun startHandsFreeMode() {
        if (handsFreeEnabled) return
        if (!modelReady || currentSpeechModel == null) return
        localRecorder.cancel()
        offlineTts.stop()
        if (::sakhaNeuralTts.isInitialized) sakhaNeuralTts.stop()
        handsFreeEnabled = true
        conversationDeadlineMs = 0L
        voiceStatusText.text = "Режим ассистента включён. Жду слово активации: ${wakeWords().joinToString()}"
        setControls()
        scheduleHandsFreeListen(150L)
    }

    private fun stopHandsFreeMode(userInitiated: Boolean) {
        handsFreeEnabled = false
        conversationDeadlineMs = 0L
        autoUtteranceRecorder.cancel()
        if (userInitiated) {
            voiceStatusText.text = "Режим ассистента выключен. Ручная запись остаётся доступна."
        }
        setControls()
    }

    private fun scheduleHandsFreeListen(delayMs: Long = 0L) {
        if (!handsFreeEnabled) return
        lifecycleScope.launch {
            if (delayMs > 0) delay(delayMs)
            if (handsFreeEnabled) beginHandsFreeListen()
        }
    }

    private fun beginHandsFreeListen() {
        if (!handsFreeEnabled || autoUtteranceRecorder.isActive) return
        if (generationJob?.isActive == true || qualityRunning || voiceBusy || localRecorder.isRecording) {
            scheduleHandsFreeListen(250L)
            return
        }
        val speechModel = currentSpeechModel
        if (speechModel == null || !speechModel.exists() || !modelReady) {
            disableHandsFreeWithMessage("Режим ассистента остановлен: модель недоступна")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val inConversation = conversationDeadlineMs > now
        val waitMs = if (inConversation) {
            (conversationDeadlineMs - now).coerceAtLeast(1_000L)
        } else {
            WAKE_LISTEN_SLICE_MS
        }

        try {
            autoUtteranceRecorder.start(
                maxWaitMs = waitMs,
                onState = { state ->
                    runOnUiThread {
                        if (!handsFreeEnabled) return@runOnUiThread
                        voiceStatusText.text = when (state) {
                            AutoUtteranceRecorder.State.WAITING_FOR_SPEECH -> if (inConversation) {
                                "Диалог активен: слушаю следующую фразу. Без команды вернусь к слову активации через 30 секунд."
                            } else {
                                "Жду слово активации: ${wakeWords().joinToString()}"
                            }
                            AutoUtteranceRecorder.State.SPEECH_DETECTED -> "Речь обнаружена локально… закончите фразу."
                            AutoUtteranceRecorder.State.UTTERANCE_READY -> "Тишина обнаружена. Передаю запись в локальный Whisper…"
                        }
                        setControls()
                    }
                },
                onUtterance = { audio ->
                    runOnUiThread { handleHandsFreeUtterance(audio, inConversation) }
                },
                onTimeout = {
                    runOnUiThread {
                        if (!handsFreeEnabled) return@runOnUiThread
                        if (inConversation) {
                            conversationDeadlineMs = 0L
                            voiceStatusText.text = "Окно диалога завершено. Снова жду слово активации."
                        }
                        setControls()
                        scheduleHandsFreeListen(120L)
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        disableHandsFreeWithMessage("Ошибка автоматического микрофона: ${error.message ?: error.javaClass.simpleName}")
                    }
                },
            )
            setControls()
        } catch (e: Exception) {
            disableHandsFreeWithMessage("Не удалось запустить автоматическое слушание: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun handleHandsFreeUtterance(audio: FloatArray, conversationMode: Boolean) {
        if (!handsFreeEnabled) return
        val speechModel = currentSpeechModel ?: return
        voiceBusy = true
        setControls()

        lifecycleScope.launch {
            try {
                if (audio.size < AutoUtteranceRecorder.SAMPLE_RATE / 3) {
                    throw IllegalArgumentException("Фраза слишком короткая")
                }
                val transcript = localWhisper.transcribe(speechModel, audio).trim()
                if (transcript.isBlank()) throw IllegalStateException("Речь не распознана")

                lastVoiceAudio = audio.copyOf()
                lastVoiceTranscript = transcript
                asrCorrectionInput.setText(transcript)
                voiceBusy = false
                promptInput.setText(transcript)
                promptInput.setSelection(transcript.length)

                if (conversationMode) {
                    voiceStatusText.text = "Диалог: $transcript"
                    setControls()
                    generate(transcript, resumeHandsFreeAfter = true)
                    return@launch
                }

                val hit = findWakeHit(transcript)
                if (hit == null) {
                    voiceStatusText.text = "Фраза услышана, но слова активации нет. Продолжаю ждать."
                    setControls()
                    scheduleHandsFreeListen(120L)
                    return@launch
                }

                val command = transcript.substring((hit.index + hit.length).coerceAtMost(transcript.length))
                    .trim { it.isWhitespace() || it in charArrayOf(',', '.', '!', '?', ':', ';', '-', '—') }
                conversationDeadlineMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS

                if (command.isBlank()) {
                    voiceStatusText.text = "PhoneAI активирован. Слушаю команду без повторения слова активации."
                    setControls()
                    scheduleHandsFreeListen(100L)
                } else {
                    promptInput.setText(command)
                    promptInput.setSelection(command.length)
                    voiceStatusText.text = "Активация распознана. Команда: $command"
                    setControls()
                    generate(command, resumeHandsFreeAfter = true)
                }
            } catch (e: Exception) {
                voiceBusy = false
                voiceStatusText.text = "Распознавание: ${e.message ?: e.javaClass.simpleName}. Продолжаю слушать."
                setControls()
                scheduleHandsFreeListen(200L)
            }
        }
    }

    private fun continueHandsFreeAfterAssistant() {
        if (!handsFreeEnabled) return
        conversationDeadlineMs = SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
        voiceStatusText.text = "Ответ завершён. Слушаю следующую команду ещё 30 секунд без слова активации."
        setControls()
        scheduleHandsFreeListen(120L)
    }

    private fun disableHandsFreeWithMessage(message: String) {
        handsFreeEnabled = false
        conversationDeadlineMs = 0L
        autoUtteranceRecorder.cancel()
        suppressHandsFreeListener = true
        handsFreeSwitch.isChecked = false
        suppressHandsFreeListener = false
        voiceStatusText.text = message
        setControls()
    }

    private fun findWakeHit(transcript: String): WakeHit? {
        val comparableText = transcript.lowercase().replace('ё', 'е')
        return wakeWords()
            .sortedByDescending { it.length }
            .firstNotNullOfOrNull { wake ->
                val token = wake.lowercase().replace('ё', 'е').trim()
                val index = comparableText.indexOf(token)
                if (index >= 0) WakeHit(index, token.length) else null
            }
    }

    private fun normalizeWakeToken(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun showModelManager() {
        if (isWorking()) return
        val entries = installedModels()
        if (entries.isEmpty()) {
            toast("Локальных моделей пока нет")
            return
        }

        val savedLlm = prefs.getString(KEY_MODEL_PATH, null)
        val savedSpeech = prefs.getString(KEY_SPEECH_MODEL_PATH, null)
        val labels = entries.map { entry ->
            val active = when (entry.kind) {
                ModelKind.LLM -> entry.file.absolutePath == savedLlm
                ModelKind.SPEECH -> entry.file.absolutePath == savedSpeech
            }
            "${if (active) "✓ " else ""}${entry.kind.label}: ${entry.file.name} • ${formatBytes(entry.file.length())}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Локальные модели")
            .setItems(labels) { _, which ->
                val selected = entries[which]
                when (selected.kind) {
                    ModelKind.LLM -> {
                        if (!engineInitialized) toast("ИИ-ядро ещё запускается")
                        else lifecycleScope.launch { loadModelFile(selected.file, remember = true) }
                    }
                    ModelKind.SPEECH -> {
                        currentSpeechModel = selected.file
                        prefs.edit().putString(KEY_SPEECH_MODEL_PATH, selected.file.absolutePath).apply()
                        refreshSpeechModelState()
                        setControls()
                        toast("Выбрана модель распознавания ${selected.file.name}")
                    }
                }
            }
            .setNeutralButton("Очистить неиспользуемые") { _, _ -> confirmCleanupUnusedModels() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun installedModels(): List<ManagedModel> {
        val llm = File(filesDir, "models").listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .map { ManagedModel(it, ModelKind.LLM) }
        val speech = File(filesDir, "speech_models").listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("bin", ignoreCase = true) }
            .map { ManagedModel(it, ModelKind.SPEECH) }
        return (llm + speech).sortedBy { it.file.name.lowercase() }
    }

    private fun confirmCleanupUnusedModels() {
        val keep = setOfNotNull(
            prefs.getString(KEY_MODEL_PATH, null),
            prefs.getString(KEY_SPEECH_MODEL_PATH, null)
        )
        val unused = installedModels().filter { it.file.absolutePath !in keep }
        if (unused.isEmpty()) {
            toast("Неиспользуемых моделей нет")
            return
        }
        val bytes = unused.sumOf { it.file.length() }
        AlertDialog.Builder(this)
            .setTitle("Освободить ${formatBytes(bytes)}?")
            .setMessage("Будет удалено ${unused.size} неактивных локальных моделей. Выбранные LLM и Whisper останутся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                unused.forEach { it.file.delete() }
                toast("Неиспользуемые модели удалены")
            }
            .show()
    }

    private fun initializeEngine() {
        lifecycleScope.launch {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                val state = engine.state.first {
                    it is InferenceEngine.State.Initialized ||
                        it is InferenceEngine.State.ModelReady ||
                        it is InferenceEngine.State.Error
                }

                when (state) {
                    is InferenceEngine.State.Initialized -> {
                        engineInitialized = true
                        statusText.text = "Ядро готово. Вычисления будут идти на телефоне."
                        refreshBackendState()
                        setControls()
                        if (!backgroundActive()) restoreLastModel()
                    }
                    is InferenceEngine.State.ModelReady -> {
                        engineInitialized = true
                        if (backgroundActive()) {
                            statusText.text = "LLM сейчас используется фоновым сервисом PhoneAI."
                            modelReady = false
                        } else {
                            statusText.text = "Ядро уже содержит загруженную модель."
                            modelReady = true
                            currentModel = prefs.getString(KEY_MODEL_PATH, null)?.let(::File)?.takeIf { it.isFile }
                        }
                        refreshBackendState()
                        setControls()
                    }
                    is InferenceEngine.State.Error -> {
                        statusText.text = "Ошибка запуска ядра: ${state.exception.message ?: state.exception.javaClass.simpleName}"
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                statusText.text = "Не удалось запустить локальное ядро: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private suspend fun restoreLastModel() {
        val path = prefs.getString(KEY_MODEL_PATH, null) ?: return
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            prefs.edit().remove(KEY_MODEL_PATH).apply()
            return
        }

        statusText.text = "Найдена сохранённая модель. Загружаю в RAM…"
        loadModelFile(file, remember = false)
    }

    private suspend fun inspectThenImport(uri: Uri) {
        if (!engineInitialized) {
            toast("ИИ-ядро ещё запускается")
            return
        }

        val sourceName = queryDisplayName(uri) ?: "phoneai-model.gguf"
        if (!sourceName.lowercase().endsWith(".gguf")) {
            toast("Нужен файл модели в формате .gguf")
            return
        }

        val sourceSize = querySize(uri)
        val profile = deviceProfile()

        if (sourceSize > 0 && sourceSize > profile.hardModelLimitBytes) {
            val message = buildString {
                append("Файл ${formatBytes(sourceSize)} слишком велик для безопасного профиля этого телефона.\n\n")
                append("Рекомендуется: ${profile.recommendation}.\n")
                append("Лимит PhoneAI 0.19: около ${formatBytes(profile.hardModelLimitBytes)} для самой модели.")
            }
            AlertDialog.Builder(this)
                .setTitle("Модель слишком большая")
                .setMessage(message)
                .setPositiveButton("Понятно", null)
                .show()
            return
        }

        val freeDisk = filesDir.usableSpace
        if (sourceSize > 0 && freeDisk < (sourceSize * 1.15).toLong()) {
            toast("Недостаточно свободной памяти для копии модели")
            return
        }

        importModel(uri, sourceName)
    }

    private suspend fun importModel(uri: Uri, sourceName: String) {
        setBusy(true, "Копирование GGUF в память приложения…")

        val modelsDir = File(filesDir, "models").apply { mkdirs() }
        val safeName = sanitizeFileName(sourceName)
        val target = File(modelsDir, safeName)
        val temp = File(modelsDir, "$safeName.importing")

        try {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть выбранный файл" }
                    temp.outputStream().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }

                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "Не удалось сохранить модель" }
            }

            loadModelFile(target, remember = true)
        } catch (e: Exception) {
            temp.delete()
            statusText.text = "Ошибка импорта: ${e.message ?: e.javaClass.simpleName}"
            toast("Не удалось импортировать модель")
        } finally {
            setBusy(false, statusText.text.toString())
        }
    }

    private suspend fun loadModelFile(file: File, remember: Boolean) {
        setBusy(true, "Загрузка ${file.name} в RAM…")

        try {
            when (engine.state.value) {
                is InferenceEngine.State.ModelReady,
                is InferenceEngine.State.Error -> engine.cleanUp()
                else -> Unit
            }
            modelReady = false

            val wantsGpu = gpuModeRequested && BuildConfig.OPENCL_BUILD && openClGpuAvailable()
            var usedGpu = false
            try {
                engine.loadModel(file.absolutePath, gpuLayers = if (wantsGpu) GPU_LAYERS_ALL else 0)
                usedGpu = wantsGpu
            } catch (gpuError: Exception) {
                if (!wantsGpu) throw gpuError
                runCatching {
                    if (engine.state.value is InferenceEngine.State.Error) engine.cleanUp()
                }
                statusText.text = "GPU-offload не запустился. Автоматически пробую CPU…"
                engine.loadModel(file.absolutePath, gpuLayers = 0)
                usedGpu = false
            }
            engine.setSystemPrompt(buildRuntimeSystemPrompt())

            activeGpuLayers = if (usedGpu) GPU_LAYERS_ALL else 0
            currentModel = file
            modelReady = true

            if (remember) {
                prefs.edit().putString(KEY_MODEL_PATH, file.absolutePath).apply()
            }

            modelText.text = "${file.name} • ${formatBytes(file.length())}"
            statusText.text = if (activeGpuLayers > 0) {
                "Модель готова. Запрошен OpenCL GPU-offload; интернет не нужен."
            } else {
                "Модель готова на CPU. Интернет для ответа не нужен."
            }
            perfText.text = "Готово к локальному тесту скорости"
            refreshDeviceCard()
            refreshBackendState()
        } catch (e: Exception) {
            modelReady = false
            activeGpuLayers = 0
            currentModel = null
            statusText.text = "Ошибка модели: ${e.message ?: e.javaClass.simpleName}"
            toast("Эта GGUF-модель не загрузилась")
        } finally {
            setBusy(false, statusText.text.toString())
        }
    }

    private suspend fun unloadModel() {
        generationJob?.cancel()
        if (!modelReady) return

        setBusy(true, "Выгружаю модель из RAM…")
        try {
            engine.cleanUp()
            modelReady = false
            activeGpuLayers = 0
            currentModel = null
            statusText.text = "Модель выгружена из RAM. Файл сохранён для следующего запуска."
            modelText.text = "Модель сейчас не загружена"
            perfText.text = "Скорость: —"
            refreshDeviceCard()
            refreshBackendState()
        } catch (e: Exception) {
            statusText.text = "Ошибка выгрузки: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            setBusy(false, statusText.text.toString())
        }
    }

    private fun generate(prompt: String, resumeHandsFreeAfter: Boolean = false) {
        if (!modelReady || generationJob?.isActive == true || qualityRunning || voiceBusy || localRecorder.isRecording) return
        offlineTts.stop()
        if (::sakhaNeuralTts.isInitialized) sakhaNeuralTts.stop()

        val memory = memoryInfo()
        if (memory.lowMemory || memory.availMem < MIN_FREE_MEMORY_BYTES) {
            statusText.text = "Android сообщает о нехватке RAM. Выгрузите другие приложения или выберите модель меньше."
            toast("Недостаточно свободной RAM для безопасной генерации")
            if (resumeHandsFreeAfter) continueHandsFreeAfterAssistant()
            return
        }

        val thermal = thermalStatus()
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
            statusText.text = "Телефон сильно нагрет. Генерация временно остановлена для защиты устройства."
            toast("Подождите, пока телефон остынет")
            if (resumeHandsFreeAfter) continueHandsFreeAfterAssistant()
            return
        }

        lastUserPrompt = prompt
        lastAssistantAnswer = null
        llmCorrectionInput.setText("")

        promptInput.isEnabled = false
        sendButton.isEnabled = false
        modelButton.isEnabled = false
        unloadButton.isEnabled = false
        benchmarkButton.isEnabled = false
        stopButton.isEnabled = true
        answerText.text = ""
        statusText.text = "Генерация полностью локально • язык: ${SakhaLanguage.label(prompt)}"
        perfText.text = "Скорость: считаю…"

        generationJob = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            var pieces = 0
            val buffer = StringBuilder()
            var waitingForTts = false

            try {
                val maxOutput = deviceProfile().predictLength
                engine.sendUserPrompt(prepareInferencePrompt(prompt), predictLength = maxOutput).collect { piece ->
                    pieces++
                    buffer.append(piece)
                    answerText.text = sanitizeAssistantText(buffer.toString(), final = false)

                    if (pieces % 4 == 0) {
                        val seconds = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1) / 1000.0
                        perfText.text = "≈ %.1f фрагм./с • %d фрагментов • %s".format(
                            pieces / seconds,
                            pieces,
                            thermalLabel()
                        )
                    }
                }

                val seconds = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1) / 1000.0
                perfText.text = "≈ %.1f фрагм./с • %d фрагментов • %s".format(
                    pieces / seconds,
                    pieces,
                    thermalLabel()
                )
                var readyAnswer = sanitizeAssistantText(buffer.toString(), final = true)
                if (readyAnswer.isBlank()) {
                    statusText.text = "Финальный ответ пуст. Повторяю локально без режима размышления…"
                    answerText.text = "Формирую короткий готовый ответ…"
                    val retryBuffer = StringBuilder()
                    engine.sendUserPrompt(
                        buildDirectRetryPrompt(prompt),
                        predictLength = DIRECT_RETRY_PREDICT_LENGTH
                    ).collect { piece ->
                        retryBuffer.append(piece)
                        val visible = sanitizeAssistantText(retryBuffer.toString(), final = false)
                        if (visible.isNotBlank()) answerText.text = visible
                    }
                    readyAnswer = sanitizeAssistantText(retryBuffer.toString(), final = true)
                }
                if (readyAnswer.isBlank()) {
                    readyAnswer = "Не удалось получить финальный текст от локальной модели. Попробуйте ещё раз."
                }
                answerText.text = readyAnswer
                lastAssistantAnswer = readyAnswer
                llmCorrectionInput.setText(readyAnswer)
                statusText.text = if (activeGpuLayers > 0) {
                    "Готово. Ответ создан локально с запросом GPU-offload."
                } else {
                    "Готово. Ответ создан процессором телефона."
                }
                if (speakSwitch.isChecked && readyAnswer.isNotBlank()) {
                    val callback = if (resumeHandsFreeAfter) {
                        { runOnUiThread { continueHandsFreeAfterAssistant() } }
                    } else null
                    waitingForTts = speakLocally(readyAnswer, callback) && resumeHandsFreeAfter
                    if (!waitingForTts && resumeHandsFreeAfter && !offlineTts.canSpeakOffline()) {
                        voiceStatusText.text = "Озвучка пропущена: офлайн-голос недоступен"
                    }
                }
            } catch (_: CancellationException) {
                statusText.text = "Генерация остановлена."
                perfText.text = "Остановлено пользователем • ${thermalLabel()}"
            } catch (e: Exception) {
                statusText.text = "Ошибка генерации: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                generationJob = null
                setControls()
                refreshDeviceCard()
                if (resumeHandsFreeAfter && !waitingForTts) {
                    continueHandsFreeAfterAssistant()
                }
            }
        }
    }

    private fun prepareInferencePrompt(prompt: String): String {
        val routed = SakhaLanguage.routePrompt(prompt)
        val isQwen3 = currentModel?.name?.contains("qwen3", ignoreCase = true) == true
        return if (isQwen3) "$routed\n/no_think" else routed
    }

    private fun buildDirectRetryPrompt(prompt: String): String {
        val languageHint = when (SakhaLanguage.classify(prompt)) {
            SakhaLanguage.LanguageClass.SAKHA -> "Саха тылынан биир кыра, туһалаах эппиэти биэр."
            SakhaLanguage.LanguageClass.MIXED -> "Ответь кратко на языке основной просьбы пользователя."
            SakhaLanguage.LanguageClass.OTHER -> "Ответь прямо и кратко по-русски."
        }
        return buildString {
            append("Повтори ответ на исходный запрос без рассуждений и без тегов <think>. ")
            append(languageHint)
            append("\nИсходный запрос: ")
            append(prompt)
            append("\n/no_think")
        }
    }

    private fun sanitizeAssistantText(value: String, final: Boolean): String {
        var clean = value.replace(Regex("(?is)<think>.*?</think>"), "")
        clean = clean.replace(Regex("(?is)<think>.*$"), "")
        clean = clean.replace(Regex("(?is)</think>"), "")
        clean = clean.trim()
        if (!final) return clean
        return clean.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun runBenchmark() {
        if (!modelReady || isWorking()) return

        lifecycleScope.launch {
            setBusy(true, "Тестирую скорость локальной модели…")
            benchmarkButton.isEnabled = false
            try {
                val result = engine.bench(pp = 128, tg = 32, pl = 1, nr = 1)
                perfText.text = result.trim()
                statusText.text = "Тест скорости завершён."
                refreshBackendState(result)
            } catch (e: Exception) {
                statusText.text = "Ошибка теста: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                setBusy(false, statusText.text.toString())
            }
        }
    }

    private fun runQualitySuite() {
        val file = currentModel ?: return
        if (!modelReady || isWorking()) return

        qualityRunning = true
        qualityJob = lifecycleScope.launch {
            val tests = listOf(
                QualityCase("Арифметика", "Сколько будет 7 + 5? Ответь только числом.", listOf("12")),
                QualityCase("Последовательность", "Продолжи: 2, 4, 6, 8, ? Ответь только числом.", listOf("10")),
                QualityCase("Цвет", "Какой цвет получается при смешении синего и жёлтого? Ответь одним словом.", listOf("зелёный", "зеленый")),
                QualityCase("Инструкция", "Напиши только слово: готово", listOf("готово"), exact = true)
            )
            val lines = mutableListOf<String>()
            var passed = 0
            try {
                setBusy(true, "Подготавливаю чистый контекст для теста качества…")
                loadModelFile(file, remember = false)
                qualityResultText.text = "Тест 0/${tests.size}…"

                tests.forEachIndexed { index, test ->
                    statusText.text = "Тест качества ${index + 1}/${tests.size}: ${test.name}…"
                    val output = StringBuilder()
                    engine.sendUserPrompt(test.prompt, predictLength = QUALITY_PREDICT_LENGTH).collect { piece ->
                        output.append(piece)
                    }
                    val clean = normalizeAnswer(output.toString())
                    val ok = if (test.exact) {
                        test.expected.any { normalizeAnswer(it) == clean }
                    } else {
                        test.expected.any { clean.contains(normalizeAnswer(it)) }
                    }
                    if (ok) passed++
                    lines += "${if (ok) "✓" else "✗"} ${test.name}: ${output.toString().trim().take(100)}"
                    qualityResultText.text = "Тест ${index + 1}/${tests.size} • пройдено $passed\n${lines.joinToString("\n")}"
                }

                qualityResultText.text = "Smoke-test: $passed/${tests.size}\n${lines.joinToString("\n")}\n\nЭто тест регрессий, а не абсолютная оценка интеллекта модели."
                statusText.text = "Тест качества завершён. Восстанавливаю чистый чат…"
            } catch (e: Exception) {
                qualityResultText.text = "Тест прерван: ${e.message ?: e.javaClass.simpleName}\n${lines.joinToString("\n")}"
            } finally {
                runCatching { loadModelFile(file, remember = false) }
                qualityRunning = false
                qualityJob = null
                setControls()
                statusText.text = "Модель готова после теста качества."
            }
        }
    }

    private fun normalizeAnswer(value: String): String = value
        .lowercase()
        .trim()
        .replace(Regex("[\\s\\n\\r\\t]+"), " ")
        .replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$"), "")

    private fun backendInventory(): String = if (!engineInitialized) "" else
        runCatching { engine.backendDevices() }.getOrDefault("")

    private fun openClGpuAvailable(): Boolean = backendInventory().lineSequence().any { line ->
        val upper = line.uppercase()
        upper.startsWith("OPENCL|") && (upper.endsWith("|GPU") || upper.endsWith("|IGPU"))
    }

    private fun refreshBackendState(benchmark: String? = null) {
        val inventory = backendInventory()
        val openClLines = inventory.lineSequence().filter { it.uppercase().startsWith("OPENCL|") }.toList()
        val gpuFound = openClLines.any {
            val upper = it.uppercase()
            upper.endsWith("|GPU") || upper.endsWith("|IGPU")
        }

        backendStateText.text = buildString {
            when {
                !BuildConfig.OPENCL_BUILD -> append("Сборка: CPU. GPU-код не включён в этот APK.")
                !engineInitialized -> append("OpenCL: ожидаю инициализацию ядра…")
                gpuFound -> {
                    append("OpenCL GPU найден: ")
                    append(openClLines.joinToString { it.split('|').getOrNull(2).orEmpty().ifBlank { it } })
                    append(". Режим GPU можно включить экспериментально.")
                }
                else -> append("OpenCL включён в APK, но совместимый GPU backend не обнаружен. Используется CPU.")
            }
            if (activeGpuLayers > 0) append("\nТекущая модель загружена с GPU-offload preference.")
            if (!benchmark.isNullOrBlank()) {
                val backendLine = benchmark.lineSequence().firstOrNull { it.contains("OPENCL", ignoreCase = true) }
                if (backendLine != null) append("\nBenchmark видит OpenCL backend.")
            }
        }

        val canToggle = BuildConfig.OPENCL_BUILD && engineInitialized && gpuFound && !isWorking() && !handsFreeEnabled && !autoUtteranceRecorder.isActive
        gpuSwitch.isEnabled = canToggle
        if (!gpuFound && gpuSwitch.isChecked) {
            gpuModeRequested = false
            prefs.edit().putBoolean(KEY_GPU_EXPERIMENTAL, false).apply()
            gpuSwitch.isChecked = false
        }
    }

    private fun isWorking(): Boolean =
        generationJob?.isActive == true || qualityRunning || voiceBusy || localRecorder.isRecording

    private fun saveIdentityProfile() {
        val value = systemPromptInput.text.toString().trim()
        if (value.length < 10) {
            toast("Описание PhoneAI слишком короткое")
            return
        }
        prefs.edit().putString(KEY_SYSTEM_PROMPT, value.take(MAX_PROFILE_CHARS)).apply()
        systemPromptInput.setText(value.take(MAX_PROFILE_CHARS))
        toast("Профиль PhoneAI сохранён")
        reloadCurrentModelForCustomization("Профиль сохранён. Обновляю поведение модели…")
    }

    private fun saveLocalMemory() {
        val value = memoryInput.text.toString().trim().take(MAX_MEMORY_CHARS)
        prefs.edit().putString(KEY_LOCAL_MEMORY, value).apply()
        memoryInput.setText(value)
        refreshMemoryState(value)
        toast("Локальная память сохранена на телефоне")
        reloadCurrentModelForCustomization("Память сохранена. Обновляю контекст модели…")
    }

    private fun clearLocalMemory() {
        prefs.edit().remove(KEY_LOCAL_MEMORY).apply()
        memoryInput.setText("")
        refreshMemoryState("")
        toast("Локальная память очищена")
        reloadCurrentModelForCustomization("Память очищена. Обновляю контекст модели…")
    }

    private fun reloadCurrentModelForCustomization(status: String) {
        val file = currentModel ?: run {
            statusText.text = status.substringBefore(". ")
            return
        }
        if (isWorking()) {
            toast("Сначала остановите генерацию")
            return
        }
        lifecycleScope.launch {
            statusText.text = status
            loadModelFile(file, remember = false)
        }
    }

    private fun buildRuntimeSystemPrompt(): String {
        val identity = trainingSystemPrompt()
        val memory = prefs.getString(KEY_LOCAL_MEMORY, "").orEmpty().trim()
        return if (memory.isBlank()) {
            identity
        } else {
            "$identity\n\nЛокальная память пользователя. Используй её только когда она относится к запросу:\n${memory.take(MAX_MEMORY_CHARS)}"
        }
    }

    private fun trainingSystemPrompt(): String =
        prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SYSTEM_PROMPT

    private fun refreshMemoryState(memory: String = prefs.getString(KEY_LOCAL_MEMORY, "").orEmpty()) {
        memoryStateText.text = if (memory.isBlank()) {
            "Память пуста. Она хранится только в приложении."
        } else {
            "Локальная память: ${memory.length} символов из $MAX_MEMORY_CHARS"
        }
    }

    private fun addTrainingExample() {
        val prompt = teachPromptInput.text.toString().trim()
        val answer = teachAnswerInput.text.toString().trim()
        if (prompt.isBlank() || answer.isBlank()) {
            toast("Заполните и вопрос, и идеальный ответ")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "training").apply { mkdirs() }
                val dataset = File(dir, DATASET_FILE_NAME)
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", trainingSystemPrompt()))
                    .put(JSONObject().put("role", "user").put("content", prompt))
                    .put(JSONObject().put("role", "assistant").put("content", answer))
                val record = JSONObject()
                    .put("messages", messages)
                    .put("source", "phoneai-android")
                    .put("format_version", 1)

                dataset.appendText(record.toString() + "\n", Charsets.UTF_8)

                withContext(Dispatchers.Main) {
                    teachPromptInput.setText("")
                    teachAnswerInput.setText("")
                    refreshDatasetState()
                    toast("Пример добавлен в набор обучения")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Не удалось сохранить пример: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun refreshDatasetState() {
        val file = trainingDatasetFile()
        val count = if (file.exists()) {
            runCatching { file.useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0)
        } else 0
        datasetStateText.text = buildString {
            append("Примеров обучения: $count")
            if (count > 0) append(" • ${formatBytes(file.length())}")
            append("\nФормат: JSONL chat messages • готов для LoRA-пайплайна")
        }
        exportDatasetButton.isEnabled = count > 0
        clearDatasetButton.isEnabled = count > 0
    }

    private fun trainingDatasetFile(): File =
        File(File(filesDir, "training"), DATASET_FILE_NAME)

    private fun exportTrainingDataset() {
        val source = trainingDatasetFile()
        if (!source.exists() || source.length() == 0L) {
            toast("Набор обучения пока пуст")
            return
        }

        try {
            val shareDir = File(cacheDir, "shared").apply { mkdirs() }
            val exported = File(shareDir, DATASET_FILE_NAME)
            source.copyTo(exported, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", exported)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Экспорт набора PhoneAI"))
        } catch (e: Exception) {
            toast("Не удалось экспортировать набор: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun confirmClearDataset() {
        if (!trainingDatasetFile().exists()) return
        AlertDialog.Builder(this)
            .setTitle("Очистить набор обучения?")
            .setMessage("Будут удалены только сохранённые примеры обучения. Модель и локальная память останутся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Очистить") { _, _ ->
                trainingDatasetFile().delete()
                refreshDatasetState()
                toast("Набор обучения очищен")
            }
            .show()
    }

    private fun saveAsrCorrection() {
        val audio = lastVoiceAudio
        val recognized = lastVoiceTranscript.orEmpty()
        val corrected = asrCorrectionInput.text.toString().trim()
        if (audio == null || audio.isEmpty() || recognized.isBlank()) {
            toast("Сначала запишите и распознайте фразу")
            return
        }
        if (corrected.isBlank()) {
            toast("Введите правильный текст распознанной фразы")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                feedbackStore.saveAsrCorrection(
                    audio = audio,
                    recognized = recognized,
                    corrected = corrected,
                    sampleRate = LocalAudioRecorder.SAMPLE_RATE,
                )
                withContext(Dispatchers.Main) {
                    lastVoiceAudio = null
                    lastVoiceTranscript = null
                    asrCorrectionInput.setText("")
                    feedbackAnalysisText.text = "Есть новые данные. Нажмите «Анализ повторяющихся ошибок»."
                    refreshFeedbackState()
                    setControls()
                    toast("Исправление речи сохранено только на телефоне")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Не удалось сохранить исправление: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun saveLlmCorrection() {
        val prompt = lastUserPrompt.orEmpty()
        val modelAnswer = lastAssistantAnswer.orEmpty()
        val corrected = llmCorrectionInput.text.toString().trim()
        if (prompt.isBlank() || modelAnswer.isBlank()) {
            toast("Сначала получите ответ PhoneAI")
            return
        }
        if (corrected.isBlank()) {
            toast("Введите правильный ответ")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                feedbackStore.saveLlmCorrection(
                    prompt = prompt,
                    modelAnswer = modelAnswer,
                    correctedAnswer = corrected,
                    systemPrompt = trainingSystemPrompt(),
                )
                withContext(Dispatchers.Main) {
                    llmCorrectionInput.setText("")
                    lastUserPrompt = null
                    lastAssistantAnswer = null
                    feedbackAnalysisText.text = "Есть новые данные. Нажмите «Анализ повторяющихся ошибок»."
                    refreshFeedbackState()
                    setControls()
                    toast("Исправленный ответ сохранён для будущего обучения")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Не удалось сохранить исправление: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun refreshFeedbackState() {
        if (!::feedbackStore.isInitialized || !::feedbackStateText.isInitialized) return
        val summary = feedbackStore.summary()
        feedbackStateText.text = buildString {
            append("Исправления: речь ${summary.asrCount} • ответы ${summary.llmCount}")
            if (summary.audioBytes > 0) append(" • аудио ${formatBytes(summary.audioBytes)}")
            append("\nХранится локально. Ничего не отправляется автоматически.")
        }
        val hasAny = summary.asrCount + summary.llmCount > 0
        exportFeedbackButton.isEnabled = hasAny
        analyzeFeedbackButton.isEnabled = hasAny
        clearFeedbackButton.isEnabled = hasAny
        if (!hasAny && ::feedbackAnalysisText.isInitialized) {
            feedbackAnalysisText.text = "Анализ появится после сохранения исправлений."
        }
    }

    private fun showFeedbackAnalysis() {
        val summary = feedbackStore.summary()
        if (summary.asrCount + summary.llmCount == 0) {
            toast("Исправлений пока нет")
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            val analysis = feedbackStore.analyzeErrors()
            withContext(Dispatchers.Main) {
                feedbackAnalysisText.text = analysis.toDisplayText()
                toast("Анализ ошибок обновлён")
            }
        }
    }

    private fun exportFeedbackBundle() {
        val summary = feedbackStore.summary()
        if (summary.asrCount + summary.llmCount == 0) {
            toast("Исправлений пока нет")
            return
        }
        try {
            val shareDir = File(cacheDir, "shared").apply { mkdirs() }
            val exported = feedbackStore.exportZip(File(shareDir, FEEDBACK_ZIP_NAME))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", exported)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Экспорт исправлений PhoneAI Sakha"))
        } catch (e: Exception) {
            toast("Не удалось экспортировать исправления: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun confirmClearFeedback() {
        val summary = feedbackStore.summary()
        if (summary.asrCount + summary.llmCount == 0) return
        AlertDialog.Builder(this)
            .setTitle("Удалить локальные исправления?")
            .setMessage("Будут удалены сохранённые аудиофрагменты и исправленные ответы. Модели и обычный набор обучения останутся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                feedbackStore.clear()
                lastVoiceAudio = null
                lastVoiceTranscript = null
                lastUserPrompt = null
                lastAssistantAnswer = null
                asrCorrectionInput.setText("")
                llmCorrectionInput.setText("")
                feedbackAnalysisText.text = "Анализ появится после сохранения исправлений."
                refreshFeedbackState()
                setControls()
                toast("Локальные исправления удалены")
            }
            .show()
    }

    private fun setBusy(busy: Boolean, status: String) {
        statusText.text = status
        applyControlState(busy || isWorking())
    }

    private fun setControls() {
        applyControlState(isWorking())
    }

    private fun applyControlState(working: Boolean) {
        val assistantListening = autoUtteranceRecorder.isActive || kwsCorpusRecorder.isActive
        val background = backgroundActive()
        val locked = working || assistantListening || background || backgroundTransition
        modelButton.isEnabled = engineInitialized && !locked
        manageModelsButton.isEnabled = !locked
        speechModelButton.isEnabled = !locked
        unloadButton.isEnabled = modelReady && !locked
        benchmarkButton.isEnabled = modelReady && !locked
        qualityTestButton.isEnabled = modelReady && !locked
        sendButton.isEnabled = modelReady && !locked && !handsFreeEnabled
        promptInput.isEnabled = modelReady && !locked && !handsFreeEnabled
        stopButton.isEnabled = generationJob?.isActive == true
        voiceButton.isEnabled = !background && !handsFreeEnabled && (localRecorder.isRecording || (currentSpeechModel != null && !working))
        autoVoiceSendSwitch.isEnabled = !working && !handsFreeEnabled && !background
        speakSwitch.isEnabled = (offlineTts.canSpeakOffline() || (::sakhaNeuralTts.isInitialized && sakhaNeuralTts.isReady())) && !working && !background
        sakhaTtsPackButton.isEnabled = !working && !background
        kwsPackButton.isEnabled = !working && !background
        lightweightWakeSwitch.isEnabled = WakeWordPackManager.current(this) != null && !working && !background
        val canCollectKws = !working && !background && !handsFreeEnabled && !kwsCorpusRecorder.isActive
        kwsTargetInput.isEnabled = canCollectKws
        kwsSpeakerInput.isEnabled = canCollectKws
        kwsNegativeTextInput.isEnabled = canCollectKws
        kwsConsentSwitch.isEnabled = canCollectKws
        kwsPositiveButton.isEnabled = canCollectKws
        kwsNegativeButton.isEnabled = canCollectKws
        if (::kwsCorpusStore.isInitialized) {
            val kwsSummary = kwsCorpusStore.summary()
            kwsExportCorpusButton.isEnabled = canCollectKws && kwsSummary.total > 0
            kwsAuditButton.isEnabled = canCollectKws && kwsSummary.total > 0
            kwsClearCorpusButton.isEnabled = canCollectKws && kwsSummary.total > 0
        }
        handsFreeSwitch.isEnabled = !background && (handsFreeEnabled || (currentSpeechModel != null && modelReady && !working))
        backgroundSwitch.isEnabled = background || (!working && prefs.getString(KEY_MODEL_PATH, null)?.let(::File)?.isFile == true && currentSpeechModel != null)
        wakeWordsInput.isEnabled = !working && !handsFreeEnabled && !background
        saveWakeWordsButton.isEnabled = !working && !handsFreeEnabled && !background
        customizationControlsEnabled(!locked)
        syncBackgroundUi()
        refreshBackendState()
    }

    private fun customizationControlsEnabled(enabled: Boolean) {
        systemPromptInput.isEnabled = enabled
        saveProfileButton.isEnabled = enabled
        memoryInput.isEnabled = enabled
        saveMemoryButton.isEnabled = enabled
        clearMemoryButton.isEnabled = enabled
        teachPromptInput.isEnabled = enabled
        teachAnswerInput.isEnabled = enabled
        addTrainingExampleButton.isEnabled = enabled
        asrCorrectionInput.isEnabled = enabled && lastVoiceAudio != null
        saveAsrCorrectionButton.isEnabled = enabled && lastVoiceAudio != null && !lastVoiceTranscript.isNullOrBlank()
        llmCorrectionInput.isEnabled = enabled && !lastAssistantAnswer.isNullOrBlank()
        saveLlmCorrectionButton.isEnabled = enabled && !lastUserPrompt.isNullOrBlank() && !lastAssistantAnswer.isNullOrBlank()
        if (enabled) {
            refreshDatasetState()
            refreshFeedbackState()
        } else {
            exportDatasetButton.isEnabled = false
            clearDatasetButton.isEnabled = false
            exportFeedbackButton.isEnabled = false
            analyzeFeedbackButton.isEnabled = false
            clearFeedbackButton.isEnabled = false
        }
    }

    private fun refreshDeviceCard() {
        val profile = deviceProfile()
        val memory = memoryInfo()
        val totalGb = memory.totalMem / GIB.toDouble()
        val availableGb = memory.availMem / GIB.toDouble()
        val cores = Runtime.getRuntime().availableProcessors()
        val soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()

        deviceText.text = buildString {
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("SoC: ${if (soc.isBlank()) Build.HARDWARE else soc}")
            appendLine("Android ${Build.VERSION.RELEASE} • ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("CPU-потоков: $cores")
            append("RAM: %.1f ГБ всего • %.1f ГБ свободно • %s".format(totalGb, availableGb, thermalLabel()))
        }

        profileText.text = buildString {
            appendLine("Профиль: ${profile.name}")
            appendLine("Рекомендуемая модель: ${profile.recommendation}")
            appendLine("База PhoneAI 0.19: Qwen3-0.6B → LoRA → GGUF")
            appendLine("Ускорение: ${if (BuildConfig.OPENCL_BUILD) "CPU + экспериментальный OpenCL" else "CPU"}")
            append("Макс. ответ PhoneAI 0.19: ${profile.predictLength} токенов")
        }
    }

    private fun deviceProfile(): DeviceProfile {
        val total = memoryInfo().totalMem
        val gb = total / GIB.toDouble()

        val base = when {
            gb < 5.5 -> DeviceProfile(
                name = "Compact",
                recommendation = "0.5B–1.5B, Q4",
                predictLength = 256,
                hardModelLimitBytes = (total * 0.34).toLong()
            )
            gb < 8.5 -> DeviceProfile(
                name = "Balanced",
                recommendation = "1B–3B, Q4",
                predictLength = 384,
                hardModelLimitBytes = (total * 0.40).toLong()
            )
            gb < 12.5 -> DeviceProfile(
                name = "Strong",
                recommendation = "2B–4B, Q4",
                predictLength = 512,
                hardModelLimitBytes = (total * 0.43).toLong()
            )
            else -> DeviceProfile(
                name = "Max",
                recommendation = "3B–8B, Q4 (по памяти и нагреву)",
                predictLength = 768,
                hardModelLimitBytes = (total * 0.46).toLong()
            )
        }

        return base.copy(
            hardModelLimitBytes = max(base.hardModelLimitBytes, 700L * MIB)
        )
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    }

    private fun thermalStatus(): Int {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    private fun thermalLabel(): String = when (thermalStatus()) {
        PowerManager.THERMAL_STATUS_NONE -> "нагрев: нормальный"
        PowerManager.THERMAL_STATUS_LIGHT -> "нагрев: лёгкий"
        PowerManager.THERMAL_STATUS_MODERATE -> "нагрев: средний"
        PowerManager.THERMAL_STATUS_SEVERE -> "нагрев: высокий"
        PowerManager.THERMAL_STATUS_CRITICAL -> "нагрев: критический"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "нагрев: аварийный"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "нагрев: отключение"
        else -> "нагрев: неизвестно"
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun querySize(uri: Uri): Long {
        val projection = arrayOf(OpenableColumns.SIZE)
        val fromCursor = contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
        } ?: -1L
        if (fromCursor >= 0) return fromCursor

        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (sanitized.lowercase().endsWith(".gguf")) sanitized else "$sanitized.gguf"
    }

    private fun sanitizeSpeechFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (sanitized.lowercase().endsWith(".bin")) sanitized else "$sanitized.bin"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "неизвестный размер"
        return when {
            bytes >= GIB -> "%.2f ГБ".format(bytes / GIB.toDouble())
            bytes >= MIB -> "%.0f МБ".format(bytes / MIB.toDouble())
            else -> "%.0f КБ".format(bytes / 1024.0)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        syncBackgroundUi()
        setControls()
        if (!backgroundActive() && engineInitialized && !modelReady && !backgroundTransition) {
            lifecycleScope.launch {
                if (engine.state.value is InferenceEngine.State.Initialized) restoreLastModel()
            }
        }
    }

    override fun onDestroy() {
        autoUtteranceRecorder.cancel()
        kwsCorpusRecorder.cancel()
        localRecorder.cancel()
        if (::offlineTts.isInitialized) offlineTts.shutdown()
        if (::sakhaNeuralTts.isInitialized) sakhaNeuralTts.shutdown()
        super.onDestroy()
    }

    data class QualityCase(
        val name: String,
        val prompt: String,
        val expected: List<String>,
        val exact: Boolean = false
    )

    data class DeviceProfile(
        val name: String,
        val recommendation: String,
        val predictLength: Int,
        val hardModelLimitBytes: Long
    )

    data class ManagedModel(val file: File, val kind: ModelKind)
    data class WakeHit(val index: Int, val length: Int)

    enum class ModelKind(val label: String) {
        LLM("LLM"),
        SPEECH("Whisper")
    }

    companion object {
        private const val PREFS_NAME = "phoneai_core"
        private const val SAKHA_TTS_ENABLED = false
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_LOCAL_MEMORY = "local_memory"
        private const val KEY_GPU_EXPERIMENTAL = "gpu_experimental"
        private const val KEY_SPEECH_MODEL_PATH = "speech_model_path"
        private const val KEY_WAKE_WORDS = "wake_words"
        private const val KEY_LIGHTWEIGHT_WAKE = "lightweight_wake_enabled"
        private const val KEY_KWS_TARGET = "kws_target_keyword"
        private const val KEY_KWS_SPEAKER = "kws_speaker_id"

        private const val DATASET_FILE_NAME = "phoneai_train.jsonl"
        private const val FEEDBACK_ZIP_NAME = "phoneai_sakha_feedback.zip"
        private const val KWS_CORPUS_ZIP_NAME = "phoneai_sakha_kws_corpus.zip"

        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * 1024L * 1024L
        private const val MIN_FREE_MEMORY_BYTES = 384L * MIB
        private const val MAX_PROFILE_CHARS = 4000
        private const val MAX_MEMORY_CHARS = 3000
        private const val GPU_LAYERS_ALL = 99
        private const val QUALITY_PREDICT_LENGTH = 48
        private const val DIRECT_RETRY_PREDICT_LENGTH = 192
        private const val MAX_SPEECH_MODEL_BYTES = 1800L * MIB
        private const val CONVERSATION_WINDOW_MS = 30_000L
        private const val WAKE_LISTEN_SLICE_MS = 15_000L
        private const val DEFAULT_WAKE_WORDS = "PhoneAI, Phone AI, Фонай, Фонэй, Джарвис"

        private const val DEFAULT_SYSTEM_PROMPT =
            "Ты PhoneAI, локальный мобильный ИИ. Отвечай полезно, ясно и кратко. " +
                "Если пользователь пишет или говорит на якутском (саха) языке, отвечай на якутском (саха) и не переходи на русский без просьбы. " +
                "Сохраняй буквы ҕ, ҥ, ө, һ, ү и не выдумывай неизвестные якутские слова или формы. " +
                "Если пользователь обращается по-русски, отвечай по-русски. " +
                "Ты работаешь непосредственно на устройстве пользователя без облачного API. " +
                "Не выдавай догадки за факты и сообщай, когда тебе не хватает данных. " +
                "Не показывай скрытые рассуждения и содержимое тегов <think>."
    }
}
