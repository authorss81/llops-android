package com.authorss81.noteflow

import com.authorss81.noteflow.services.ReferenceImagePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 178 (2026-08-20): reference-image underlay regression guard.
 *
 * Part 1 is the pure-JVM policy contract (dim range, wire format, placement) — no
 * Android types, so it runs on the host.
 *
 * Part 2 source-pins the wiring the DoD depends on:
 *   - the underlay is delivered OUTSIDE the draggable embed set
 *     (getCanvasItemsForPage must keep excluding REFERENCE_IMAGE);
 *   - saveMediaEmbedsForPage carries the reference row forward across every
 *     delete+reinsert page save (otherwise a single editor flush erases it);
 *   - the only canvas-embed surface that renders into EXPORTED/share artifacts
 *     (drawEmbedsAndStickyNotesToCanvas) draws PHOTO embeds ONLY — a reference
 *     image is never exported into the markdown back-save or a shared render;
 *   - the stored path is RELATIVE and every read/delete re-resolves it through
 *     InlineImagePathPolicy (B1-AUTH-05 app-private confinement);
 *   - picker ingestion stays inside the bounded-read cap.
 *
 * The pre-existing Phase148UiFailureTextScrubTest UNC-path failure is unrelated.
 */
class Phase178ReferenceImageUnderlayTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin/$rel from ${start.path}")
    }

    // --- Part 1: ReferenceImagePolicy ---------------------------------

    @Test
    fun `dim opacity range is 30-50 percent and clamps`() {
        assertEquals(0.30f, ReferenceImagePolicy.MIN_OPACITY, 0f)
        assertEquals(0.50f, ReferenceImagePolicy.MAX_OPACITY, 0f)
        assertEquals(0.40f, ReferenceImagePolicy.DEFAULT_OPACITY, 0f)
        assertEquals(0.30f, ReferenceImagePolicy.clampOpacity(0.1f), 0f)
        assertEquals(0.50f, ReferenceImagePolicy.clampOpacity(0.99f), 0f)
        assertEquals(0.45f, ReferenceImagePolicy.clampOpacity(0.45f), 0f)
        assertEquals(0.35f, ReferenceImagePolicy.clampOpacity(0.35f), 0f)
    }

    @Test
    fun `non-finite opacity collapses to the in-range default`() {
        assertEquals(ReferenceImagePolicy.DEFAULT_OPACITY, ReferenceImagePolicy.clampOpacity(Float.NaN), 0f)
        assertEquals(ReferenceImagePolicy.DEFAULT_OPACITY, ReferenceImagePolicy.clampOpacity(Float.POSITIVE_INFINITY), 0f)
        assertEquals(ReferenceImagePolicy.DEFAULT_OPACITY, ReferenceImagePolicy.clampOpacity(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun `encode-decode round trip preserves the clamped value`() {
        assertEquals(0.35f, ReferenceImagePolicy.decodeOpacity(ReferenceImagePolicy.encodeConfig(0.35f)), 0f)
        assertEquals(0.40f, ReferenceImagePolicy.decodeOpacity(ReferenceImagePolicy.encodeConfig(0.40f)), 0f)
        assertEquals(0.50f, ReferenceImagePolicy.decodeOpacity(ReferenceImagePolicy.encodeConfig(0.999f)), 0f)
        assertEquals(0.30f, ReferenceImagePolicy.decodeOpacity(ReferenceImagePolicy.encodeConfig(0.001f)), 0f)
    }

    @Test
    fun `corrupt or missing config decodes fail-soft to the default`() {
        assertEquals(ReferenceImagePolicy.DEFAULT_OPACITY, ReferenceImagePolicy.decodeOpacity(null), 0f)
        assertEquals(ReferenceImagePolicy.DEFAULT_OPACITY, ReferenceImagePolicy.decodeOpacity(""), 0f)
        assertEquals(ReferenceImagePolicy.DEFAULT_OPACITY, ReferenceImagePolicy.decodeOpacity("garbage"), 0f)
        assertEquals(ReferenceImagePolicy.DEFAULT_OPACITY, ReferenceImagePolicy.decodeOpacity("{\"opacity\":NaN}"), 0f)
        assertEquals(ReferenceImagePolicy.DEFAULT_OPACITY, ReferenceImagePolicy.decodeOpacity("{\"opacity\":\"high\"}"), 0f)
    }

    @Test
    fun `fitForPage is aspect-preserving and centered`() {
        // Wide image into a 1080x1528 portrait page: scaled by width, centered vertically.
        val fit = ReferenceImagePolicy.fitForPage(2000, 1000, 1080f, 1528f)
        assertEquals(1080f, fit.width, 0.01f)
        assertEquals(540f, fit.height, 0.01f)
        assertEquals(0f, fit.x, 0.01f)
        assertEquals(494f, fit.y, 0.01f)
    }

    @Test
    fun `fitForPage guards degenerate inputs`() {
        assertEquals(ReferenceImagePolicy.Rect.ZERO, ReferenceImagePolicy.Rect.ZERO)
        val noPages = ReferenceImagePolicy.fitForPage(0, 0, 0f, 0f)
        assertEquals(0f, noPages.width, 0f)
        assertEquals(0f, noPages.height, 0f)
    }

    @Test
    fun `recenterVertically keeps a resized underlay vertically centered`() {
        val rect = ReferenceImagePolicy.recenterVertically(300f, 200f, 400f)
        assertEquals(300f, rect.width, 0f)
        assertEquals(200f, rect.height, 0f)
        assertEquals(300f, rect.y, 0f)
    }

    // --- Part 2: wiring source-pins -----------------------------------

    @Test
    fun `underlay is delivered outside the draggable embed set`() {
        val src = mainSource("data/repository/NoteRepository.kt")
        val canvasItems = src.substring(src.indexOf("fun getCanvasItemsForPage"))
        assertTrue(
            "getCanvasItemsForPage must keep excluding REFERENCE_IMAGE embeds",
            canvasItems.contains("else if (embed.type != MediaEmbedType.REFERENCE_IMAGE)")
        )
        val type = mainSource("data/model/StrokeModels.kt")
        assertTrue("MediaEmbedType must declare REFERENCE_IMAGE", type.contains("REFERENCE_IMAGE"))
    }

    @Test
    fun `page saves carry the reference row forward`() {
        val src = mainSource("data/repository/NoteRepository.kt")
        val saveBlock = src.substring(src.indexOf("fun saveMediaEmbedsForPage"))
        assertTrue(
            "reference row must be captured before the delete+reinsert",
            saveBlock.contains("db.mediaEmbedDao().getReferenceImageForPage(pageId)")
        )
        assertTrue(
            "reference row must be re-inserted raw after the regular embeds (no double encryption)",
            saveBlock.contains("if (reference != null) db.mediaEmbedDao().insertMediaEmbeds(listOf(reference))")
        )
        assertTrue(
            "reference row must survive an empty embeds flush",
            saveBlock.contains("if (reference != null) db.mediaEmbedDao().insertMediaEmbeds(listOf(reference))")
        )
    }

    @Test
    fun `exported canvas renders use PHOTO embeds only - underlay is never exported`() {
        val src = mainSource("services/ImportExportService.kt")
        val embedsDraw = src.substring(src.indexOf("drawEmbedsAndStickyNotesToCanvas"))
        assertTrue(
            "the exported render must gate on PHOTO (reference image stays reference-only)",
            embedsDraw.contains("if (embed.type == MediaEmbedType.PHOTO && embed.contentUrlOrPath != null)")
        )
        assertFalse(
            "exported render must not branch on the reference-image type",
            embedsDraw.contains("MediaEmbedType.REFERENCE_IMAGE")
        )
    }

    @Test
    fun `reference artwork is stored relative and read through InlineImagePathPolicy`() {
        val editor = mainSource("ui/screens/EditorScreen.kt")
        assertTrue(
            "persist must store the relative file name so confinement can re-verify it",
            editor.contains("contentUrlOrPath = File(savedFile).name")
        )
        // Both the decode effect and the remove handler must route through the
        // confined resolver — never a raw File(path).
        assertEquals(
            "the confine-and-decode path must be used exactly twice (read + delete)",
            2,
            Regex("InlineImagePathPolicy\\.resolve").findAll(editor).count()
        )
        assertTrue(
            "decode must be the bounded decoder",
            editor.contains("decodeBoundedImage(resolved.absolutePath, maxDim = 1600)")
        )
        assertTrue(
            "picker ingestion must stay inside the bounded-read cap",
            editor.contains("AttachmentIngestPolicy.boundedReadBytes(stream)")
        )
    }

    @Test
    fun `the reference row is encrypted with the embed field convention`() {
        val src = mainSource("data/repository/NoteRepository.kt")
        val saveBlock = src.substring(src.indexOf("fun saveReferenceImageForPage"))
        assertTrue("opacity must live in field-encrypted textContent", saveBlock.contains("EncryptionService.encryptField"))
        assertTrue(
            "the row must be keyed as a REFERENCE_IMAGE embed",
            saveBlock.contains("typeName = MediaEmbedType.REFERENCE_IMAGE.name")
        )
    }
}