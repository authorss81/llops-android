package com.authorss81.noteflow.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.data.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

object PsdExportService {

    data class PsdLayer(
        val name: String,
        val bitmap: Bitmap,
        val isVisible: Boolean = true,
        val opacity: Int = 255
    )

    /**
     * B2-DOS-06 (phase-82): the outcome of a layered PSD export. [file] is null
     * on failure; [exportedLayerCount]/[omittedLayerCount] report the layer
     * budget decision so the caller can surface a non-alarming notice when the
     * layer cap bit (see [PsdExportPolicy.noticeMessage]).
     */
    data class PsdExportOutcome(
        val file: File?,
        val exportedLayerCount: Int,
        val omittedLayerCount: Int
    ) {
        val wasLayerCapped: Boolean get() = omittedLayerCount > 0
    }

    suspend fun exportLayersToPsd(
        context: Context,
        title: String,
        width: Int = 1080,
        height: Int = 1528,
        layers: List<PsdLayer>
    ): File? = withContext(Dispatchers.IO) {
        try {
            val sanitizeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Canvas_Layers" }
            val exportDir = File(context.cacheDir, "psd_exports").apply { if (!exists()) mkdirs() }
            val psdFile = File(exportDir, "$sanitizeTitle.psd")

            val activeLayers = layers.ifEmpty {
                listOf(PsdLayer("Background", Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    Canvas(this).drawColor(Color.WHITE)
                }))
            }

            FileOutputStream(psdFile).use { fos ->
                DataOutputStream(BufferedOutputStream(fos)).use { dos ->
                    // 1. PSD Header
                    dos.writeBytes("8BPS") // Signature
                    dos.writeShort(1)     // Version = 1
                    dos.write(ByteArray(6)) // Reserved
                    dos.writeShort(4)     // Channels (A, R, G, B)
                    dos.writeInt(height)
                    dos.writeInt(width)
                    dos.writeShort(8)     // Depth = 8 bits
                    dos.writeShort(3)     // Mode = 3 (RGB Color)

                    // 2. Color Mode Data
                    dos.writeInt(0)

                    // 3. Image Resources
                    dos.writeInt(0)

                    // 4. Layer and Mask Information
                    // B2-DOS-06 (phase-82): the section is written STREAMING —
                    // the small layer INFO records go into a tiny in-heap buffer
                    // (bounded, ~2 KB for 16 layers) and the per-layer channel
                    // PIXEL data is written straight to the destination stream
                    // one channel at a time, reusing ONE IntArray pixel buffer.
                    // The pre-fix `layerPixelBlocks` (every layer's 4 channels
                    // materialized as full-size ByteArrays ~6.6 MB each, ALL held
                    // at once) is gone, so peak heap no longer scales with N.
                    val channelSize = channelSizeFor(width, height)
                    val recordsBytes = buildLayerRecords(activeLayers, width, height, channelSize)
                    val sectionLength = layerSectionLength(recordsBytes.size, activeLayers.size, width, height)
                    dos.writeInt(sectionLength)
                    dos.write(recordsBytes)

                    // One reused pixel buffer for EVERY layer AND the composite —
                    // a single IntArray(width*height) instead of one per layer.
                    val pixels = IntArray(width * height)
                    writeLayerChannelData(dos, activeLayers, pixels, width, height)

                    // 5. Merged Composite Image Data
                    val compositeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val compCanvas = Canvas(compositeBitmap)
                    compCanvas.drawColor(Color.WHITE)
                    for (layer in activeLayers) {
                        if (layer.isVisible) {
                            compCanvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                        }
                    }

                    // Write composite image data: 0 = Raw Uncompressed
                    dos.writeShort(0)
                    compositeBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    // Red channel
                    writeChannelPixels(dos, pixels, 16)
                    // Green channel
                    writeChannelPixels(dos, pixels, 8)
                    // Blue channel
                    writeChannelPixels(dos, pixels, 0)
                    // Alpha channel
                    writeChannelPixels(dos, pixels, 24)

                    compositeBitmap.recycle()
                }
            }

            // B1-PLAT-3 (phase-59): no auto-copy to public Downloads — the PSD stays
            // app-private in cacheDir until the user picks a destination.
            psdFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Size in bytes of ONE uncompressed channel's on-disk block inside the
     * layer-and-mask section: 2 bytes for the Raw compression header plus one
     * byte per pixel. Pure JVM so the layout arithmetic is unit-testable.
     */
    internal fun channelSizeFor(width: Int, height: Int): Int = width * height + 2

    /**
     * Total bytes of the section's channel PIXEL data for [layerCount] layers
     * (4 channels each). This is what `layerSectionLength` adds to the info
     * records — the pre-fix code held exactly this many bytes in `layerPixelBlocks`.
     */
    internal fun channelDataLength(layerCount: Int, width: Int, height: Int): Int =
        layerCount.coerceAtLeast(0) * PsdExportPolicy.CHANNELS_PER_LAYER * channelSizeFor(width, height)

    /**
     * The `Layer and Mask Information` section length written into the PSD
     * header — [recordsBytesSize] (layer count + info records) plus all channel
     * pixel data. Streaming the pixel data requires knowing this total up front,
     * and it must stay byte-identical to the pre-fix materialized section.
     */
    internal fun layerSectionLength(recordsBytesSize: Int, layerCount: Int, width: Int, height: Int): Int =
        recordsBytesSize + channelDataLength(layerCount, width, height)

    /**
     * One layer's INFO record (rectangle, channel table, blend signature,
     * opacity/clipping/flags, extra-data block with the Pascal name). NO pixel
     * data — the record only declares each channel's on-disk size
     * ([channelSize]). Pure JVM so a unit test can pin the exact layout the
     * streaming writer relies on.
     */
    internal fun layerRecordBytes(
        name: String,
        isVisible: Boolean,
        opacity: Int,
        width: Int,
        height: Int,
        channelSize: Int
    ): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // Top, Left, Bottom, Right
        dos.writeInt(0)
        dos.writeInt(0)
        dos.writeInt(height)
        dos.writeInt(width)

        // Number of channels: 4 (A=-1, R=0, G=1, B=2)
        dos.writeShort(PsdExportPolicy.CHANNELS_PER_LAYER)

        // Alpha
        dos.writeShort(-1)
        dos.writeInt(channelSize)
        // Red
        dos.writeShort(0)
        dos.writeInt(channelSize)
        // Green
        dos.writeShort(1)
        dos.writeInt(channelSize)
        // Blue
        dos.writeShort(2)
        dos.writeInt(channelSize)

        // Signature & Blend Mode
        dos.writeBytes("8BIM")
        dos.writeBytes("norm") // Normal blend mode
        dos.writeByte(opacity.coerceIn(0, 255)) // Opacity
        dos.writeByte(0) // Clipping = base
        dos.writeByte(if (isVisible) 0 else 2) // Flags (bit 1 = visible)
        dos.writeByte(0) // Filler

        // Extra data block
        val extraBos = java.io.ByteArrayOutputStream()
        val extraDos = DataOutputStream(extraBos)
        extraDos.writeInt(0) // Layer mask data size
        extraDos.writeInt(0) // Layer blending ranges size

        // Layer name (Pascal string, padded to even length)
        val nameBytes = name.take(31).toByteArray(Charsets.US_ASCII)
        extraDos.writeByte(nameBytes.size)
        extraDos.write(nameBytes)
        val namePadding = (1 + nameBytes.size) % 2
        if (namePadding != 0) extraDos.writeByte(0)

        val extraBytes = extraBos.toByteArray()
        dos.writeInt(extraBytes.size)
        dos.write(extraBytes)

        dos.flush()
        return bos.toByteArray()
    }

    /**
     * Layer count + every layer's info record (see [layerRecordBytes]) as the
     * section's leading bytes. Small and bounded (a few KB), this is the only
     * section buffer ever held in heap.
     */
    private fun buildLayerRecords(layers: List<PsdLayer>, width: Int, height: Int, channelSize: Int): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // Count of layers (negative indicates absolute alpha channel info)
        val count = layers.size
        dos.writeShort(count)

        for (layer in layers) {
            val record = layerRecordBytes(layer.name, layer.isVisible, layer.opacity, width, height, channelSize)
            dos.write(record)
        }

        dos.flush()
        return bos.toByteArray()
    }

    /**
     * B2-DOS-06: writes every layer's channel PIXEL data straight to the
     * destination [dos] — one layer at a time, one channel at a time — reusing
     * the single [pixels] buffer for each layer. Nothing is accumulated.
     */
    private fun writeLayerChannelData(
        dos: DataOutputStream,
        layers: List<PsdLayer>,
        pixels: IntArray,
        width: Int,
        height: Int
    ) {
        for (layer in layers) {
            layer.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Alpha
            writeChannelPixels(dos, pixels, 24)
            // Red
            writeChannelPixels(dos, pixels, 16)
            // Green
            writeChannelPixels(dos, pixels, 8)
            // Blue
            writeChannelPixels(dos, pixels, 0)
        }
    }

    /** One Raw-compressed channel: 2-byte header (0) then one byte per pixel. */
    private fun writeChannelPixels(dos: DataOutputStream, pixels: IntArray, shift: Int) {
        dos.writeShort(0) // Raw
        when (shift) {
            24 -> for (p in pixels) dos.writeByte((p ushr 24) and 0xFF)
            16 -> for (p in pixels) dos.writeByte((p shr 16) and 0xFF)
            8 -> for (p in pixels) dos.writeByte((p shr 8) and 0xFF)
            else -> for (p in pixels) dos.writeByte(p and 0xFF)
        }
    }
}
