package com.phoneai.core.voice

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

object WakeWordPackManager {
    private const val ROOT = "kws_pack"
    private const val MANIFEST = "phoneai_kws.json"
    private const val MAX_FILES = 24
    private const val MAX_TOTAL_BYTES = 96L * 1024L * 1024L

    data class Pack(
        val root: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
        val tokens: File,
        val keywords: File,
        val modelType: String,
        val sampleRate: Int,
        val numThreads: Int,
        val keywordsScore: Float,
        val keywordsThreshold: Float,
        val numTrailingBlanks: Int,
    )

    fun current(context: Context): Pack? = runCatching {
        readPack(File(context.filesDir, ROOT))
    }.getOrNull()

    fun remove(context: Context) {
        File(context.filesDir, ROOT).deleteRecursively()
    }

    fun importZip(context: Context, uri: Uri): Pack {
        val root = File(context.filesDir, ROOT)
        val staging = File(context.filesDir, "$ROOT.importing")
        staging.deleteRecursively()
        staging.mkdirs()

        var count = 0
        var total = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть KWS-пакет" }
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    count++
                    require(count <= MAX_FILES) { "Слишком много файлов в KWS-пакете" }

                    val safeName = entry.name.replace('\\', '/')
                    require(!safeName.startsWith("/") && !safeName.contains("../")) {
                        "Небезопасный путь в KWS-пакете"
                    }
                    val target = File(staging, safeName).canonicalFile
                    require(target.path.startsWith(staging.canonicalPath + File.separator)) {
                        "Небезопасный путь в KWS-пакете"
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            total += n
                            require(total <= MAX_TOTAL_BYTES) { "KWS-пакет слишком большой" }
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
        }

        val pack = readPack(staging)
        root.deleteRecursively()
        require(staging.renameTo(root)) { "Не удалось установить KWS-пакет" }
        return readPack(root)
    }

    private fun readPack(root: File): Pack {
        require(root.isDirectory) { "KWS-пакет не установлен" }
        val manifestFile = File(root, MANIFEST)
        require(manifestFile.isFile) { "В KWS-пакете нет $MANIFEST" }
        val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(json.optInt("version", 1) == 1) { "Неподдерживаемая версия KWS-пакета" }

        fun requiredPath(key: String): File {
            val rel = json.getString(key).replace('\\', '/')
            require(!rel.startsWith("/") && !rel.contains("../")) { "Небезопасный путь $key" }
            val file = File(root, rel).canonicalFile
            require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile && file.length() > 0L) {
                "KWS-файл $key не найден"
            }
            return file
        }

        return Pack(
            root = root,
            encoder = requiredPath("encoder"),
            decoder = requiredPath("decoder"),
            joiner = requiredPath("joiner"),
            tokens = requiredPath("tokens"),
            keywords = requiredPath("keywords"),
            modelType = json.optString("modelType", "zipformer2").ifBlank { "zipformer2" },
            sampleRate = json.optInt("sampleRate", 16000).coerceIn(8000, 48000),
            numThreads = json.optInt("numThreads", 1).coerceIn(1, 4),
            keywordsScore = json.optDouble("keywordsScore", 1.5).toFloat().coerceIn(0.1f, 10f),
            keywordsThreshold = json.optDouble("keywordsThreshold", 0.25).toFloat().coerceIn(0.01f, 0.99f),
            numTrailingBlanks = json.optInt("numTrailingBlanks", 2).coerceIn(0, 10),
        )
    }
}
