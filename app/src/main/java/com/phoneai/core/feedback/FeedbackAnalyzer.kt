package com.phoneai.core.feedback

import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Data-driven analysis of explicitly saved corrections.
 * It does not try to infer Sakha grammar rules. It only summarizes observed differences
 * between model output and user-reviewed corrections.
 */
internal class FeedbackAnalyzer(
    private val asrJsonl: File,
    private val llmJsonl: File,
) {
    data class Analysis(
        val asrExamples: Int,
        val llmExamples: Int,
        val hardAsrExamples: Int,
        val hardLlmExamples: Int,
        val topWordConfusions: List<Counted>,
        val topGraphemeConfusions: List<Counted>,
        val topEndingConfusions: List<Counted>,
        val topLlmChanges: List<Counted>,
    ) {
        fun toDisplayText(limit: Int = 5): String = buildString {
            appendLine("Сложные примеры: ASR $hardAsrExamples/$asrExamples • ответы $hardLlmExamples/$llmExamples")
            appendSection("Слова", topWordConfusions, limit)
            appendSection("Буквы/графемы", topGraphemeConfusions, limit)
            appendSection("Концовки слов", topEndingConfusions, limit)
            appendSection("Правки ответов", topLlmChanges, limit)
            if (asrExamples + llmExamples < 3) {
                append("\nДля устойчивой статистики сохраните больше исправлений.")
            }
        }

        private fun StringBuilder.appendSection(title: String, rows: List<Counted>, limit: Int) {
            append("\n$title: ")
            if (rows.isEmpty()) {
                append("пока нет повторяющихся данных")
            } else {
                append(rows.take(limit).joinToString(" • ") { "${it.label} ×${it.count}" })
            }
        }
    }

    data class Counted(val label: String, val count: Int)

    fun analyze(): Analysis {
        val wordCounts = linkedMapOf<String, Int>()
        val graphemeCounts = linkedMapOf<String, Int>()
        val endingCounts = linkedMapOf<String, Int>()
        val llmCounts = linkedMapOf<String, Int>()
        var asrExamples = 0
        var llmExamples = 0
        var hardAsr = 0
        var hardLlm = 0

        for (row in readRows(asrJsonl)) {
            val recognized = row.optString("recognized").trim()
            val corrected = row.optString("corrected").trim()
            if (corrected.isBlank()) continue
            asrExamples++
            val recognizedTokens = tokens(recognized)
            val correctedTokens = tokens(corrected)
            val wordAlignment = align(recognizedTokens, correctedTokens)
            val wordEdits = wordAlignment.count { it.first != it.second }
            if (wordEdits.toDouble() / max(1, correctedTokens.size) >= 0.25 ||
                normalizedCharDistance(recognized, corrected) >= 0.20
            ) {
                hardAsr++
            }

            for ((left, right) in wordAlignment) {
                if (left == right) continue
                val label = "${left ?: "∅"} → ${right ?: "∅"}"
                bump(wordCounts, label)
                if (left != null && right != null) {
                    val common = commonPrefixLength(left, right)
                    if (common >= 2 && common < max(left.length, right.length)) {
                        val oldEnd = left.substring(common).ifBlank { "∅" }.take(6)
                        val newEnd = right.substring(common).ifBlank { "∅" }.take(6)
                        bump(endingCounts, "$oldEnd → $newEnd")
                    }
                    for ((a, b) in align(left.toList(), right.toList())) {
                        if (a != b) bump(graphemeCounts, "${a ?: '∅'} → ${b ?: '∅'}")
                    }
                }
            }
        }

        for (row in readRows(llmJsonl)) {
            val model = row.optString("model_answer").trim()
            val corrected = row.optString("corrected_answer").trim()
            if (corrected.isBlank()) continue
            llmExamples++
            val before = tokens(model)
            val after = tokens(corrected)
            val alignment = align(before, after)
            val edits = alignment.count { it.first != it.second }
            if (edits.toDouble() / max(1, after.size) >= 0.25) hardLlm++
            for ((left, right) in alignment) {
                if (left == right) continue
                bump(llmCounts, "${left ?: "∅"} → ${right ?: "∅"}")
            }
        }

        return Analysis(
            asrExamples = asrExamples,
            llmExamples = llmExamples,
            hardAsrExamples = hardAsr,
            hardLlmExamples = hardLlm,
            topWordConfusions = top(wordCounts),
            topGraphemeConfusions = top(graphemeCounts),
            topEndingConfusions = top(endingCounts),
            topLlmChanges = top(llmCounts),
        )
    }

    private fun readRows(file: File): List<JSONObject> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.filter { it.isNotBlank() }.mapNotNull { line ->
                    runCatching { JSONObject(line) }.getOrNull()
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun tokens(text: String): List<String> = TOKEN_REGEX.findAll(text.lowercase())
        .map { it.value }
        .toList()

    private fun bump(map: MutableMap<String, Int>, key: String) {
        map[key] = (map[key] ?: 0) + 1
    }

    private fun top(map: Map<String, Int>): List<Counted> = map.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { Counted(it.key, it.value) }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun normalizedCharDistance(a: String, b: String): Double {
        val aa = a.lowercase().filterNot(Char::isWhitespace)
        val bb = b.lowercase().filterNot(Char::isWhitespace)
        if (aa.isEmpty() && bb.isEmpty()) return 0.0
        return editDistance(aa.toList(), bb.toList()).toDouble() / max(1, max(aa.length, bb.length))
    }

    private fun <T> editDistance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1)
            cur[0] = i
            for (j in 1..b.size) {
                val sub = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + sub)
            }
            prev = cur
        }
        return prev[b.size]
    }

    /** Levenshtein backtrace. null marks insertion/deletion. */
    private fun <T> align(a: List<T>, b: List<T>): List<Pair<T?, T?>> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) dp[i][0] = i
        for (j in 0..b.size) dp[0][j] = j
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                val sub = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + sub)
            }
        }
        val out = mutableListOf<Pair<T?, T?>>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1 -> {
                    out += a[i - 1] to b[j - 1]
                    i--; j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                    out += a[i - 1] to null
                    i--
                }
                else -> {
                    out += null to b[j - 1]
                    j--
                }
            }
        }
        out.reverse()
        return out
    }

    companion object {
        private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
    }
}
