package com.authorss81.noteflow.plugins.langdetect

import com.authorss81.noteflow.plugins.DetectedLanguage
import com.authorss81.noteflow.plugins.LanguageDetectionOutcome
import com.github.pemistahl.lingua.api.IsoCode639_1
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder

/**
 * Pure-JVM language detection + auto-tagging (Phase 15, capability
 * `LanguageDetection`), backed by **Lingua** (Apache-2.0, no native code).
 *
 * Memory-conscious by design: only a bounded subset of the 75 built-in
 * languages is compiled in (`fromLanguages(...)`) and the detector runs in
 * **low-accuracy mode** (`withLowAccuracyMode()`), which is also what allows
 * short note snippets to be detected. All of this is unit-testable on the JVM.
 *
 * The language tag convention is `lang:<iso>` (e.g. `lang:en`); `lang:*` and
 * `language:*` tags count as a user override that [autoTagLanguage] never
 * overwrites.
 */
object LanguageDetectionCore {

    /** iso-639-1 code → display name. This bounded subset keeps memory sane. */
    val SUPPORTED: Map<String, String> = linkedMapOf(
        "en" to "English",
        "de" to "German",
        "fr" to "French",
        "es" to "Spanish",
        "it" to "Italian",
        "pt" to "Portuguese",
        "nl" to "Dutch",
        "pl" to "Polish",
        "ru" to "Russian",
        "uk" to "Ukrainian",
        "tr" to "Turkish",
        "sv" to "Swedish",
        "da" to "Danish",
        "nb" to "Norwegian (Bokmål)",
        "fi" to "Finnish",
        "cs" to "Czech",
        "hu" to "Hungarian",
        "ro" to "Romanian",
        "hi" to "Hindi",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "el" to "Greek"
    )

    private val subset: Set<Language> = SUPPORTED.keys.mapNotNull { iso ->
        runCatching { Language.getByIsoCode639_1(IsoCode639_1.valueOf(iso.uppercase())) }.getOrNull()
    }.toSet()

    private val detector: com.github.pemistahl.lingua.api.LanguageDetector by lazy {
        LanguageDetectorBuilder
            .fromLanguages(*subset.toTypedArray())
            .withLowAccuracyMode()
            .build()
    }

    private const val MIN_CHARS_FOR_DETECTION = 20

    /** Detect the dominant language of [text]. Never throws. */
    fun detectLanguage(text: String): LanguageDetectionOutcome {
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS_FOR_DETECTION) {
            return LanguageDetectionOutcome.NoMatch(
                "Text is too short to detect a language reliably (need ~$MIN_CHARS_FOR_DETECTION chars)."
            )
        }
        return try {
            val best = detector.computeLanguageConfidenceValues(trimmed).maxByOrNull { it.value }
                ?: return LanguageDetectionOutcome.NoMatch("Language could not be determined.")
            val iso = best.key.isoCode639_1.name.lowercase()
            val display = SUPPORTED[iso] ?: best.key.name.lowercase().replaceFirstChar { it.uppercase() }
            val confidence = best.value.coerceIn(0.0, 1.0)
            if (confidence <= 0.0) {
                return LanguageDetectionOutcome.NoMatch("Language could not be determined.")
            }
            LanguageDetectionOutcome.Success(
                DetectedLanguage(
                    isoCode = iso,
                    displayName = display,
                    confidence = confidence
                )
            )
        } catch (e: Exception) {
            LanguageDetectionOutcome.Error("Language detection failed (${e::class.java.simpleName}).")
        }
    }

    /**
     * Merge a freshly detected `lang:<iso>` tag into [existingTags] (a
     * comma-separated tag string), honouring a user override: any existing
     * `lang:*` / `language:*` tag is left untouched. Returns the original tags
     * unchanged when nothing can be detected. Pure and unit-tested.
     */
    fun autoTagLanguage(text: String, existingTags: String): String {
        val tags = existingTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.any { isLanguageTag(it) }) return existingTags
        val outcome = detectLanguage(text)
        if (outcome !is LanguageDetectionOutcome.Success) return existingTags
        val detected = outcome.language.isoCode
        return (tags + "lang:$detected").distinct().joinToString(",")
    }

    /** True when [tag] is a language tag this plugin manages. */
    fun isLanguageTag(tag: String): Boolean {
        val t = tag.trim().lowercase()
        return t.startsWith("lang:") || t.startsWith("language:")
    }
}