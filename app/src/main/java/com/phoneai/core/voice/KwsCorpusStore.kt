package com.phoneai.core.voice

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local-only collector for custom Sakha/PhoneAI wake-word training samples. */
class KwsCorpusStore(private val context: Context) {
    private val root = File(context.filesDir, "sakha_kws_corpus").apply { mkdirs() }
    private val audioDir = File(root, "audio").apply { mkdirs() }
    private val rowsFile = File(root, "samples.jsonl")

    enum class Label(val wire: String) {
        POSITIVE("positive"),
        NEGATIVE("negative"),
    }

    data class Summary(
        val positive: Int,
        val negative: Int,
        val speakers: Int,
        val audioBytes: Long,
    ) {
        val total: Int get() = positive + negative
    }

    enum class Readiness(val wire: String) {
        NOT_READY("not_ready"),
        PERSONAL_EXPERIMENT("personal_experiment"),
        MULTI_SPEAKER_EXPERIMENT("multi_speaker_experiment"),
    }

    data class SpeakerBreakdown(
        val speakerId: String,
        val positive: Int,
        val negative: Int,
    )

    data class AuditReport(
        val positive: Int,
        val negative: Int,
        val speakers: List<SpeakerBreakdown>,
        val targetKeywords: List<String>,
        val duplicateAudio: Int,
        val negativeKeywordLeakage: Int,
        val positiveTranscriptMismatch: Int,
        val durationOutliers: Int,
        val readiness: Readiness,
        val recommendations: List<String>,
    ) {
        val total: Int get() = positive + negative

        fun toJson(): JSONObject {
            val speakerArray = org.json.JSONArray()
            speakers.forEach { row ->
                speakerArray.put(
                    JSONObject()
                        .put("speaker_id", row.speakerId)
                        .put("positive", row.positive)
                        .put("negative", row.negative)
                )
            }
            val recs = org.json.JSONArray()
            recommendations.forEach { recs.put(it) }
            val targets = org.json.JSONArray()
            targetKeywords.forEach { targets.put(it) }
            return JSONObject()
                .put("format_version", 1)
                .put("positive", positive)
                .put("negative", negative)
                .put("total", total)
                .put("speaker_count", speakers.size)
                .put("speakers", speakerArray)
                .put("target_keywords", targets)
                .put("duplicate_audio", duplicateAudio)
                .put("negative_keyword_leakage", negativeKeywordLeakage)
                .put("positive_transcript_mismatch", positiveTranscriptMismatch)
                .put("duration_outliers", durationOutliers)
                .put("readiness", readiness.wire)
                .put("recommendations", recs)
                .put("note", "Heuristic collection gates only; final KWS quality must be measured on held-out audio.")
        }
    }

    fun saveSample(
        audio: FloatArray,
        sampleRate: Int,
        label: Label,
        targetKeyword: String,
        transcript: String,
        speakerId: String,
        consentConfirmed: Boolean,
    ) {
        require(consentConfirmed) { "Нужно подтвердить согласие говорящего" }
        require(sampleRate in 8000..48000) { "Некорректная частота дискретизации" }
        require(audio.isNotEmpty()) { "Аудио пустое" }
        val durationMs = audio.size * 1000L / sampleRate
        require(durationMs in MIN_DURATION_MS..MAX_DURATION_MS) {
            "Запись должна быть от ${MIN_DURATION_MS / 1000.0} до ${MAX_DURATION_MS / 1000.0} сек"
        }
        val keyword = targetKeyword.trim()
        require(keyword.isNotEmpty()) { "Введите слово активации" }
        val cleanTranscript = transcript.trim()
        require(cleanTranscript.isNotEmpty()) { "Для записи нужен текст произнесённой фразы" }
        val speaker = speakerId.trim().ifEmpty { "speaker_local" }.take(80)

        val id = "kws_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val wav = File(audioDir, "$id.wav")
        writeMono16Wav(wav, audio, sampleRate)

        val row = JSONObject()
            .put("id", id)
            .put("audio", "audio/${wav.name}")
            .put("label", label.wire)
            .put("target_keyword", keyword)
            .put("transcript", cleanTranscript)
            .put("speaker_id", speaker)
            .put("sample_rate", sampleRate)
            .put("duration_ms", durationMs)
            .put("language_hint", "sah")
            .put("consent_confirmed", true)
            .put("created_at", Instant.now().toString())
            .put("format_version", 1)
        rowsFile.appendText(row.toString() + "\n", Charsets.UTF_8)
    }

    fun summary(): Summary {
        var positive = 0
        var negative = 0
        val speakers = linkedSetOf<String>()
        if (rowsFile.isFile) {
            rowsFile.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { JSONObject(line) }.getOrNull()?.let { row ->
                    when (row.optString("label")) {
                        Label.POSITIVE.wire -> positive++
                        Label.NEGATIVE.wire -> negative++
                    }
                    row.optString("speaker_id").takeIf { it.isNotBlank() }?.let(speakers::add)
                }
            }
        }
        val bytes = audioDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        return Summary(positive, negative, speakers.size, bytes)
    }


    fun audit(): AuditReport {
        data class Row(
            val label: String,
            val transcript: String,
            val keyword: String,
            val speaker: String,
            val durationMs: Long,
            val audio: File?,
        )

        val rows = mutableListOf<Row>()
        if (rowsFile.isFile) {
            rowsFile.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val rel = json.optString("audio")
                val audio = rel.takeIf { it.startsWith("audio/") }
                    ?.let { File(root, it) }
                    ?.takeIf { it.isFile }
                rows += Row(
                    label = json.optString("label"),
                    transcript = json.optString("transcript"),
                    keyword = json.optString("target_keyword"),
                    speaker = json.optString("speaker_id").ifBlank { "speaker_unknown" },
                    durationMs = json.optLong("duration_ms", 0L),
                    audio = audio,
                )
            }
        }

        val positive = rows.count { it.label == Label.POSITIVE.wire }
        val negative = rows.count { it.label == Label.NEGATIVE.wire }
        val targets = rows.map { it.keyword.trim() }.filter { it.isNotBlank() }.distinct().sorted()

        val speakerMap = linkedMapOf<String, IntArray>()
        rows.forEach { row ->
            val counts = speakerMap.getOrPut(row.speaker) { intArrayOf(0, 0) }
            if (row.label == Label.POSITIVE.wire) counts[0]++
            if (row.label == Label.NEGATIVE.wire) counts[1]++
        }
        val speakers = speakerMap.map { (id, c) -> SpeakerBreakdown(id, c[0], c[1]) }
            .sortedBy { it.speakerId }

        var negativeLeakage = 0
        var positiveMismatch = 0
        var durationOutliers = 0
        rows.forEach { row ->
            val keyword = normalizePhrase(row.keyword)
            val transcript = normalizePhrase(row.transcript)
            if (row.label == Label.NEGATIVE.wire && keyword.isNotBlank() && transcript.contains(keyword)) {
                negativeLeakage++
            }
            if (row.label == Label.POSITIVE.wire && keyword.isNotBlank() && transcript != keyword) {
                positiveMismatch++
            }
            val recommended = if (row.label == Label.POSITIVE.wire) 350L..4_000L else 350L..8_000L
            if (row.durationMs !in recommended) durationOutliers++
        }

        val hashes = mutableMapOf<String, Int>()
        rows.mapNotNull { it.audio }.forEach { file ->
            val hash = sha256(file)
            hashes[hash] = (hashes[hash] ?: 0) + 1
        }
        val duplicateAudio = hashes.values.sumOf { (it - 1).coerceAtLeast(0) }

        val personalBlockers = mutableListOf<String>()
        if (targets.size != 1) personalBlockers += "Для одного KWS-пакета нужен один target keyword; найдено ${targets.size}."
        if (positive < 30) personalBlockers += "Для персонального эксперимента соберите минимум 30 положительных записей."
        if (negative < 60) personalBlockers += "Для персонального эксперимента соберите минимум 60 отрицательных записей."
        if (negativeLeakage > 0) personalBlockers += "В $negativeLeakage отрицательных транскрипциях встречается ключевая фраза."
        if (positiveMismatch > 0) personalBlockers += "$positiveMismatch положительных транскрипций не совпадают с target keyword."
        val duplicateLimit = maxOf(2, (rows.size * 0.10).toInt())
        if (duplicateAudio > duplicateLimit) personalBlockers += "Слишком много точных дублей аудио: $duplicateAudio."

        val personalReady = personalBlockers.isEmpty()
        val speakersWithCoverage = speakers.count { it.positive >= 10 && it.negative >= 10 }
        val multiReady = personalReady && positive >= 80 && negative >= 160 && speakers.size >= 3 && speakersWithCoverage >= 3

        val readiness = when {
            multiReady -> Readiness.MULTI_SPEAKER_EXPERIMENT
            personalReady -> Readiness.PERSONAL_EXPERIMENT
            else -> Readiness.NOT_READY
        }
        val recommendations = personalBlockers.toMutableList()
        if (personalReady && !multiReady) {
            if (positive < 80) recommendations += "Для мультиспикерного эксперимента доведите положительные записи примерно до 80+."
            if (negative < 160) recommendations += "Для мультиспикерного эксперимента доведите отрицательные записи примерно до 160+."
            if (speakers.size < 3) recommendations += "Добавьте минимум 3 говорящих с явным согласием для speaker-disjoint проверки."
            if (speakersWithCoverage < 3) recommendations += "Нужно хотя бы 3 говорящих с 10+ positive и 10+ negative у каждого."
        }
        if (durationOutliers > 0) recommendations += "Проверьте $durationOutliers записей с необычной длительностью; они не блокируют обучение, но могут ухудшить KWS."
        if (duplicateAudio > 0 && duplicateAudio <= duplicateLimit) recommendations += "Найдены точные дубли аудио: $duplicateAudio; лучше заменить их новыми произнесениями."
        if (multiReady) recommendations += "Корпус прошёл collection-gate. Следующий критерий — FAR/FRR на полностью удержанном test split."

        return AuditReport(
            positive = positive,
            negative = negative,
            speakers = speakers,
            targetKeywords = targets,
            duplicateAudio = duplicateAudio,
            negativeKeywordLeakage = negativeLeakage,
            positiveTranscriptMismatch = positiveMismatch,
            durationOutliers = durationOutliers,
            readiness = readiness,
            recommendations = recommendations,
        )
    }

    fun exportZip(destination: File): File {
        val summary = summary()
        require(summary.total > 0) { "KWS-корпус пуст" }
        destination.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            val manifest = JSONObject()
                .put("name", "PhoneAI Sakha KWS corpus")
                .put("format_version", 1)
                .put("created_at", Instant.now().toString())
                .put("positive_samples", summary.positive)
                .put("negative_samples", summary.negative)
                .put("speakers", summary.speakers)
                .put("privacy", "local-user-initiated-export")
                .put("note", "Samples are included only after explicit recording and consent confirmation.")
            putBytes(zip, "manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))
            putBytes(zip, "audit_report.json", audit().toJson().toString(2).toByteArray(Charsets.UTF_8))
            putFile(zip, rowsFile, "samples.jsonl")
            audioDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() == "wav" }
                ?.sortedBy { it.name }
                ?.forEach { putFile(zip, it, "audio/${it.name}") }
        }
        return destination
    }

    fun clear() {
        root.deleteRecursively()
        root.mkdirs()
        audioDir.mkdirs()
    }


    private fun normalizePhrase(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

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
        header.putShort(1.toShort())
        header.putShort(1.toShort())
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

    companion object {
        private const val MIN_DURATION_MS = 250L
        private const val MAX_DURATION_MS = 12_000L
    }
}
