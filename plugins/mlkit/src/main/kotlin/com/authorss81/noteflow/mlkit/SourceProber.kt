package com.authorss81.noteflow.mlkit

/**
 * Pure JVM — a tiny, dependency-free source-language prober used by
 * [TranslationCatalog.detectSourceLanguage].
 *
 * Phase-175 replacement for the app-side Lingua detector: a downloadable plugin
 * compiles only against `plugin-sdk`, so it cannot reach the app's
 * `LanguageDetectionCore`. Script surfaces are unambiguous for the CJK + Arabic/
 * Hebrew + Thai + Cyrillic + Greek group; European languages fall back to a few
 * stopword heuristics and otherwise default to English. Honest by design: an
 * ambiguous snippet translates from [fallback], never a wrong guess.
 */
object SourceProber {

    /** Detect the ISO-639-1 ML Kit-compatible code of [text], or "en". */
    fun detect(text: String): String {
        val sample = text.trim()
        if (sample.isEmpty()) return "en"
        val cjk = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\u3100-\\u312F\\u31A0-\\u31BF]")
        val hiraganaKatakana = Regex("[\\u3040-\\u30FF]")
        val han = Regex("[\\u4E00-\\u9FFF]")
        val hangul = Regex("[\\uAC00-\\uD7AF]")
        val thai = Regex("[\\u0E00-\\u0E7F]")
        val arabic = Regex("[\\u0600-\\u06FF]")
        val hebrew = Regex("[\\u0590-\\u05FF]")
        val cyrillic = Regex("[\\u0400-\\u04FF]")
        val greek = Regex("[\\u0370-\\u03FF]")
        val devanagari = Regex("[\\u0900-\\u097F]")
        val latin = Regex("[A-Za-z]")

        return when {
            hangul.containsMatchIn(sample) -> "ko"
            han.containsMatchIn(sample) && !hiraganaKatakana.containsMatchIn(sample) -> "zh"
            hiraganaKatakana.containsMatchIn(sample) || cjk.containsMatchIn(sample) && !han.containsMatchIn(sample) -> "ja"
            thai.containsMatchIn(sample) -> "th"
            arabic.containsMatchIn(sample) -> "ar"
            hebrew.containsMatchIn(sample) -> "he"
            cyrillic.containsMatchIn(sample) -> "ru"
            greek.containsMatchIn(sample) -> "el"
            devanagari.containsMatchIn(sample) -> "hi"
            !latin.containsMatchIn(sample) -> "en"
            else -> probeLatin(sample.lowercase())
        }
    }

    private fun probeLatin(sample: String): String {
        val words = sample.split(WHITESPACE).filter { it.isNotEmpty() }
        val counts = mutableMapOf<String, Int>()
        ES.common.forEach { w -> if (words.contains(w)) counts["es"] = counts.getOrDefault("es", 0) + 1 }
        FR.common.forEach { w -> if (words.contains(w)) counts["fr"] = counts.getOrDefault("fr", 0) + 1 }
        DE.common.forEach { w -> if (words.contains(w)) counts["de"] = counts.getOrDefault("de", 0) + 1 }
        IT.common.forEach { w -> if (words.contains(w)) counts["it"] = counts.getOrDefault("it", 0) + 1 }
        PT.common.forEach { w -> if (words.contains(w)) counts["pt"] = counts.getOrDefault("pt", 0) + 1 }
        NL.common.forEach { w -> if (words.contains(w)) counts["nl"] = counts.getOrDefault("nl", 0) + 1 }
        return counts.maxByOrNull { it.value }?.key ?: "en"
    }

    private val WHITESPACE = Regex("\\s+")

    private object ES { val common = listOf("el", "la", "los", "las", "de", "que", "y", "en", "es", "por") }
    private object FR { val common = listOf("le", "la", "les", "de", "des", "et", "en", "est", "pour") }
    private object DE { val common = listOf("der", "die", "das", "und", "den", "mit", "von", "ist", "für", "ein") }
    private object IT { val common = listOf("il", "lo", "la", "del", "della", "e", "è", "per", "che") }
    private object PT { val common = listOf("o", "a", "os", "as", "de", "que", "e", "em", "por", "para") }
    private object NL { val common = listOf("de", "het", "een", "en", "van", "met", "is", "voor", "dat") }
}