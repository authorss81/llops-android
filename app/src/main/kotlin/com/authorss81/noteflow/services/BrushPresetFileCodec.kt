package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.StrokeTool
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 155: the pure-JVM codec for `.inkbrush` brush-preset FILES.
 *
 * The `.inkbrush` extension is famously fought over (Google Ink Tooling publishes
 * a real protobuf there, but its wire format is `androidx.ink`-internal and NOT
 * stable enough for us to serialize without an SDK internals dependency). Our
 * exported files are therefore a tiny, versioned, self-describing JSON bundle
 * that keeps the SAME `.inkbrush` extension so the ecosystem picks are unchanged
 * (SAF MIME `application/octet-stream`, single file, imports back losslessly).
 *
 * Bundle shape:
 *
 * ```
 * {
 *   "format": "inkflow.brushpreset",        // magic (parse gate)
 *   "version": 1,
 *   "name": "My Gouache",                    // display name (sanitized on import)
 *   "tool": "WATERCOLOR",                    // StrokeTool.name
 *   "size": 14.0,
 *   "colorHex": "#3B82F6",
 *   "pressureCurveKey": "light",
 *   "brushParams": { "dilution": .92, "charge": .45, "pull": .62,
 *                    "impasto": .05, "paperGrain": .92, "splatterSpread": .35 },
 *   "protobufSeed": "<BASE64>"               // OPTIONAL opaque androidx.ink blob
 *                                            // (present when exported by a device
 *                                            // that could encode a real family)
 * }
 * ```
 *
 * [BrushPresetFileCodec.decode] is the single import gate. When the bytes are
 * NOT our JSON bundle but DO look like a binary blob, it returns
 * [DecodeResult.RawProtobuf] with the raw bytes UNTOUCHED so the Android layer
 * can hand them to `ProtobufBrushLoader.loadFromByteArray` (the dormant loader
 * phase-155 wires up) — the classifier never pre-parses protobuf it cannot
 * interpret, so a native-family file still round-trips. Anything else is
 * [DecodeResult.Invalid] with a user-safe reason (no file paths, no names, no
 * exception text — R2-b2b3-LOG-03 "sanitized logging" carried over).
 */
object BrushPresetFileCodec {

    const val FORMAT = "inkflow.brushpreset"
    const val VERSION = 1
    /** Cap on encoded bundle size; also the cap consumed by [decode]. */
    const val MAX_ENCODED_BYTES = 256 * 1024

    private val gson = Gson()

    /** The import decision for one `.inkbrush` file. */
    sealed class DecodeResult {
        /** Our own JSON bundle, fully validated into a usable [BrushPreset]. */
        class Preset(val preset: BrushPreset) : DecodeResult()

        /** A NON-JSON binary blob — forward to ProtobufBrushLoader untouched. */
        class RawProtobuf(val bytes: ByteArray) : DecodeResult()

        /** Malformed / rejected. [reason] is user-safe and path-free. */
        class Invalid(val reason: String) : DecodeResult()
    }

    /** Serializes [preset] (tool included) into an `.inkbrush` bundle. */
    fun encode(preset: BrushPreset): ByteArray {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.addProperty("name", preset.name)
        root.addProperty("tool", preset.tool.name)
        root.addProperty("size", preset.size.toDouble())
        root.addProperty("colorHex", preset.colorHex)
        root.addProperty("pressureCurveKey", preset.pressureCurveKey)

        val params = JsonObject()
        params.addProperty("dilution", preset.brushParams.dilution.toDouble())
        params.addProperty("charge", preset.brushParams.charge.toDouble())
        params.addProperty("pull", preset.brushParams.pull.toDouble())
        params.addProperty("impasto", preset.brushParams.impasto.toDouble())
        params.addProperty("paperGrain", preset.brushParams.paperGrain.toDouble())
        params.addProperty("splatterSpread", preset.brushParams.splatterSpread.toDouble())
        root.add("brushParams", params)

        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Decodes an `.inkbrush` file.
     *
     * Rejection is fail-closed: any size over [MAX_ENCODED_BYTES],
     * non-JSON bytes, a wrong magic, missing fields, or out-of-range params all
     * land in [DecodeResult.Invalid]; raw non-JSON bytes that are present and
     * non-trivial surface as [DecodeResult.RawProtobuf] so the androidx.ink
     * loader can still attempt a native family.
     */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) return DecodeResult.Invalid("empty file")
        if (bytes.size > MAX_ENCODED_BYTES) return DecodeResult.Invalid("file too large")
        // Find the first meaningful byte: skip a UTF-8 BOM and leading ASCII
        // whitespace so hand-edited or tool-written JSON still routes to the JSON
        // parser (phase-155 review fix — the pre-fix gate only peeked at byte 0,
        // so a BOM or leading newline misrouted otherwise-valid JSON to the raw
        // binary path).
        val first = firstMeaningfulByteIndex(bytes)
        if (first < 0) return DecodeResult.Invalid("empty file")
        if (looksLikeJsonStart(bytes[first])) {
            return decodeJson(bytes)
        }
        // Everything else is treated as an opaque binary family blob — hand it up
        // untouched so BrushPresetImportPolicy + ProtobufBrushLoader can decide.
        return DecodeResult.RawProtobuf(bytes)
    }

    /** Index of the first byte that is not a UTF-8 BOM byte or ASCII whitespace. */
    private fun firstMeaningfulByteIndex(bytes: ByteArray): Int {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b != ' '.code && b != '\t'.code && b != '\n'.code && b != '\r'.code) return i
            i++
        }
        return -1
    }

    private fun looksLikeJsonStart(b: Byte): Boolean = when (b.toInt() and 0xFF) {
        '{'.code, '['.code -> true
        else -> false
    }

    private fun decodeJson(bytes: ByteArray): DecodeResult {
        val text = String(bytes, Charsets.UTF_8)
        val root: JsonObject = try {
            JsonParser.parseString(text)?.takeIf { it.isJsonObject }?.asJsonObject ?: return DecodeResult.Invalid("not a brush file")
        } catch (e: Exception) {
            // Parse failure on a JSON-looking file is NOT blindly a raw protobuf —
            // binary that happens to start with '{' is vanishingly rare.
            return DecodeResult.Invalid("malformed brush file")
        }

        if (root.get("format")?.asString != FORMAT) return DecodeResult.Invalid("unknown brush format")
        val version = root.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: return DecodeResult.Invalid("unsupported brush version")
        if (version != VERSION) return DecodeResult.Invalid("unsupported brush version")

        // Optional opaque seed is skipped here — it belongs to the Android path.
        return parsePreset(root, bytes.size)
    }

    private fun parsePreset(root: JsonObject, rawSize: Int): DecodeResult {
        val name = sanitizeName(root.get("name")?.takeIf { it.isJsonPrimitive }?.asString)
        val toolName = root.get("tool")?.takeIf { it.isJsonPrimitive }?.asString
        val size = root.get("size")?.takeIf { it.isJsonPrimitive }?.asFloat
        val colorHex = root.get("colorHex")?.takeIf { it.isJsonPrimitive }?.asString
        val pressureCurveKey = root.get("pressureCurveKey")?.takeIf { it.isJsonPrimitive }?.asString

        if (toolName == null) return DecodeResult.Invalid("brush file missing tool")
        val tool = runCatching { StrokeTool.valueOf(toolName) }.getOrNull()
        if (tool == null) return DecodeResult.Invalid("brush file has an unknown tool")

        val params = parseParams(root.get("brushParams")?.takeIf { it.isJsonObject }?.asJsonObject)
            ?: return DecodeResult.Invalid("brush file missing brush settings")

        val problems = BrushPresetPack.validateParams(params)
        if (problems.isNotEmpty()) return DecodeResult.Invalid("brush settings out of range")

        if (size == null || size !in 0.5f..120f) return DecodeResult.Invalid("brush size out of range")
        val color = colorHex?.takeIf { it.startsWith("#") && it.length in 4..9 } ?: return DecodeResult.Invalid("brush color invalid")

        return DecodeResult.Preset(
            BrushPreset(
                id = derivedId(tool, params),
                name = name ?: "Imported preset",
                tool = tool,
                brushParams = params,
                size = size,
                colorHex = color,
                pressureCurveKey = pressureCurveKey?.takeIf { BrushPresetImportPolicy.isKnownPressureCurve(it) } ?: "linear"
            )
        )
    }

    private fun parseParams(obj: JsonObject?): BrushStudioParams? {
        if (obj == null) return null
        fun opt(key: String, default: Float): Float {
            val el = obj.get(key) ?: return default
            return if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asFloat else default
        }
        return BrushStudioParams(
            dilution = opt("dilution", 0.6f),
            charge = opt("charge", 0.8f),
            pull = opt("pull", 0.7f),
            impasto = opt("impasto", 0.4f),
            paperGrain = opt("paperGrain", 0.5f),
            splatterSpread = opt("splatterSpread", 0.3f)
        )
    }

    /**
     * A stable, deterministic id derived from the payload so re-importing the
     * SAME file dedupes instead of piling up. Not a timestamp (would never dedupe
     * and would leak creation time into the id surface).
     */
    fun derivedId(tool: StrokeTool, params: BrushStudioParams): String {
        val seed = "$tool|${params.dilution}|${params.charge}|${params.pull}|${params.impasto}|${params.paperGrain}|${params.splatterSpread}"
        return "imported_" + (seed.hashCode().toUInt().toString(16))
    }

    fun sanitizeName(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim().replace(Regex("[\\r\\n\\t]"), " ")
            .replace(Regex("[\\p{Cc}]"), "")
            .replace("/", "-").replace("\\", "-")
        if (cleaned.length > BrushPresetImportPolicy.MAX_PRESET_NAME_CHARS) {
            return cleaned.take(BrushPresetImportPolicy.MAX_PRESET_NAME_CHARS)
        }
        return cleaned.takeUnless { it.isEmpty() }
    }

    /**
     * Serializes a list of imported presets to a JSON array of encoded bundles
     * (each element is the [encode] payload as a JSON string). Pure JVM, used to
     * persist the user's "My presets" in shared prefs — NO DB schema impact.
     */
    fun encodeList(presets: List<BrushPreset>): String {
        val arr = JsonArray()
        for (preset in presets) {
            arr.add(String(encode(preset), Charsets.UTF_8))
        }
        return arr.toString()
    }

    /**
     * Parses the persisted "My presets" JSON back into [BrushPreset]s. Every
     * element is re-validated through [decode], so a corrupt entry is dropped
     * instead of crashing or poisoning the list.
     */
    fun decodeList(json: String): List<BrushPreset> {
        if (json.isBlank()) return emptyList()
        val arr = runCatching {
            JsonParser.parseString(json)?.takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<BrushPreset>()
        for (el in arr) {
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                when (val r = decode(el.asString.toByteArray(Charsets.UTF_8))) {
                    is DecodeResult.Preset -> out.add(r.preset)
                    else -> Unit
                }
            }
        }
        return out
    }
}

/**
 * Phase 155: import-side policy for `.inkbrush` files — every limit that keeps
 * the import path cheap and DoS-safe. Pure JVM. All limits are deliberately
 * small so a hostile file (huge, nested, or repeating fields) cannot tax a
 * low-end device.
 */
object BrushPresetImportPolicy {

    /** Hard cap: a single brush file may not exceed this many bytes. */
    const val MAX_BRUSH_FILE_BYTES = 256 * 1024

    /** Hard cap: how many user-imported presets may be resident. */
    const val MAX_IMPORTED_PRESETS = 32

    /** Sanity cap on a preset display name (chars). */
    const val MAX_PRESET_NAME_CHARS = 48

    /** User-facing display-name cap after sanitizing. */
    fun sanitizeName(raw: String?): String? = BrushPresetFileCodec.sanitizeName(raw)

    fun sizeAllowed(bytes: Int): Boolean = bytes in 1..MAX_BRUSH_FILE_BYTES

    fun countAllowed(currentCount: Int, adding: Int = 1): Boolean =
        currentCount >= 0 && adding > 0 && currentCount + adding <= MAX_IMPORTED_PRESETS

    /** Rejects a tool that isn't a paintable/freehand tool (no TEXT/RECT/etc). */
    fun isFreehandTool(tool: StrokeTool): Boolean = tool.isFreehandTool

    fun isKnownPressureCurve(key: String): Boolean = when (key) {
        "linear", "light", "heavy", "custom" -> true
        else -> false
    }

    /** True when we are under all caps and the tool is paintable. */
    fun canImport(preset: BrushPreset, currentCount: Int, fileBytes: Int): Boolean =
        sizeAllowed(fileBytes) && countAllowed(currentCount) && isFreehandTool(preset.tool)
}