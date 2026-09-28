package com.phoneai.core.chat

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.phoneai.core.R
import com.phoneai.core.voice.SakhaLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var messagesScroll: ScrollView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var pendingAttachmentsText: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var addAttachmentButton: Button
    private lateinit var newChatButton: Button
    private lateinit var historyButton: Button

    private lateinit var store: ChatStore
    private lateinit var activeChat: ChatConversation
    private lateinit var engine: InferenceEngine

    private var modelReady = false
    private var currentModelFile: File? = null
    private var generationJob: Job? = null
    private val pendingAttachments = mutableListOf<ChatAttachment>()

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val attachmentPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            statusText.text = "Импортирую вложения…"
            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { store.importAttachment(activeChat.id, uri) }.getOrNull()
                }
            }
            pendingAttachments += imported
            renderPendingAttachments()
            statusText.text = if (imported.isNotEmpty()) {
                "Вложения готовы: " + imported.size
            } else {
                "Не удалось импортировать вложения"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        bindViews()
        store = ChatStore(this)
        activeChat = store.listConversations().firstOrNull() ?: store.createConversation()

        newChatButton.setOnClickListener { createNewChat() }
        historyButton.setOnClickListener { showHistory() }
        addAttachmentButton.setOnClickListener { attachmentPicker.launch(arrayOf("*/*")) }
        sendButton.setOnClickListener { sendMessage() }

        renderChat()
        initializeEngine()
    }

    override fun onDestroy() {
        generationJob?.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.chatStatusText)
        titleText = findViewById(R.id.chatTitleText)
        messagesScroll = findViewById(R.id.messagesScroll)
        messagesContainer = findViewById(R.id.messagesContainer)
        pendingAttachmentsText = findViewById(R.id.pendingAttachmentsText)
        input = findViewById(R.id.chatInput)
        sendButton = findViewById(R.id.chatSendButton)
        addAttachmentButton = findViewById(R.id.addAttachmentButton)
        newChatButton = findViewById(R.id.newChatButton)
        historyButton = findViewById(R.id.historyButton)
    }

    private fun initializeEngine() {
        sendButton.isEnabled = false
        lifecycleScope.launch {
            try {
                statusText.text = "Подключаю локальную модель…"
                engine = AiChat.getInferenceEngine(applicationContext)
                val state = engine.state.first {
                    it is InferenceEngine.State.Initialized ||
                        it is InferenceEngine.State.ModelReady ||
                        it is InferenceEngine.State.Error
                }
                if (state is InferenceEngine.State.Error) {
                    statusText.text = "Ошибка локального движка"
                    return@launch
                }
                loadModelForActiveChat()
            } catch (e: Exception) {
                modelReady = false
                statusText.text = "Не удалось запустить чат: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun loadModelForActiveChat() {
        val path = prefs.getString(KEY_MODEL_PATH, null)
        val file = path?.let(::File)
        if (file == null || !file.exists()) {
            modelReady = false
            sendButton.isEnabled = false
            statusText.text = "Сначала выберите GGUF-модель на главном экране"
            return
        }

        modelReady = false
        sendButton.isEnabled = false
        statusText.text = "Готовлю изолированный контекст чата…"
        when (engine.state.value) {
            is InferenceEngine.State.ModelReady,
            is InferenceEngine.State.Error -> engine.cleanUp()
            else -> Unit
        }
        engine.loadModel(file.absolutePath, gpuLayers = 0)
        engine.setSystemPrompt(buildSystemPrompt(activeChat))
        currentModelFile = file
        modelReady = true
        sendButton.isEnabled = true
        statusText.text = "Локальная модель готова • чат изолирован"
    }

    private fun buildSystemPrompt(chat: ChatConversation): String {
        val base = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)
            ?.trim()
            .orEmpty()
            .ifBlank { DEFAULT_SYSTEM_PROMPT }
        val memory = prefs.getString(KEY_LOCAL_MEMORY, "").orEmpty().trim()
        val history = chat.messages
            .takeLast(14)
            .joinToString("\n") { message ->
                val who = if (message.role == "assistant") "PhoneAI" else "Пользователь"
                who + ": " + message.text.take(700)
            }
            .takeLast(7000)
        val pinned = chat.messages
            .flatMap { it.attachments }
            .filter { it.pinned }
            .joinToString(", ") { it.name }

        return buildString {
            append(base)
            append("\n\nПравила ответа: не показывай скрытые рассуждения и содержимое тегов <think>. ")
            append("Отвечай на языке основной просьбы пользователя. ")
            append("Если готового ответа нет, скажи об этом прямо и кратко.")
            if (memory.isNotBlank()) {
                append("\n\nЛокальная память пользователя:\n")
                append(memory.take(3000))
            }
            if (history.isNotBlank()) {
                append("\n\nИстория только этого чата:\n")
                append(history)
            }
            if (pinned.isNotBlank()) {
                append("\n\nЗакреплённые вложения этого чата: ")
                append(pinned)
                append(". Модель получает только имена и типы вложений, если их содержимое явно не передано текстом.")
            }
        }
    }

    private fun createNewChat() {
        if (generationJob?.isActive == true) return
        activeChat = store.createConversation()
        pendingAttachments.clear()
        renderChat()
        lifecycleScope.launch {
            runCatching { loadModelForActiveChat() }
                .onFailure { statusText.text = "Не удалось подготовить новый чат" }
        }
    }

    private fun showHistory() {
        val chats = store.listConversations()
        if (chats.isEmpty()) {
            toast("История пока пуста")
            return
        }
        val labels = chats.map { chat ->
            val count = chat.messages.size
            chat.title + " • " + count + " сообщ."
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("История чатов")
            .setItems(labels) { _, which ->
                if (generationJob?.isActive == true) return@setItems
                activeChat = chats[which]
                pendingAttachments.clear()
                renderChat()
                lifecycleScope.launch {
                    runCatching { loadModelForActiveChat() }
                        .onFailure { statusText.text = "Не удалось открыть контекст чата" }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun sendMessage() {
        if (!modelReady || generationJob?.isActive == true) return
        val rawText = input.text.toString().trim()
        if (rawText.isBlank() && pendingAttachments.isEmpty()) return

        val text = rawText.ifBlank { "Отправлены вложения без текстового сообщения." }
        val attachments = pendingAttachments.toMutableList()
        val userMessage = ChatMessage(
            role = "user",
            text = text,
            attachments = attachments
        )
        activeChat.messages += userMessage
        if (activeChat.title == "Новый чат") {
            val firstLine = rawText.lineSequence().firstOrNull()?.trim()?.take(42)
            activeChat.title = if (!firstLine.isNullOrBlank()) {
                firstLine
            } else {
                attachments.firstOrNull()?.name?.take(42) ?: "Новый чат"
            }
        }
        store.save(activeChat)

        pendingAttachments.clear()
        input.setText("")
        renderChat()

        val attachmentHint = attachments.joinToString("\n") {
            "- " + it.name + " (" + it.mime + ")"
        }
        val prompt = buildString {
            append(text)
            if (attachmentHint.isNotBlank()) {
                append("\n\nВложения пользователя:\n")
                append(attachmentHint)
                append("\nНе утверждай, что прочитал содержимое файла, если оно не было передано текстом.")
            }
        }

        val streamingView = createBubble("PhoneAI", "")
        messagesContainer.addView(streamingView)
        scrollToBottom()

        generationJob = lifecycleScope.launch {
            sendButton.isEnabled = false
            addAttachmentButton.isEnabled = false
            statusText.text = "PhoneAI отвечает локально • " + SakhaLanguage.label(text)
            val buffer = StringBuilder()
            try {
                engine.sendUserPrompt(
                    prepareInferencePrompt(prompt),
                    predictLength = 512
                ).collect { piece ->
                    buffer.append(piece)
                    val visible = sanitizeAssistantText(buffer.toString(), final = false)
                    streamingView.text = "PhoneAI\n" + visible
                    scrollToBottom()
                }

                var answer = sanitizeAssistantText(buffer.toString(), final = true)
                if (answer.isBlank()) {
                    statusText.text = "Повторяю без режима размышления…"
                    streamingView.text = "PhoneAI\nФормирую короткий готовый ответ…"
                    val retryBuffer = StringBuilder()
                    engine.sendUserPrompt(
                        buildDirectRetryPrompt(text),
                        predictLength = DIRECT_RETRY_PREDICT_LENGTH
                    ).collect { piece ->
                        retryBuffer.append(piece)
                        val visible = sanitizeAssistantText(retryBuffer.toString(), final = false)
                        if (visible.isNotBlank()) {
                            streamingView.text = "PhoneAI\n" + visible
                            scrollToBottom()
                        }
                    }
                    answer = sanitizeAssistantText(retryBuffer.toString(), final = true)
                }
                if (answer.isBlank()) {
                    answer = "Не удалось получить финальный текст от локальной модели. Попробуйте ещё раз."
                }

                activeChat.messages += ChatMessage(role = "assistant", text = answer)
                store.save(activeChat)
                renderChat()
                statusText.text = "Готово • диалог сохранён"
            } catch (_: CancellationException) {
                statusText.text = "Ответ остановлен"
            } catch (e: Exception) {
                statusText.text = "Ошибка ответа: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                generationJob = null
                sendButton.isEnabled = modelReady
                addAttachmentButton.isEnabled = true
            }
        }
    }

    private fun prepareInferencePrompt(prompt: String): String {
        val routed = SakhaLanguage.routePrompt(prompt)
        val isQwen3 = currentModelFile?.name?.contains("qwen3", ignoreCase = true) == true
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
        return clean
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun renderChat() {
        titleText.text = activeChat.title
        renderPendingAttachments()
        messagesContainer.removeAllViews()

        if (activeChat.messages.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Новый локальный чат. История этого диалога хранится только на устройстве."
                setPadding(dp(14), dp(18), dp(14), dp(18))
            }
            messagesContainer.addView(empty)
        } else {
            activeChat.messages.forEach { message ->
                val author = if (message.role == "assistant") "PhoneAI" else "Вы"
                messagesContainer.addView(createBubble(author, message.text))
                if (message.attachments.isNotEmpty()) {
                    messagesContainer.addView(createAttachmentBlock(message))
                }
            }
        }
        scrollToBottom()
    }

    private fun createBubble(author: String, body: String): TextView {
        return TextView(this).apply {
            text = author + "\n" + body
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(6), 0, dp(6))
            layoutParams = params
            setBackgroundColor(getColor(R.color.panel))
        }
    }

    private fun createAttachmentBlock(message: ChatMessage): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            message.attachments.forEach { attachment ->
                val row = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val open = Button(this@ChatActivity).apply {
                    text = "📎 " + attachment.name
                    isAllCaps = false
                    setOnClickListener { openAttachment(attachment) }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val pin = Button(this@ChatActivity).apply {
                    text = if (attachment.pinned) "📌" else "☆"
                    setOnClickListener { togglePin(message.id, attachment.id) }
                    contentDescription = if (attachment.pinned) "Открепить" else "Закрепить"
                }
                row.addView(open)
                row.addView(pin)
                addView(row)
            }
        }
    }

    private fun togglePin(messageId: String, attachmentId: String) {
        val message = activeChat.messages.firstOrNull { it.id == messageId } ?: return
        val attachment = message.attachments.firstOrNull { it.id == attachmentId } ?: return
        attachment.pinned = !attachment.pinned
        store.save(activeChat)
        renderChat()
        statusText.text = if (attachment.pinned) "Вложение закреплено" else "Вложение откреплено"
    }

    private fun renderPendingAttachments() {
        pendingAttachmentsText.visibility = if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
        pendingAttachmentsText.text = if (pendingAttachments.isEmpty()) {
            ""
        } else {
            "К сообщению: " + pendingAttachments.joinToString(", ") { it.name }
        }
    }

    private fun openAttachment(attachment: ChatAttachment) {
        val file = File(attachment.localPath)
        if (!file.exists()) {
            toast("Файл вложения не найден")
            return
        }

        if (attachment.mime.startsWith("image/")) {
            showImageInsideApp(file, attachment.name)
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            toast("На телефоне нет приложения для открытия этого файла")
        }
    }

    private fun showImageInsideApp(file: File, title: String) {
        val bitmap = loadScaledBitmap(file, 1800)
        if (bitmap == null) {
            toast("Не удалось открыть изображение")
            return
        }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(image)
            .setPositiveButton("Закрыть", null)
            .setOnDismissListener { bitmap.recycleSafely() }
            .show()
    }

    private fun loadScaledBitmap(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private fun scrollToBottom() {
        messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val PREFS_NAME = "phoneai_core"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_LOCAL_MEMORY = "local_memory"
        private const val DIRECT_RETRY_PREDICT_LENGTH = 192

        private const val DEFAULT_SYSTEM_PROMPT =
            "Ты PhoneAI, локальный мобильный ИИ. Отвечай полезно, ясно и кратко. " +
                "Если пользователь пишет или говорит на якутском (саха) языке, отвечай на якутском (саха) и не переходи на русский без просьбы. " +
                "Сохраняй буквы ҕ, ҥ, ө, һ, ү. Если пользователь обращается по-русски, отвечай по-русски. " +
                "Не выдавай догадки за факты и сообщай, когда тебе не хватает данных."
    }
}
