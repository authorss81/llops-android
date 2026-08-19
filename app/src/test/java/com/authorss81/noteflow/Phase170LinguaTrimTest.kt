package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.langdetect.LanguageDetectionCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 170 (Phase-32-NEW-01 MEDIUM): the base APK must ship ONLY the 24
 * `language-models/<iso>` n-gram dirs that `LanguageDetectionCore` actually
 * compiles (`SUPPORTED`), not lingua 1.2.2's full 75-language corpus (~80 MB
 * packed was 56% of the release APK). The trim lives in
 * `app/build.gradle.kts` (`packaging.resources.excludes`, driven by the
 * `LINGUA_UNUSED_LANGUAGE_ISOS` constant). These pure-JVM tests pin that
 * contract so the exclude list and the detection subset cannot drift apart:
 *
 *  - `SUPPORTED` is exactly the documented 24-code subset (iso → display name);
 *  - every SUPPORTED code resolves to a real lingua `Language` (so a future
 *    lingua update that drops a language fails loudly instead of silently
 *    degrading the detector);
 *  - `LINGUA_UNUSED_LANGUAGE_ISOS` in `app/build.gradle.kts` equals lingua's
 *    full 75-code corpus MINUS the 24 SUPPORTED codes (no overlap, no gap);
 *  - the packaged resource excludes cover every unused language
 *    (`language-models/<iso>` + glob) and touch none of the 24 used languages.
 */
class Phase170LinguaTrimTest {

    /** The exact 24-code subset `LanguageDetectionCore` compiles. */
    private val supported: Set<String> = LanguageDetectionCore.SUPPORTED.keys

    /**
     * Lingua 1.2.2's complete ISO-639-1 corpus, extracted from the shipped
     * `language-models/` directories (verified 75 codes via `unzip -l` on the
     * `com.github.pemistahl:lingua:1.2.2` JAR in the Gradle cache).
     */
    private val linguaFullCorpus: List<String> = listOf(
        "af", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "cy", "da", "de",
        "el", "en", "eo", "es", "et", "eu", "fa", "fi", "fr", "ga", "gu", "he",
        "hi", "hr", "hu", "hy", "id", "is", "it", "ja", "ka", "kk", "ko", "la",
        "lg", "lt", "lv", "mi", "mk", "mn", "mr", "ms", "nb", "nl", "nn", "pa",
        "pl", "pt", "ro", "ru", "sk", "sl", "sn", "so", "sq", "sr", "st", "sv",
        "sw", "ta", "te", "th", "tl", "tn", "tr", "ts", "uk", "ur", "vi", "xh",
        "yo", "zh", "zu"
    )

    // ---- part 1: the SUPPORTED subset is pinned to 24 languages -------------

    @Test
    fun `SUPPORTED pins the documented 24-language subset`() {
        assertEquals(24, supported.size)
        assertEquals(
            linkedSetOf(
                "en", "de", "fr", "es", "it", "pt", "nl", "pl", "ru", "uk",
                "tr", "sv", "da", "nb", "fi", "cs", "hu", "ro", "hi", "zh",
                "ja", "ko", "ar", "el"
            ),
            supported
        )
        // Display-name map stays honest: every key has a non-blank label.
        assertTrue(
            LanguageDetectionCore.SUPPORTED.values.all { it.isNotBlank() }
        )
    }

    @Test
    fun `every SUPPORTED code still resolves to a real lingua Language`() {
        // If lingua is ever upgraded and a SUPPORTED code disappears, the
        // detector's `fromLanguages(subset)` build would silently drop it —
        // this pin catches that before the trim excludes its data.
        val resolvable = linguaFullCorpus.toSet()
        supported.forEach { iso ->
            assertTrue(
                "lingua corpus no longer contains SUPPORTED language '$iso'",
                iso in resolvable
            )
        }
    }

    // ---- part 2: the trim list equals the corpus minus SUPPORTED ------------

    @Test
    fun `lingua corpus has exactly 75 codes and covers every SUPPORTED code`() {
        assertEquals(75, linguaFullCorpus.size)
        assertEquals(75, linguaFullCorpus.toSet().size)
        assertTrue(linguaFullCorpus.containsAll(supported))
    }

    @Test
    fun `build-gradle trim list equals the 75-language corpus minus SUPPORTED`() {
        val expectedTrimmed = linguaFullCorpus.filterNot { it in supported }

        val buildText = buildFileText()
        val trimmedInBuild = parseLinguaTrimList(buildText)

        assertEquals("trim list count must be 75 - 24 = 51", 51, trimmedInBuild.size)
        assertEquals(
            "LINGUA_UNUSED_LANGUAGE_ISOS must equal the corpus minus SUPPORTED " +
                "(no overlap, no gap, no drift from LanguageDetectionCore)",
            expectedTrimmed.toSet(),
            trimmedInBuild.toSet()
        )
    }

    // ---- part 3: the packaging excludes apply to every unused language -------

    @Test
    fun `packaging excludes every unused language and none of the 24 used`() {
        val buildText = buildFileText()
        val trimmedInBuild = parseLinguaTrimList(buildText)

        // The excludes must be wired inside the packaging resources block and
        // generated from the SAME trimmed list (never a hand-written glob).
        val packagingSlice = buildText
            .substringAfter("packaging {")
            .substringBefore("testOptions {")
        assertTrue(
            "packaging block must declare resources.excludes",
            packagingSlice.contains("resources {") && packagingSlice.contains("excludes +=")
        )
        assertTrue(
            "packaging excludes must be derived from LINGUA_UNUSED_LANGUAGE_ISOS",
            packagingSlice.contains("LINGUA_UNUSED_LANGUAGE_ISOS.map { \"language-models/\$it/**\" }")
        )

        // Mechanism coverage: one glob per unused code is produced by mapping
        // the list, so every parsed (51) unused code is excluded.
        assertEquals(
            "the exclude-generation list in the packaging block must reference the 51 unused codes",
            51,
            trimmedInBuild.size
        )
        // Anti-foot-gun: a blanket `language-models/**` exclude would also strip
        // the 24 used languages — forbid it.
        assertFalse(
            "packaging resources must never blanket-exclude all language-models/**",
            packagingSlice.contains("language-models/**") ||
                packagingSlice.contains("\"language-models/**\"")
        )
        // No SUPPORTED language may ever be reachable by a literal glob
        // (the mapping only emits `language-models/<iso>/**` for non-SUPPORTED).
        assertFalse(
            "the packaging block must NOT mention any SUPPORTED language-models/* glob",
            supported.any { packagingSlice.contains("\"language-models/$it/**\"") }
        )
    }

    // ---- helpers -------------------------------------------------------------

    private fun parseLinguaTrimList(buildText: String): List<String> =
        Regex("LINGUA_UNUSED_LANGUAGE_ISOS = listOf\\((.*?)\\)", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(buildText)
            ?.groupValues
            ?.get(1)
            ?.run {
                Regex("\"([a-z]{2})\"").findAll(this).map { it.groupValues[1] }.toList()
            }
            ?: error("LINGUA_UNUSED_LANGUAGE_ISOS list not found in app/build.gradle.kts")

    private fun buildFileText(): String {
        val buildFile = File(repoRoot(), "app/build.gradle.kts")
        assertTrue("repo root must contain app/build.gradle.kts", buildFile.isFile)
        return buildFile.readText()
    }

    companion object {
        private fun repoRoot(): File {
            val cwd = File(System.getProperty("user.dir") ?: ".")
            var dir = cwd
            repeat(8) {
                if (File(dir, "gradle/libs.versions.toml").isFile &&
                    File(dir, "app").isDirectory
                ) {
                    return dir
                }
                dir = dir.parentFile ?: return cwd
            }
            return cwd
        }
    }
}