package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.BrushPresetFileCodec
import com.authorss81.noteflow.services.BrushPresetFileCodec.DecodeResult
import com.authorss81.noteflow.services.BrushPresetImportPolicy
import com.authorss81.noteflow.services.BrushPresetPack
import com.authorss81.noteflow.services.BrushStudioParams
import com.authorss81.noteflow.services.BrushPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 155: pure-JVM `.inkbrush` file codec — JSON bundle round-trips, the
 * raw-protobuf pass-through, the BOM/whitespace routing fix, and the import
 * policy caps.
 */
class BrushPresetFileCodecTest {

    private val preset = BrushPreset(
        id = "soft_watercolor",
        name = "Soft Watercolor",
        tool = StrokeTool.WATERCOLOR,
        brushParams = BrushStudioParams(
            dilution = 0.92f, charge = 0.45f, pull = 0.62f,
            impasto = 0.05f, paperGrain = 0.92f, splatterSpread = 0.35f
        ),
        size = 14f,
        colorHex = "#3B82F6",
        pressureCurveKey = "light"
    )

    private fun decoded(bytes: ByteArray): DecodeResult = BrushPresetFileCodec.decode(bytes)

    // ---- round trip ------------------------------------------------------------

    @Test
    fun encodeThenDecodeRoundTripsPreset() {
        val result = decoded(BrushPresetFileCodec.encode(preset))
        assertTrue(result is DecodeResult.Preset)
        val out = (result as DecodeResult.Preset).preset
        assertEquals(preset.name, out.name)
        assertEquals(preset.tool, out.tool)
        assertEquals(preset.size, out.size)
        assertEquals(preset.colorHex, out.colorHex)
        assertEquals(preset.pressureCurveKey, out.pressureCurveKey)
        assertEquals(preset.brushParams, out.brushParams)
    }

    @Test
    fun encodeListDecodeListRoundTrips() {
        val list = listOf(preset, preset.copy(id = "marker", tool = StrokeTool.MARKER, name = "Marker", size = 9f))
        val json = BrushPresetFileCodec.encodeList(list)
        val back = BrushPresetFileCodec.decodeList(json)
        assertEquals(2, back.size)
        assertEquals("Soft Watercolor", back[0].name)
        assertEquals(StrokeTool.MARKER, back[1].tool)
    }

    @Test
    fun decodeListIgnoresGarbageEntries() {
        val json = """["not a bundle", "${"{}"}"]"""
        assertEquals(0, BrushPresetFileCodec.decodeList(json).size)
    }

    @Test
    fun decodeListBlankIsEmpty() {
        assertEquals(0, BrushPresetFileCodec.decodeList("").size)
        assertEquals(0, BrushPresetFileCodec.decodeList("   ").size)
    }

    // ---- JSON routing fix (BOM + leading whitespace) ----------------------------

    @Test
    fun utf8BomJsonStillDecodesAsJson() {
        val withBom = "\uFEFF".toByteArray(Charsets.UTF_8) + BrushPresetFileCodec.encode(preset)
        assertTrue(decoded(withBom) is DecodeResult.Preset)
    }

    @Test
    fun leadingWhitespaceJsonStillDecodesAsJson() {
        val withSpaces = "\n\t  ".toByteArray(Charsets.UTF_8) + BrushPresetFileCodec.encode(preset)
        assertTrue(decoded(withSpaces) is DecodeResult.Preset)
    }

    // ---- raw protobuf pass-through ----------------------------------------------

    @Test
    fun binaryBytesRouteToRawProtobufUntouched() {
        val binary = byteArrayOf(0x0A, 0x05, 0x00, 0x01, 0x02, 0x03, 0x04, 0x08, 0x01)
        val result = decoded(binary)
        assertTrue(result is DecodeResult.RawProtobuf)
        assertTrue((result as DecodeResult.RawProtobuf).bytes.contentEquals(binary))
    }

    @Test
    fun emptyFileIsInvalid() {
        assertTrue(decoded(ByteArray(0)) is DecodeResult.Invalid)
    }

    @Test
    fun whitespaceOnlyFileIsInvalid() {
        assertTrue(decoded("\n  \t".toByteArray(Charsets.UTF_8)) is DecodeResult.Invalid)
    }

    @Test
    fun oversizeFileIsInvalid() {
        val bytes = ByteArray(BrushPresetFileCodec.MAX_ENCODED_BYTES + 1) { 'a'.code.toByte() }
        assertTrue(decoded(bytes) is DecodeResult.Invalid)
    }

    // ---- validation --------------------------------------------------------------

    @Test
    fun wrongMagicIsInvalid() {
        val bad = BrushPresetFileCodec.encode(preset)
            .toString(Charsets.UTF_8)
            .replace("\"format\":\"inkflow.brushpreset\"", "\"format\":\"other\"")
            .toByteArray(Charsets.UTF_8)
        val result = decoded(bad)
        assertTrue(result is DecodeResult.Invalid)
    }

    @Test
    fun unsupportedVersionIsInvalid() {
        val bad = BrushPresetFileCodec.encode(preset)
            .toString(Charsets.UTF_8)
            .replace("\"version\":1", "\"version\":999")
            .toByteArray(Charsets.UTF_8)
        val result = decoded(bad)
        assertTrue(result is DecodeResult.Invalid)
    }

    @Test
    fun unknownToolIsInvalid() {
        val bad = BrushPresetFileCodec.encode(preset)
            .toString(Charsets.UTF_8)
            .replace("\"tool\":\"WATERCOLOR\"", "\"tool\":\"NOT_A_TOOL\"")
            .toByteArray(Charsets.UTF_8)
        assertTrue(decoded(bad) is DecodeResult.Invalid)
    }

    @Test
    fun outOfRangeParamsAreInvalid() {
        val bad = BrushPresetFileCodec.encode(preset)
            .toString(Charsets.UTF_8)
            .replace("\"dilution\":0.92", "\"dilution\":5.0")
            .toByteArray(Charsets.UTF_8)
        assertTrue(decoded(bad) is DecodeResult.Invalid)
    }

    @Test
    fun outOfRangeSizeIsInvalid() {
        val bad = BrushPresetFileCodec.encode(preset)
            .toString(Charsets.UTF_8)
            .replace("\"size\":14.0", "\"size\":1000.0")
            .toByteArray(Charsets.UTF_8)
        assertTrue(decoded(bad) is DecodeResult.Invalid)
    }

    @Test
    fun invalidColorIsRejected() {
        val bad = BrushPresetFileCodec.encode(preset)
            .toString(Charsets.UTF_8)
            .replace("\"colorHex\":\"#3B82F6\"", "\"colorHex\":\"red\"")
            .toByteArray(Charsets.UTF_8)
        assertTrue(decoded(bad) is DecodeResult.Invalid)
    }

    // ---- sanitization + id determinism --------------------------------------------

    @Test
    fun sanitizeNameStripsControlCharactersAndSlash() {
        assertEquals("My-Gouache", BrushPresetFileCodec.sanitizeName("My/Gouache"))
        assertEquals("A  B", BrushPresetFileCodec.sanitizeName("A\n\tB"))
        assertEquals("C".repeat(BrushPresetImportPolicy.MAX_PRESET_NAME_CHARS), BrushPresetFileCodec.sanitizeName("C".repeat(60)))
        assertNull(BrushPresetFileCodec.sanitizeName("   "))
        assertNull(BrushPresetFileCodec.sanitizeName(null))
    }

    @Test
    fun derivedIdIsDeterministic() {
        val a = BrushPresetFileCodec.derivedId(preset.tool, preset.brushParams)
        val b = BrushPresetFileCodec.derivedId(preset.tool, preset.brushParams)
        assertEquals(a, b)
        val c = BrushPresetFileCodec.derivedId(StrokeTool.MARKER, preset.brushParams)
        assertTrue(a != c)
    }

    // ---- import policy ------------------------------------------------------------

    @Test
    fun importPolicyCaps() {
        assertTrue(BrushPresetImportPolicy.sizeAllowed(100))
        assertTrue(!BrushPresetImportPolicy.sizeAllowed(0))
        assertTrue(!BrushPresetImportPolicy.sizeAllowed(BrushPresetImportPolicy.MAX_BRUSH_FILE_BYTES + 1))
        assertTrue(BrushPresetImportPolicy.countAllowed(31))
        assertTrue(!BrushPresetImportPolicy.countAllowed(BrushPresetImportPolicy.MAX_IMPORTED_PRESETS))
        assertTrue(!BrushPresetImportPolicy.countAllowed(-1))
        assertTrue(BrushPresetImportPolicy.isFreehandTool(StrokeTool.WATERCOLOR))
        assertTrue(!BrushPresetImportPolicy.isFreehandTool(StrokeTool.TEXT))
    }

    @Test
    fun importPolicyKnownCurves() {
        for (key in listOf("linear", "light", "heavy", "custom")) {
            assertTrue(BrushPresetImportPolicy.isKnownPressureCurve(key))
        }
        assertTrue(!BrushPresetImportPolicy.isKnownPressureCurve("weird"))
    }

    @Test
    fun canImportRequiresAllCaps() {
        val fileBytes = 512
        assertTrue(BrushPresetImportPolicy.canImport(preset, 5, fileBytes))
        assertTrue(!BrushPresetImportPolicy.canImport(preset, 5, BrushPresetImportPolicy.MAX_BRUSH_FILE_BYTES + 1))
        assertTrue(!BrushPresetImportPolicy.canImport(preset, BrushPresetImportPolicy.MAX_IMPORTED_PRESETS, fileBytes))
        assertTrue(!BrushPresetImportPolicy.canImport(preset.copy(tool = StrokeTool.TEXT), 5, fileBytes))
    }

    @Test
    fun curatedPackStillValid() {
        assertTrue(BrushPresetPack.validateAllPresets().isEmpty())
        assertTrue(BrushPresetPack.validatePresetSizes().isEmpty())
    }
}
