package com.authorss81.noteflow.plugins.translation

import com.authorss81.noteflow.plugins.TranslationLanguage
import com.authorss81.noteflow.plugins.langdetect.LanguageDetectionCore

/**
 * PURE JVM — the on-device translator's language catalogue + source
 * auto-detection. No Android dependencies, fully unit-tested.
 *
 * [TARGETS] is a curated subset of the languages ML Kit's `translate`
 * library can handle (the full set is ~50; this keeps the UI honest about what
 * actually works offline on-device without an API key).
 */
object TranslationCatalog {

    /** mlkit-translate language code → display name. */
    val TARGETS: Map<String, String> = linkedMapOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "zh" to "Chinese (Simplified)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "nl" to "Dutch",
        "tr" to "Turkish",
        "pl" to "Polish",
        "uk" to "Ukrainian",
        "sv" to "Swedish",
        "fa" to "Persian",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "he" to "Hebrew",
        "th" to "Thai",
        "cs" to "Czech",
        "el" to "Greek",
        "da" to "Danish",
        "fi" to "Finnish",
        "hu" to "Hungarian",
        "ro" to "Romanian"
    )

    /** The target languages offered by the UI (Code → [TranslationLanguage]). */
    fun supportedTargetLanguages(): List<TranslationLanguage> =
        TARGETS.map { (code, name) -> TranslationLanguage(code, name) }

    /** True when [code] is a target language this plugin can translate into. */
    fun isSupportedTarget(code: String): Boolean = normalize(code) in TARGETS

    /** Normalize a possibly-dialect code (e.g. Lia opens `nb` for Bokmål). */
    fun normalize(code: String): String = when (code.trim().lowercase()) {
        "nb" -> "no"
        "zh-cn" -> "zh"
        "zh-tw" -> "zh"
        "pt-br" -> "pt"
        else -> code.trim().lowercase()
    }

    /**
     * Auto-detect the source language of [text] using the existing pure Lingua
     * detector (Phase 15). Returns an ML Kit-compatible code, defaulting to
     * [fallback] when detection is inconclusive or unsupported — Translate then
     * still works, just starting from the fallback.
     */
    fun detectSourceLanguage(text: String, fallback: String = "en"): String {
        if (text.isBlank()) return fallback
        return when (val outcome = LanguageDetectionCore.detectLanguage(text)) {
            is com.authorss81.noteflow.plugins.LanguageDetectionOutcome.Success -> {
                val iso = normalize(outcome.language.isoCode)
                // Lingua may return a code ML Kit doesn't ship (e.g. he/fa are
                // ML Kit-supported, but el/cs are; everything else filtered).
                if (translateSupportedSource(iso)) iso else fallback
            }
            else -> fallback
        }
    }

    private val VALID_SOURCES: Set<String> = setOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo",
        "es", "et", "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "hu", "hy",
        "id", "is", "it", "ja", "ka", "kk", "km", "ko", "lt", "lv", "mk", "ms", "mt",
        "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sr", "sv", "sw", "ta",
        "te", "th", "tr", "uk", "ur", "vi", "zh"
    )

    private fun translateSupportedSource(code: String): Boolean = code in VALID_SOURCES
}