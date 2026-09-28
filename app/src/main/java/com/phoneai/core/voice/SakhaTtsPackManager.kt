package com.phoneai.core.voice

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

object SakhaTtsPackManager {
    data class Pack(val dir: File, val model: File, val tokens: File)

    fun current(context: Context): Pack? {
        val dir = File(context.filesDir, "sakha_tts/current")
        return validate(dir)
    }

    fun importZip(context: Context, uri: Uri): Pack {
        val base = File(context.filesDir, "sakha_tts").apply { mkdirs() }
        val temp = File(base, "importing-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) { "Не удалось открыть TTS-пакет" }
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val clean = entry.name.replace('\\', '/').substringAfterLast('/')
                        if (clean !in ALLOWED_FILES) continue
                        val out = File(temp, clean)
                        var total = 0L
                        val limit = if (clean == "model.onnx") MAX_MODEL_BYTES else MAX_SMALL_FILE_BYTES
                        out.outputStream().buffered().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                total += read
                                require(total <= limit) { "Файл $clean слишком большой" }
                                output.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val pack = validate(temp) ?: error("В пакете нужны model.onnx и tokens.txt")
            val current = File(base, "current")
            val backup = File(base, "previous")
            if (backup.exists()) backup.deleteRecursively()
            if (current.exists() && !current.renameTo(backup)) current.deleteRecursively()
            if (!temp.renameTo(current)) {
                current.mkdirs()
                pack.model.copyTo(File(current, "model.onnx"), overwrite = true)
                pack.tokens.copyTo(File(current, "tokens.txt"), overwrite = true)
                temp.deleteRecursively()
            }
            return validate(current) ?: error("Не удалось установить TTS-пакет")
        } catch (t: Throwable) {
            temp.deleteRecursively()
            throw t
        }
    }

    private fun validate(dir: File): Pack? {
        val model = File(dir, "model.onnx")
        val tokens = File(dir, "tokens.txt")
        if (!model.isFile || model.length() < 1_000_000L) return null
        if (!tokens.isFile || tokens.length() < 10L) return null
        return Pack(dir, model, tokens)
    }

    private val ALLOWED_FILES = setOf("model.onnx", "tokens.txt", "LICENSE_MODEL.txt", "README.txt")
    private const val MAX_MODEL_BYTES = 400L * 1024L * 1024L
    private const val MAX_SMALL_FILE_BYTES = 2L * 1024L * 1024L
}
