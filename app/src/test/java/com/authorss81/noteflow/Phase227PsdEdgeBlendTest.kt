package com.authorss81.noteflow

import com.authorss81.noteflow.services.LayerBlendPresetPolicy
import com.authorss81.noteflow.services.PaperEdgePolicy
import com.authorss81.noteflow.services.PsdExportPolicy
import com.authorss81.noteflow.services.PsdExportService.layerRecordBytes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 227 — layered PSD fidelity + the deckled-edge export path.
 *
 * The pre-227 writer stamped EVERY layer "norm" (NORMAL) with 100% opacity, so
 * a PSD re-opened in Photoshop flattened to a washed-out SRC_OVER paste even
 * though the on-canvas sheet honored per-layer opacity + blend. This pins the
 * two-phase fix:
 *
 *  - [PsdExportPolicy.psdBlendSignature] maps EVERY renderer-supported
 *    [LayerEntity.blendMode] to its 4-char Photoshop key, and the layer INFO
 *    record now carries it (offset 46..49) — the exported stack reopens with
 *    the SAME composite the editor showed.
 *  - The deckled edge clips every export canvas (transparent PNG + every PSD
 *    layer + the flattened composite), driven by the SAME pure-JVM
 *    [PaperEdgePolicy] node stream as the on-canvas card, so exported sheets
 *    keep the hand-cut silhouette instead of a white rectangle.
 */
class Phase227PsdEdgeBlendTest {

    // ---- layer-blend fidelity -------------------------------------------------

    @Test
    fun `every renderer-supported blend maps to a 4-char PSD key`() {
        val keys = LayerBlendPresetPolicy.RENDERER_SUPPORTED_MODES
        assertTrue("renderer must support at least the classic stack", keys.isNotEmpty())
        for (mode in keys) {
            val sig = PsdExportPolicy.psdBlendSignature(mode)
            assertEquals("signature for '$mode' must be exactly 4 chars", 4, sig.length)
            assertEquals("unknown/leftover modes must not be NORMAL ghost-written", mode != "NORMAL", sig != "norm")
        }
        // every distinct key is used at most once (no two modes collapse).
        val distinct = keys.map(PsdExportPolicy::psdBlendSignature).toSet()
        assertEquals("no two renderer modes may collapse to one key", keys.size, distinct.size)
    }

    @Test
    fun `unknown or corrupt blend strings fall back to the PSD default`() {
        for (bad in listOf("", "unicorn", "MULT", "norm weird", "VERY_LONG_MODE")) {
            assertEquals("fallback for '$bad'", "norm", PsdExportPolicy.psdBlendSignature(bad))
        }
    }

    @Test
    fun `mapping is case-insensitive like the renderer`() {
        assertEquals(
            PsdExportPolicy.psdBlendSignature("multiply"),
            PsdExportPolicy.psdBlendSignature("MULTIPLY")
        )
        assertEquals("mul ", PsdExportPolicy.psdBlendSignature("multiply"))
    }

    @Test
    fun `layer record writes the real blend key at the PSD layout offsets`() {
        val mul = layerRecordBytes(name = "Ink", isVisible = true, opacity = 148, width = 4, height = 4, channelSize = 18, blendSignature = "mul ")
        assertEquals("8BIM", String(mul, 42, 4, Charsets.US_ASCII))
        assertEquals("mul ", String(mul, 46, 4, Charsets.US_ASCII))
        // opacity byte is a 0..255 Int, blend key must NOT shift the layout.
        assertEquals(148, mul[50].toInt() and 0xFF)
        // a non-normal key keeps the EXACT legacy record length (layout is static).
        val norm = layerRecordBytes(name = "Ink", isVisible = true, opacity = 148, width = 4, height = 4, channelSize = 18)
        assertEquals("blend key must not change record length", norm.size, mul.size)
    }

    @Test
    fun `dodge and burn map to their distinct PSD keys`() {
        assertEquals("ldiv", PsdExportPolicy.psdBlendSignature("COLOR_DODGE"))
        assertEquals("idiv", PsdExportPolicy.psdBlendSignature("COLOR_BURN"))
        assertEquals("smud", PsdExportPolicy.psdBlendSignature("EXCLUSION"))
        assertEquals("hLit", PsdExportPolicy.psdBlendSignature("HARD_LIGHT"))
        assertEquals("sLit", PsdExportPolicy.psdBlendSignature("SOFT_LIGHT"))
    }

    // ---- deckled-edge export path --------------------------------------------

    @Test
    fun `export sheets share the exact deterministic edge math`() {
        // The CLIENTS of DeckleExportHelper and the canvas both consume the same
        // pure-JVM stream — assert the geometry itself matches the on-canvas
        // paper family and stays bounded at a 360px-wide (1080px at 3x) page.
        val amp = PaperEdgePolicy.amplitudePx(1080f / 360f)
        val nodes = PaperEdgePolicy.deckleNodes(0f, 0f, 1080f, 1528f, amp, PaperEdgePolicy.seedFor(false))
        assertTrue("degraded to nothing on wide export pages", nodes.isNotEmpty())
        for ((nx, ny) in nodes) {
            assertTrue(nx >= -amp - 1e-3f && nx <= 1080f + amp + 1e-3f)
            assertTrue(ny >= -amp - 1e-3f && ny <= 1528f + amp + 1e-3f)
        }
    }

    // ---- source pins (the wiring lives inside Android-only call sites) --------

    private val mainSourceRoot by lazy { File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow") }

    @Test
    fun `canvas card and exports are threaded from the same persisted edge`() {
        val canvas = File(mainSourceRoot, "ui/components/AnnotationCanvas.kt").readText()
        assertTrue(canvas.contains("paperEdge = paperEdge"))
        assertTrue(canvas.contains("grainScale = grainScale"))
        assertTrue(
            "deckled clip must wrap the three paper passes",
            canvas.contains("clipPath(path = sheet)")
        )

        val settings = File(mainSourceRoot, "services/SettingsManager.kt").readText()
        assertTrue(settings.contains("paper_edge"))
        assertTrue(settings.contains("paper_texture_strength"))

        val imports = File(mainSourceRoot, "services/ImportExportService.kt").readText()
        assertTrue("raster export must honor the deckled clip", imports.contains("DeckleExportHelper"))
        assertTrue("export must expose the transparent background option", imports.contains("transparentBackground"))

        val editor = File(mainSourceRoot, "ui/screens/EditorScreen.kt").readText()
        assertTrue("editor must surface the edge chips", editor.contains("Paper Edge"))
        assertTrue("editor must surface the strength dial", editor.contains("Paper Texture"))
        assertTrue("editor must offer the transparent PNG export", editor.contains("Export Page as Transparent PNG"))
    }

    @Test
    fun `psd export fuses real opacity and blend into the layer records`() {
        val imports = File(mainSourceRoot, "services/ImportExportService.kt").readText()
        assertTrue(
            "psd layers must carry the entity's real opacity",
            imports.contains("(layerEntity.opacity * 255f).toInt()")
        )
        assertTrue(
            "psd layers must carry the entity's blend key",
            imports.contains("PsdExportPolicy.psdBlendSignature(")
        )
        val psd = File(mainSourceRoot, "services/PsdExportService.kt").readText()
        assertTrue("psd flatten must restore the layer blend", psd.contains("blendPaint(layer)"))
        assertTrue(
            "psd flatten must clip to the deckled sheet",
            psd.contains("DeckleExportHelper.sheetPath(")
        )
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}