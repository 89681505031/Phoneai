package com.phoneai.core.feedback

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local-only correction store for PhoneAI.
 * Nothing is uploaded automatically. Export happens only after an explicit user action.
 */
class FeedbackStore(private val context: Context) {
    private val root = File(context.filesDir, "sakha_feedback").apply { mkdirs() }
    private val audioDir = File(root, "audio").apply { mkdirs() }
    private val asrJsonl = File(root, "asr_feedback.jsonl")
    private val llmJsonl = File(root, "llm_feedback.jsonl")

    data class Summary(
        val asrCount: Int,
        val llmCount: Int,
        val audioBytes: Long,
    )

    fun saveAsrCorrection(
        audio: FloatArray,
        recognized: String,
        corrected: String,
        sampleRate: Int,
        source: String = "manual-voice",
    ) {
        require(audio.isNotEmpty()) { "Audio is empty" }
        val cleanCorrected = corrected.trim()
        require(cleanCorrected.isNotEmpty()) { "Corrected transcript is empty" }

        val id = "asr_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val wav = File(audioDir, "$id.wav")
        writeMono16Wav(wav, audio, sampleRate)

        val row = JSONObject()
            .put("id", id)
            .put("audio", "audio/${wav.name}")
            .put("sample_rate", sampleRate)
            .put("recognized", recognized.trim())
            .put("corrected", cleanCorrected)
            .put("language_hint", "sah")
            .put("source", source)
            .put("created_at", Instant.now().toString())
            .put("format_version", 1)
        asrJsonl.appendText(row.toString() + "\n", Charsets.UTF_8)
    }

    fun saveLlmCorrection(
        prompt: String,
        modelAnswer: String,
        correctedAnswer: String,
        systemPrompt: String,
        source: String = "phoneai-correction",
    ) {
        val cleanPrompt = prompt.trim()
        val cleanCorrected = correctedAnswer.trim()
        require(cleanPrompt.isNotEmpty()) { "Prompt is empty" }
        require(cleanCorrected.isNotEmpty()) { "Corrected answer is empty" }

        val row = JSONObject()
            .put("id", "llm_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
            .put("prompt", cleanPrompt)
            .put("model_answer", modelAnswer.trim())
            .put("corrected_answer", cleanCorrected)
            .put("system_prompt", systemPrompt.trim())
            .put("language_hint", "sah")
            .put("source", source)
            .put("created_at", Instant.now().toString())
            .put("format_version", 1)
        llmJsonl.appendText(row.toString() + "\n", Charsets.UTF_8)
    }

    fun summary(): Summary = Summary(
        asrCount = countRows(asrJsonl),
        llmCount = countRows(llmJsonl),
        audioBytes = audioDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L,
    )

    internal fun analyzeErrors(): FeedbackAnalyzer.Analysis = FeedbackAnalyzer(asrJsonl, llmJsonl).analyze()

    fun exportZip(destination: File): File {
        val s = summary()
        require(s.asrCount + s.llmCount > 0) { "Feedback is empty" }
        destination.parentFile?.mkdirs()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            val manifest = JSONObject()
                .put("name", "PhoneAI Sakha feedback bundle")
                .put("format_version", 1)
                .put("created_at", Instant.now().toString())
                .put("asr_examples", s.asrCount)
                .put("llm_examples", s.llmCount)
                .put("privacy", "local-user-initiated-export")
                .put("note", "Audio is included only when the user explicitly saved an ASR correction.")
            putBytes(zip, "manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))

            if (asrJsonl.isFile) putFile(zip, asrJsonl, "asr_feedback.jsonl")
            if (llmJsonl.isFile) putFile(zip, llmJsonl, "llm_feedback.jsonl")
            audioDir.listFiles()?.filter { it.isFile && it.extension.lowercase() == "wav" }?.sortedBy { it.name }?.forEach {
                putFile(zip, it, "audio/${it.name}")
            }
        }
        return destination
    }

    fun clear() {
        root.deleteRecursively()
        root.mkdirs()
        audioDir.mkdirs()
    }

    private fun countRows(file: File): Int = if (!file.isFile) 0 else
        runCatching { file.useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0)

    private fun putFile(zip: ZipOutputStream, file: File, path: String) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeMono16Wav(file: File, audio: FloatArray, sampleRate: Int) {
        val dataSize = audio.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(1.toShort()) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        FileOutputStream(file).use { out ->
            out.write(header.array())
            val chunk = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in audio) {
                val pcm = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                if (chunk.remaining() < 2) {
                    out.write(chunk.array(), 0, chunk.position())
                    chunk.clear()
                }
                chunk.putShort(pcm)
            }
            if (chunk.position() > 0) out.write(chunk.array(), 0, chunk.position())
        }
    }
}
