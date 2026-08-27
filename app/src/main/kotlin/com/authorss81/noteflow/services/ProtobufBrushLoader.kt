package com.authorss81.noteflow.services

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import com.authorss81.noteflow.data.model.StrokeTool
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Loader and manager for custom binary .inkbrush protobuf definitions created with Google Ink Tooling.
 *
 * Supports loading dynamic brush families from assets or local disk storage with automatic,
 * seamless fallback to StockBrushes when custom protobufs are absent or invalid.
 */
object ProtobufBrushLoader {

    private const val TAG = "ProtobufBrushLoader"
    private const val ASSETS_BRUSH_DIR = "brushes"

    // In-memory cache for parsed BrushFamily instances
    private val cachedFamilies = ConcurrentHashMap<String, BrushFamily>()

    /**
     * Get a BrushFamily for a given StrokeTool, prioritizing loaded .inkbrush protobuf definitions
     * and falling back gracefully to standard StockBrushes.
     */
    fun getBrushFamilyForTool(context: Context?, tool: StrokeTool): BrushFamily {
        val cacheKey = tool.name.lowercase()
        cachedFamilies[cacheKey]?.let { return it }

        // Attempt asset load if context is provided
        if (context != null) {
            val assetFileName = "$ASSETS_BRUSH_DIR/${cacheKey}.inkbrush"
            val familyFromAsset = loadFromAssets(context, assetFileName)
            if (familyFromAsset != null) {
                cachedFamilies[cacheKey] = familyFromAsset
                return familyFromAsset
            }
        }

        // Fallback to stock brushes
        val stockFamily = getStockFallback(tool)
        cachedFamilies[cacheKey] = stockFamily
        return stockFamily
    }

    /**
     * Load a BrushFamily from a custom binary .inkbrush protobuf ByteArray.
     */
    fun loadFromByteArray(bytes: ByteArray, name: String = "custom"): BrushFamily? {
        if (bytes.isEmpty()) return null
        return try {
            // Try BrushFamily static factory methods available in androidx.ink
            val createMethod = BrushFamily::class.java.methods.firstOrNull {
                it.name == "createFromProtobuf" || it.name == "fromProtobuf" || it.name == "fromByteArray"
            }
            if (createMethod != null) {
                (createMethod.invoke(null, bytes) as? BrushFamily)
            } else {
                Log.w(TAG, "Protobuf creation method not available on BrushFamily in current androidx.ink version, using fallback.")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to parse .inkbrush protobuf (${FailureLogPolicy.classNameToken(e)})")
            null
        }
    }

    /**
     * Load a BrushFamily from an InputStream.
     */
    fun loadFromInputStream(inputStream: InputStream, name: String = "custom"): BrushFamily? {
        return try {
            val bytes = inputStream.use { it.readBytes() }
            loadFromByteArray(bytes, name)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read brush stream (${FailureLogPolicy.classNameToken(e)})")
            null
        }
    }

    /**
     * Load a BrushFamily from a local File.
     */
    fun loadFromFile(file: File): BrushFamily? {
        if (!file.exists() || !file.canRead()) return null
        return try {
            val bytes = file.readBytes()
            loadFromByteArray(bytes, file.name)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load brush file (${FailureLogPolicy.classNameToken(e)})")
            null
        }
    }

    /**
     * Internal helper to load from Android Assets directory.
     */
    private fun loadFromAssets(context: Context, assetPath: String): BrushFamily? {
        return try {
            val assetManager = context.assets
            val files = assetManager.list(ASSETS_BRUSH_DIR) ?: emptyArray()
            val fileNameOnly = assetPath.substringAfterLast('/')
            if (!files.contains(fileNameOnly)) {
                return null
            }
            assetManager.open(assetPath).use { stream ->
                loadFromInputStream(stream, fileNameOnly)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Default StockBrushes fallback implementation.
     *
     * Phase 18: the six new tools map to the nearest real family so the Ink-API
     * advanced path (isAdvanced strokes) degrades honestly. CHARCOAL and
     * DRY_BRUSH get the unstable/rough graphite family (grainy, split tips),
     * INK_WASH and GOUACHE get the solid marker family (heavier deposits than
     * the classic pressurePen), the rest keep the stable pressure-sensitive pen.
     *
     * `@SuppressLint("RestrictedApi")`: the only pencil/graphite family
     * androidx.ink exposes is `pencilUnstable`, which is `@RestrictTo` the ink
     * library group. There is no non-restricted pencil brush, so this fallback
     * deliberately keeps the graphite family (its whole purpose is matching the
     * PENCIL/CHARCOAL/DRY_BRUSH tools) rather than degrading them to a solid
     * pen.
     */
    @SuppressLint("RestrictedApi")
    fun getStockFallback(tool: StrokeTool): BrushFamily {
        return when (tool) {
            StrokeTool.FOUNTAIN_PEN -> StockBrushes.pressurePen()
            StrokeTool.PENCIL, StrokeTool.CHARCOAL, StrokeTool.DRY_BRUSH -> StockBrushes.pencilUnstable
            StrokeTool.MARKER, StrokeTool.WATERCOLOR -> StockBrushes.marker()
            StrokeTool.HIGHLIGHTER -> StockBrushes.highlighter()
            StrokeTool.OIL_PAINT -> StockBrushes.pressurePen()
            StrokeTool.INK_WASH, StrokeTool.GOUACHE -> StockBrushes.marker()
            StrokeTool.OIL_PASTEL, StrokeTool.SMUDGE, StrokeTool.SPLATTER, StrokeTool.PALETTE_KNIFE -> StockBrushes.pressurePen()
            else -> StockBrushes.pressurePen()
        }
    }

    /**
     * Clear cached brush families (e.g. when custom brushes are reloaded/imported).
     */
    fun clearCache() {
        cachedFamilies.clear()
    }
}
