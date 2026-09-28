package com.phoneai.core.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mime: String,
    val localPath: String,
    var pinned: Boolean = false
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    var text: String,
    val attachments: MutableList<ChatAttachment> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
)

data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Новый чат",
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

class ChatStore(private val context: Context) {
    private val chatsDir = File(context.filesDir, "chats").apply { mkdirs() }
    private val attachmentsRoot = File(context.filesDir, "chat_attachments").apply { mkdirs() }

    fun createConversation(): ChatConversation = ChatConversation().also(::save)

    fun listConversations(): List<ChatConversation> =
        chatsDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { runCatching { readConversation(it) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()

    fun load(id: String): ChatConversation? {
        val file = chatFile(id)
        return if (file.exists()) runCatching { readConversation(file) }.getOrNull() else null
    }

    @Synchronized
    fun save(chat: ChatConversation) {
        chat.updatedAt = System.currentTimeMillis()
        val target = chatFile(chat.id)
        val temp = File(chatsDir, ".${chat.id}.${UUID.randomUUID()}.tmp")
        val bytes = toJson(chat).toString().toByteArray(Charsets.UTF_8)

        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }

        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun importAttachment(chatId: String, uri: Uri): ChatAttachment {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri)
            ?: "attachment-${System.currentTimeMillis()}"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val dir = File(attachmentsRoot, chatId).apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}._() -]"), "_").take(120)
        val destination = File(dir, "${UUID.randomUUID()}-$safeName")

        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть вложение" }
            destination.outputStream().use { output -> input.copyTo(output) }
        }

        return ChatAttachment(
            name = displayName,
            mime = mime,
            localPath = destination.absolutePath
        )
    }

    fun deleteConversation(chat: ChatConversation) {
        chatFile(chat.id).delete()
        File(attachmentsRoot, chat.id).deleteRecursively()
    }

    private fun chatFile(id: String) = File(chatsDir, "$id.json")

    private fun readConversation(file: File): ChatConversation =
        fromJson(JSONObject(file.readText(Charsets.UTF_8)))

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }

    private fun toJson(chat: ChatConversation): JSONObject = JSONObject().apply {
        put("id", chat.id)
        put("title", chat.title)
        put("createdAt", chat.createdAt)
        put("updatedAt", chat.updatedAt)
        put("messages", JSONArray().apply {
            chat.messages.forEach { message ->
                put(JSONObject().apply {
                    put("id", message.id)
                    put("role", message.role)
                    put("text", message.text)
                    put("createdAt", message.createdAt)
                    put("attachments", JSONArray().apply {
                        message.attachments.forEach { attachment ->
                            put(JSONObject().apply {
                                put("id", attachment.id)
                                put("name", attachment.name)
                                put("mime", attachment.mime)
                                put("localPath", attachment.localPath)
                                put("pinned", attachment.pinned)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): ChatConversation {
        val messages = mutableListOf<ChatMessage>()
        val messageArray = json.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until messageArray.length()) {
            val item = messageArray.getJSONObject(i)
            val attachments = mutableListOf<ChatAttachment>()
            val attachmentArray = item.optJSONArray("attachments") ?: JSONArray()
            for (j in 0 until attachmentArray.length()) {
                val a = attachmentArray.getJSONObject(j)
                attachments += ChatAttachment(
                    id = a.optString("id", UUID.randomUUID().toString()),
                    name = a.optString("name", "Вложение"),
                    mime = a.optString("mime", "application/octet-stream"),
                    localPath = a.optString("localPath", ""),
                    pinned = a.optBoolean("pinned", false)
                )
            }
            messages += ChatMessage(
                id = item.optString("id", UUID.randomUUID().toString()),
                role = item.optString("role", "user"),
                text = item.optString("text", ""),
                attachments = attachments,
                createdAt = item.optLong("createdAt", System.currentTimeMillis())
            )
        }

        return ChatConversation(
            id = json.optString("id", UUID.randomUUID().toString()),
            title = json.optString("title", "Новый чат"),
            messages = messages,
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
