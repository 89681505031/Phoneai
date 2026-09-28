package com.phoneai.core.voice

import java.text.Normalizer
import java.util.Locale

/** Local Sakha/Yakut text normalizer, conservative detector and routing helper. */
object SakhaLanguage {
    enum class LanguageClass { SAKHA, MIXED, OTHER }

    private const val SPECIAL = "ҕҥөһүҔҤӨҺҮ"
    private val strongWords = setOf(
        "саха", "сахалыы", "уонна", "буол", "буолар", "буолбут", "киһи", "дьиэ",
        "мин", "эн", "биһиги", "эһиги", "ол", "бу", "ханнык", "туох", "хайдах",
        "кэл", "бар", "көр", "бил", "үөрэн", "тыл", "тылынан", "махтал", "эрэ",
        "диэн", "эбит", "буоллаҕына", "бэйэ", "күн", "сир", "дойду", "кэпсээ"
    )
    private val sakhaSuffixes = listOf(
        "тар", "тэр", "лар", "лэр", "дар", "дэр", "нан", "нэн", "ынан", "инэн",
        "тааҕы", "тээҕи", "лыы", "лии", "буол", "быт", "бит", "ҕа", "ҕэ"
    )

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t\\r\\n ]+"), " ")
            .trim()

    fun score(text: String): Int {
        val normalized = normalize(text)
        if (normalized.isBlank()) return 0
        var score = normalized.count { it in SPECIAL } * 3
        val words = words(normalized)
        score += words.count { it in strongWords } * 2
        score += words.count { word -> sakhaSuffixes.any { suffix -> word.length > suffix.length + 2 && word.endsWith(suffix) } }
        return score
    }

    fun confidence(text: String): Float {
        val ws = words(text)
        if (ws.isEmpty()) return 0f
        val specialWordCount = ws.count { word -> word.any { it in SPECIAL } }
        val lexiconHits = ws.count { it in strongWords }
        val suffixHits = ws.count { word -> sakhaSuffixes.any { suffix -> word.length > suffix.length + 2 && word.endsWith(suffix) } }
        return ((specialWordCount * 1.6f + lexiconHits * 1.2f + suffixHits * 0.5f) / ws.size)
            .coerceIn(0f, 1f)
    }

    fun classify(text: String): LanguageClass {
        val ws = words(text)
        if (ws.isEmpty()) return LanguageClass.OTHER
        val specialWords = ws.count { word -> word.any { it in SPECIAL } }
        val strongHits = ws.count { it in strongWords }
        val suffixHits = ws.count { word -> sakhaSuffixes.any { suffix -> word.length > suffix.length + 2 && word.endsWith(suffix) } }
        val sakhaLike = ws.count { word ->
            word.any { it in SPECIAL } || word in strongWords || sakhaSuffixes.any { suffix -> word.length > suffix.length + 2 && word.endsWith(suffix) }
        }
        val ratio = sakhaLike.toFloat() / ws.size
        val strongSignal = specialWords >= 1 || strongHits >= 2 || (strongHits >= 1 && suffixHits >= 1)

        return when {
            strongSignal && (ratio >= 0.55f || ws.size <= 2) -> LanguageClass.SAKHA
            strongSignal || (sakhaLike >= 2 && ratio >= 0.25f) -> LanguageClass.MIXED
            score(text) >= 5 && confidence(text) >= 0.35f -> LanguageClass.MIXED
            else -> LanguageClass.OTHER
        }
    }

    fun looksLikeSakha(text: String): Boolean = classify(text) != LanguageClass.OTHER

    fun looksMixedRussianSakha(text: String): Boolean = classify(text) == LanguageClass.MIXED

    /** Hidden routing hint for small local LLMs. It does not translate or modify the user's words. */
    fun routePrompt(text: String): String = when (classify(text)) {
        LanguageClass.SAKHA ->
            "[Языковая маршрутизация PhoneAI: основной язык запроса — саха тыла. Ответь естественно на саха тыла; сохрани ҕ, ҥ, ө, һ, ү; не переходи на русский без просьбы.]\n\n$text"
        LanguageClass.MIXED ->
            "[Языковая маршрутизация PhoneAI: запрос смешанный русский/саха. Пойми обе части, не переводи имена и термины без необходимости и отвечай на языке основной просьбы.]\n\n$text"
        LanguageClass.OTHER -> text
    }

    fun label(text: String): String = when (classify(text)) {
        LanguageClass.SAKHA -> "саха"
        LanguageClass.MIXED -> "русский + саха"
        LanguageClass.OTHER -> "другой/русский"
    }

    private fun words(text: String): List<String> =
        normalize(text).lowercase(Locale.ROOT)
            .split(Regex("[^а-яёҕҥөһү]+"))
            .filter { it.isNotBlank() }
}
