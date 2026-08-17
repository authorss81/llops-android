package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.webcapture.WebToMarkdownExtractor
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B2-DEPS-01 (phase-97): the app's only jsoup dependency must sit on the
 * security-fixed line. CVE-2026-71497 / GHSA-pmhh-3w7g-xqp8 affects
 * `org.jsoup:jsoup >= 1.14.3, < 1.23.1` — a tag name ending in a control
 * character was normalized with `trim()` by the tokenizer and could take on the
 * parsing behavior of a different element (e.g. `<script<ESC>>` collapsed to
 * `<script>`, a RAW-TEXT element), so inert text was emitted back as active
 * markup after serialization by a cleaner with a custom `Safelist` permitting
 * raw-text elements. Fixed in 1.23.1 ("Preserve control characters in parsed
 * tag names", jsoup#2538).
 *
 * The app does not currently call `Jsoup.clean`/`Cleaner`/`Safelist` (verified
 * by a repo-wide scan, so the vulnerable path is not exercised today) — the fix
 * is the version upgrade plus the guarded state below. These pure-JVM tests
 * (1) source-pin the catalog + verification-metadata to the fixed line so the
 * advisory surface can never silently regress to a vulnerable pin, and
 * (2) prove the fixed parser keeps the payload's content as inert in-flow text
 * instead of promoting it to raw-text script markup — the exact behavior the
 * advisory describes — on the real bundled jar and through the app's only jsoup
 * consumer ([WebToMarkdownExtractor]).
 */
class B2Deps01JsoupCveTest {

    // ---- pin: the catalog + verification metadata cannot be below the fixed line ----

    @Test
    fun `catalog pins jsoup to the CVE-fixed version`() {
        val text = File(repoRoot(), "gradle/libs.versions.toml").readText()
        val versionLine = text.lines().firstOrNull { it.trimStart().startsWith("jsoup =") }
            ?: error("jsoup version reference not found in gradle/libs.versions.toml")
        assertTrue(
            "jsoup must be pinned to >= $FIXED_JSOUP_VERSION (CVE-2026-71497 fixed in $FIXED_JSOUP_VERSION); " +
                "found '$versionLine'",
            versionLine.contains("jsoup = \"$FIXED_JSOUP_VERSION\"")
        )
        val libDecl = text.lines().firstOrNull { it.contains("jsoup = { group = \"org.jsoup\"") }
            ?: error("org.jsoup library declaration not found in gradle/libs.versions.toml")
        assertTrue(
            "the catalog library must declare org.jsoup:jsoup",
            libDecl.contains("name = \"jsoup\"") && libDecl.contains("version.ref = \"jsoup\"")
        )
    }

    @Test
    fun `verification metadata pins org jsoup jsoup at the fixed release with matching checksums`() {
        val text = File(repoRoot(), "gradle/verification-metadata.xml").readText()
        val component = text.lines()
            .firstOrNull { it.contains("group=\"org.jsoup\"") && it.contains("name=\"jsoup\"") }
            ?: error("org.jsoup component not found in gradle/verification-metadata.xml")
        assertTrue(
            "verification metadata must resolve org.jsoup:jsoup only at the fixed line $FIXED_JSOUP_VERSION " +
                "(found '$component')",
            component.contains("version=\"$FIXED_JSOUP_VERSION\"")
        )
        assertFalse(
            "no vulnerable jsoup 1.17.2 component may remain pinned in the verification metadata (B2-DEPS-01)",
            text.contains("name=\"jsoup\" version=\"1.17.2\"")
        )
        // Strict verification would fail the build on ANY checksum mismatch, so these
        // sha256 values are the exact Maven Central sidecar values for the fixed artifacts.
        assertTrue("jsoup jar sha256 must match the fixed release's published checksum", text.contains(JAR_SHA256))
        assertTrue("jsoup pom sha256 must match the fixed release's published checksum", text.contains(POM_SHA256))
    }

    // ---- behavior: the CVE payload stays inert text on the fixed parser ----

    @Test
    fun `control-character tag name is preserved, never promoted to raw-text script`() {
        // CVE-2026-71497 payload class: tag name ending in the ESC control char
        // (U+001B <= U+0020). Pre-fix trim() collapsed it to the raw-text `script`
        // element; post-fix the tokenizer preserves the char so the tag never
        // acquires `script`'s parsing behavior.
        val payload = "<article><p>before</p><script\u001B>alert(1)</script\u001B><p>after</p></article>"
        val doc = Jsoup.parse(payload)

        val controlTag = doc.getAllElements()
            .firstOrNull { it.tagName().contains("script") }
            ?: error("the control-character tag must exist in the tree")

        assertTrue(
            "the control-character tag must be parsed as 'script<ESC>', never trimmed to 'script'",
            controlTag.tagName().contains('\u001B')
        )
        assertEquals(
            "the control-character tag must NOT be the raw-text 'script' element (CVE-2026-71497)",
            false,
            controlTag.tagName() == "script"
        )
        assertFalse(
            "no real 'script' raw-text element may be produced by the payload (CVE-2026-71497)",
            doc.getElementsByTag("script").any { it.tagName() == "script" }
        )
        // Pre-fix the `alert(1)` content was swallowed as inert raw-text CDATA and
        // dropped from the body text; post-fix it remains ordinary in-flow text.
        assertEquals("before alert(1) after", doc.body().text())
    }

    @Test
    fun `markdown extractor keeps the CVE payload content as inert text`() {
        // The extraction may remove known-chrome/script elements, but the payload's
        // trap is that its content becomes an ACTIVE script element on the vulnerable
        // line. On the fixed line the containing tag is not raw-text, so the content
        // survives as inert text and a future HTML-sanitizing feature can never
        // re-serialize it as live markup.
        val payload = "<!DOCTYPE html><html><head><title>My Great Article</title></head><body><article>" +
            "<h1>My Great Article</h1><p>before</p><script\u001B>alert(1)</script\u001B>" +
            "<p>after</p></article></body></html>"
        val result = WebToMarkdownExtractor.extract(payload)

        assertEquals("My Great Article", result.title)
        assertTrue("surrounding content must survive extraction", result.markdown.contains("before"))
        assertTrue("surrounding content must survive extraction", result.markdown.contains("after"))
        assertFalse(
            "the payload's formerly-raw-text content must never surface as active <script> markup",
            result.markdown.contains("<script") || result.markdown.contains("</script")
        )
        assertTrue(
            "the payload's content must remain plain inert text (never dropped as raw-text CDATA)",
            result.markdown.contains("alert(1)")
        )
    }

    @Test
    fun `no production jsoup usage reaches the vulnerable Cleaner and Safelist API`() {
        // The advisory's exploit premise is a custom Safelist permitting raw-text
        // elements through Jsoup.clean/Cleaner. Until the upgrade the app only ever
        // calls Jsoup.parse (raw text stays text), so the app surface is limited to
        // the fixed-line upgrade. If a future feature wires a cleaner/safelist it
        // MUST be introduced against the fixed line deliberately — this scan flags it.
        val root = repoRoot()
        assertTrue("repo root must resolve for the production source scan", File(root, "app").isDirectory)
        val forbidden = listOf("Jsoup.clean", "Safelist", "Cleaner(")
        val offenders = mutableListOf<String>()
        fun scan(dir: File) {
            dir.listFiles()?.forEach { entry ->
                when {
                    entry.isDirectory -> scan(entry)
                    entry.extension == "kt" -> entry.readText()
                        .takeIf { forbidden.any(it::contains) }
                        ?.let { offenders += entry.relativeTo(root).path }
                }
            }
        }
        scan(File(root, "app/src/main"))
        assertTrue(
            "production code must not use the jsoup Cleaner/Safelist API while the app is at " +
                "the fixed line; found: $offenders (B2-DEPS-01)",
            offenders.isEmpty()
        )
    }

    private companion object {
        /** jsoup 1.23.1 — the first fixed release for CVE-2026-71497 / GHSA-pmhh-3w7g-xqp8. */
        const val FIXED_JSOUP_VERSION = "1.23.1"
        const val JAR_SHA256 = "8b15e2b28eeb1e0a88a9b7dab4dc0c23524491c56959785dea22f7846897b668"
        const val POM_SHA256 = "10faba526d66760cf3a57176636976cc78865935e94faddbf1637f3cce64c21b"

        fun repoRoot(): File {
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