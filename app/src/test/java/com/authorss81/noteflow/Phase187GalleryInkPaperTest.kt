package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 187 — source pins for the gallery ink-note paper texture.
 *
 * The notebook-paper rendering must: (1) go through the pure-JVM
 * [com.authorss81.noteflow.services.InkCardPaperPolicy] (no inline label/alpha
 * literals de-syncing from the tests); (2) be drawn with `drawBehind`
 * (allocation-free `DrawScope` primitives, not a composited image layer);
 * (3) never rasterize `pointsJson` in a grid item (phase-188 risk #1); and
 * (4) keep the honest "Handwritten note" label path. Mechanical pins on
 * purpose.
 */
class Phase187GalleryInkPaperTest {

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

    @Test
    fun `ink paper texture derives from the pure-JVM policy`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "ink classification must come from the policy, not an inline type set",
            gallery.contains("InkCardPaperPolicy.isInkCanvasPage(page.sourceFileType)")
        )
        assertTrue(
            "the honest label must come from the policy constant",
            gallery.contains("InkCardPaperPolicy.HANDWRITTEN_LABEL")
        )
        assertTrue(
            "paper fill must use the policy alpha on scheme.surface",
            gallery.contains("scheme.surface.copy(alpha = InkCardPaperPolicy.PAPER_BACKGROUND_ALPHA)")
        )
        assertTrue(
            "dots must use the policy alpha on scheme.outlineVariant",
            gallery.contains("scheme.outlineVariant.copy(alpha = InkCardPaperPolicy.GRID_ALPHA)")
        )
    }

    @Test
    fun `texture is drawn with drawBehind and bounded by the policy`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "the paper must be drawn behind the card via drawBehind (no image layer)",
            gallery.contains("import androidx.compose.ui.draw.drawBehind")
        )
        assertTrue(
            "loop bounds must come from gridColumns/gridRows",
            gallery.contains("InkCardPaperPolicy.gridColumns(size.width, spacingPx)") &&
                gallery.contains("InkCardPaperPolicy.gridRows(size.height, spacingPx)")
        )
        assertTrue(
            "the Circle-shape icon chip must remain (small draw icon)",
            gallery.contains(".clip(CircleShape)")
        )
    }

    @Test
    fun `gallery texturing never touches stroke geometry`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "grid-item rendering must NOT deserialize pointsJson (phase-188 risk #1)",
            !gallery.contains("pointsJson")
        )
        assertTrue(
            "grid-item rendering must NOT call the stroke repository",
            !gallery.contains("getStrokesForPage") && !gallery.contains("strokesForPage")
        )
    }

    @Test
    fun `paper applies only to ink pages without preview text`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "paper texture must be gated on an ink page WITH no OCR preview",
            gallery.contains("val showPaperTexture = isInkPage && preview.isEmpty()")
        )
        assertTrue(
            "the existing text preview must remain the preferred render path",
            gallery.contains("if (preview.isNotEmpty())")
        )
        assertTrue(
            "paper must not be added to imported pdf/image/text pages",
            gallery.contains("\"pdf\" -> \"PDF page\"") && gallery.contains("\"text\" -> \"Empty page\"")
        )
    }
}