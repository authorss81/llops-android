package com.authorss81.noteflow

import com.authorss81.noteflow.services.PsdExportPolicy
import com.authorss81.noteflow.services.PsdExportService.channelDataLength
import com.authorss81.noteflow.services.PsdExportService.channelSizeFor
import com.authorss81.noteflow.services.PsdExportService.layerRecordBytes
import com.authorss81.noteflow.services.PsdExportService.layerSectionLength
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-DOS-06 (phase-82): layered PSD export can no longer materialize N full-page
 * ARGB_8888 Bitmaps PLUS N per-layer uncompressed channel buffers at once.
 *
 * Pre-fix (`PsdExportService.kt:119-190`):
 *  - `buildLayerDataSection` buffered EVERY layer's 4 uncompressed channels
 *    (`layerPixelBlocks.add(chanBos.toByteArray())`) — ~6.6 MB per layer HELD
 *    SIMULTANEOUSLY for the whole section, on top of a fresh per-layer
 *    `IntArray(width*height)` — and `ImportExportService.exportPageToPsd`
 *    created one 1080x1528 ARGB_8888 Bitmap (~6.6 MB) PER layer with an
 *    UNBOUNDED layer count (Layers panel + restored vaults). A ~25-layer note
 *    peaked near ~350 MB heap at export time → OOM/ANR on 1-2 GB devices, on
 *    every export.
 *
 * After:
 *  - `ImportExportService.exportPageToPsd` clamps the exported DATA-layer count
 *    to [PsdExportPolicy.MAX_EXPORT_LAYER_COUNT] (16) BEFORE any per-layer
 *    bitmap is created and reports the omission so the UI shows a one-time
 *    non-alarming notice (never silent degradation).
 *  - `PsdExportService` writes channel PIXEL data straight to the destination
 *    DataOutputStream — layer by layer, channel by channel — reusing ONE
 *    `IntArray(width*height)` buffer; `layerPixelBlocks` is gone. Peak heap no
 *    longer scales with the number of layers.
 *
 * Pure JVM: policy behavior tests + the streaming-writer's layout/length
 * arithmetic (the Android `Bitmap` path itself can't run under
 * `isReturnDefaultValues`) + source-level wiring pins.
 */
class B2Dos06PsdExportLayerCapTest {

    private val mainSourceRoot by lazy { File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow") }

    // ---- policy: layer-count cap --------------------------------------------

    @Test
    fun `export layer count is capped at the budget`() {
        assertEquals(PsdExportPolicy.MAX_EXPORT_LAYER_COUNT, 16)
        assertEquals(16, PsdExportPolicy.capLayerCount(25))
        assertEquals(16, PsdExportPolicy.capLayerCount(16))
        assertEquals(8, PsdExportPolicy.capLayerCount(8))
        assertEquals(0, PsdExportPolicy.capLayerCount(0))
        assertEquals("a negative layer count can never yield a negative cap", 0, PsdExportPolicy.capLayerCount(-3))
    }

    @Test
    fun `omitted layer count reflects the budget breach only`() {
        assertEquals(25 - 16, PsdExportPolicy.omittedLayerCount(25))
        assertEquals(0, PsdExportPolicy.omittedLayerCount(16))
        assertEquals(0, PsdExportPolicy.omittedLayerCount(8))
        assertEquals(0, PsdExportPolicy.omittedLayerCount(0))
        assertTrue(PsdExportPolicy.isLayerCountCapped(25))
        assertFalse(PsdExportPolicy.isLayerCountCapped(16))
        assertFalse(PsdExportPolicy.isLayerCountCapped(8))
    }

    @Test
    fun `capping cuts the per-layer bitmap budget in half for a heavy note`() {
        // 25 drawing layers capped to 16: the number of ~6.6 MB full-page bitmaps
        // the exporter is allowed to create is bounded and strictly below 25.
        val exported = PsdExportPolicy.capLayerCount(25)
        assertTrue("the cap must bite for a >16-layer note", exported < 25)
        assertEquals(25 - exported, PsdExportPolicy.omittedLayerCount(25))
    }

    @Test
    fun `cap notice is non-alarming and truthful`() {
        val capped = PsdExportPolicy.noticeMessage(exportedLayerCount = 16, omittedLayerCount = 9)
        assertTrue("the notice must state how many layers were exported", "16" in capped)
        assertTrue("the notice must state how many we omitted", "9" in capped)
        assertTrue("the notice must name the max", PsdExportPolicy.MAX_EXPORT_LAYER_COUNT.toString() in capped)
        assertTrue("the notice must be informational, never alarming", "omitted" in capped)
        // the export keeps the TOP (highest-zOrder) layers and drops the bottom of
        // the stack; the notice must state that so users know which layers survived.
        assertTrue("the notice must say which end of the stack was kept", "top" in capped)

        val full = PsdExportPolicy.noticeMessage(exportedLayerCount = 16, omittedLayerCount = 0)
        assertTrue("an uncapped export says so plainly", "included all" in full)
    }

    @Test
    fun `notice never reports a negative count for degenerate inputs`() {
        val message = PsdExportPolicy.noticeMessage(exportedLayerCount = -1, omittedLayerCount = -2)
        assertTrue("negative inputs clamp to the zero case", "included all" in message)
    }

    // ---- layout: streaming writer's section arithmetic ----------------------

    @Test
    fun `channel size is one byte per pixel plus the two-byte compression header`() {
        assertEquals(1080 * 1528 + 2, channelSizeFor(1080, 1528))
        assertEquals(4 + 2, channelSizeFor(2, 2))
    }

    @Test
    fun `channel data length is four channels per layer`() {
        val width = 1080
        val height = 1528
        val perLayer = 4 * channelSizeFor(width, height)
        // 25 layers would have needed ~165 MB of buffered channel data pre-fix;
        // the capped 16-layer export tops out at the same per-layer cost * 16.
        assertEquals(25 * perLayer, channelDataLength(25, width, height))
        assertEquals(16 * perLayer, channelDataLength(16, width, height))
        assertEquals("layer count is clamped", 0, channelDataLength(-2, width, height))
    }

    @Test
    fun `section length is records plus all channel pixel data`() {
        val width = 1080
        val height = 1528
        val records = ByteArray(500)
        val layerCount = 16
        assertEquals(
            records.size + channelDataLength(layerCount, width, height),
            layerSectionLength(records.size, layerCount, width, height)
        )
        // The pre-fix code wrote the section length as `buildLayerDataSection()
        // .size` = records + channel data; the streamed write must stay identical.
        assertEquals(layerSectionLength(records.size, layerCount, width, height), records.size + 16 * 4 * channelSizeFor(width, height))
    }

    @Test
    fun `layer info record keeps the exact byte layout the streamed writer relies on`() {
        val width = 1080
        val height = 1528
        val channelSize = channelSizeFor(width, height)
        val record = layerRecordBytes(name = "Drawing Layer 1", isVisible = true, opacity = 255, width = width, height = height, channelSize = channelSize)
        val bb = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN)

        // rectangle: top, left, bottom, right
        assertEquals(0, bb.getInt(0))
        assertEquals(0, bb.getInt(4))
        assertEquals(height, bb.getInt(8))
        assertEquals(width, bb.getInt(12))
        // 4 channels, declared in A(-1), R(0), G(1), B(2) order
        assertEquals(4, bb.getShort(16).toInt())
        assertEquals(-1, bb.getShort(18).toInt())
        assertEquals(channelSize, bb.getInt(20))
        assertEquals(0, bb.getShort(24).toInt())
        assertEquals(channelSize, bb.getInt(26))
        assertEquals(1, bb.getShort(30).toInt())
        assertEquals(channelSize, bb.getInt(32))
        assertEquals(2, bb.getShort(36).toInt())
        assertEquals(channelSize, bb.getInt(38))
        // blend signature and mode
        assertEquals("8BIM", String(record, 42, 4, Charsets.US_ASCII))
        assertEquals("norm", String(record, 46, 4, Charsets.US_ASCII))
        // opacity, clipping, flags (visible), filler
        assertEquals(255, bb.get(50).toInt() and 0xFF)
        assertEquals(0, bb.get(51).toInt())
        assertEquals(0, bb.get(52).toInt())
        assertEquals(0, bb.get(53).toInt())
        // extra data block: mask size + blending size + Pascal name
        val extraLen = bb.getInt(54)
        assertEquals(0, bb.getInt(58))
        assertEquals(0, bb.getInt(62))
        val nameLen = bb.get(66).toInt() and 0xFF
        assertEquals("Drawing Layer 1".length, nameLen)
        assertEquals("Drawing Layer 1", String(record, 67, nameLen, Charsets.US_ASCII))
        assertEquals(8 + 1 + nameLen + (1 + nameLen) % 2, extraLen)
        assertEquals("record + extra block length", 58 + extraLen, record.size)
    }

    @Test
    fun `hidden layers flag the visibility bit`() {
        val visible = layerRecordBytes("A", isVisible = true, opacity = 255, width = 2, height = 2, channelSize = 6)
        val hidden = layerRecordBytes("A", isVisible = false, opacity = 255, width = 2, height = 2, channelSize = 6)
        // flags byte sits at offset 52 (see layout test above); 2 = hidden, 0 = visible.
        assertEquals(0, visible[52].toInt())
        assertEquals(2, hidden[52].toInt())
        // and the layer-record length is identical either way (layout is static).
        assertEquals(visible.size, hidden.size)
    }

    // ---- source pins: the vulnerabilities are gone from the code -------------

    @Test
    fun `ImportExportService no longer iterates every layer into a bitmap`() {
        val source = File(mainSourceRoot, "services/ImportExportService.kt").readText()
        // The pre-fix `layers.sortedBy { it.zOrder }.forEachIndexed { ... }` created a
        // fresh full-page Bitmap per layer with NO cap. Now the zOrder-sorted list is
        // capped BEFORE any bitmap is created: takeLast(exportedDataLayers) keeps the
        // TOP (highest-zOrder, front-most) layers in bottom->top record order.
        assertTrue(
            "the export must clamp via the policy before rendering",
            source.contains("PsdExportPolicy.capLayerCount(")
        )
        assertTrue(
            "the export must track omitted layers for the notice",
            source.contains("omittedDataLayers = PsdExportPolicy.omittedLayerCount(")
        )
        assertTrue(
            "only the capped top layers may be rendered (bounded selection)",
            source.contains("takeLast(exportedDataLayers)")
        )
        assertTrue(
            "the bottom omitted layers must be identified for the merged preview",
            source.contains("dropLast(exportedDataLayers)")
        )
        assertTrue(
            "the function must now return the outcome carrying the cap metadata",
            source.contains("PsdExportService.PsdExportOutcome(")
        )
        // The unbounded consume-every-layer loop shape is gone: no bare
        // `sortedBy { it.zOrder }.forEachIndexed` remains in the file.
        assertFalse(
            "no unbounded per-layer bitmap loop may survive in ImportExportService",
            source.contains("sortedBy { it.zOrder }.forEachIndexed")
        )
    }

    @Test
    fun `omitted bottom layers are folded into one bounded merged preview`() {
        val source = File(mainSourceRoot, "services/ImportExportService.kt").readText()
        assertTrue(
            "the cap path must pass a compositeExtras preview of the omitted layers",
            source.contains("compositeExtras = compositeExtras")
        )
        assertTrue(
            "the omitted layers render into a single shared preview bitmap",
            source.contains("renderLayersAndStrokesToCanvas(previewCanvas")
        )
        assertTrue(
            "the preview must only exist when layers were actually omitted",
            source.contains("if (omittedDataLayers > 0")
        )
    }

    @Test
    fun `PsdExportService defensively bounds unexpected oversized input`() {
        val source = File(mainSourceRoot, "services/PsdExportService.kt").readText()
        assertTrue(
            "records/channels/composite passes must be bounded even for a future oversized caller",
            source.contains("PsdExportPolicy.MAX_EXPORT_LAYER_COUNT + 1)")
        )
        assertTrue(
            "the composite pass must bound the merged-preview extras too",
            source.contains("compositeExtras.take(PsdExportPolicy.MAX_EXPORT_LAYER_COUNT")
        )
    }

    @Test
    fun `PsdExportService streams channels instead of buffering all of them`() {
        val raw = File(mainSourceRoot, "services/PsdExportService.kt").readText()
        // Drop comment/doc lines so the references to the historical
        // `layerPixelBlocks`/`chanBos` behavior do not trip the pins themselves.
        val source = raw.lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("*/")
            }
            .joinToString("\n")
        assertFalse(
            "the per-layer channel block accumulation must be gone",
            source.contains("layerPixelBlocks")
        )
        assertFalse(
            "the per-layer channel ByteArrayOutputStream must be gone",
            source.contains("chanBos")
        )
        assertTrue(
            "channel pixel data must be written straight to the destination stream",
            source.contains("writeChannelPixels(")
        )
        assertTrue(
            "the streamed section length must be computed up front",
            source.contains("layerSectionLength(")
        )
    }

    @Test
    fun `PsdExportService reuses a single pixel buffer`() {
        val source = File(mainSourceRoot, "services/PsdExportService.kt").readText()
        // pre-fix created `IntArray(width*height)` per layer (builder) AND for the
        // composite: two independent full-page buffers plus N channel blocks.
        assertEquals("exactly ONE pixel buffer may be allocated", 1, Regex("IntArray\\(width \\* height\\)").findAll(source).count())
        // Both the per-layer channel pass and the composite pass reuse that buffer.
        assertEquals(2, Regex("getPixels").findAll(source).count())
    }

    @Test
    fun `EditorScreen surfaces the layer-cap notice`() {
        val source = File(mainSourceRoot, "ui/screens/EditorScreen.kt").readText()
        assertTrue(
            "the PSD export call site must read the capped outcome",
            source.contains("outcome.wasLayerCapped")
        )
        assertTrue(
            "the call site must render the policy's one-time notice when capped",
            source.contains("PsdExportPolicy.noticeMessage(")
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