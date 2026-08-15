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
                    val layerDataBytes = buildLayerDataSection(activeLayers, width, height)
                    dos.writeInt(layerDataBytes.size)
                    dos.write(layerDataBytes)

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
                    val pixelCount = width * height
                    val pixels = IntArray(pixelCount)
                    compositeBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                    // Red channel
                    for (p in pixels) dos.writeByte((p shr 16) and 0xFF)
                    // Green channel
                    for (p in pixels) dos.writeByte((p shr 8) and 0xFF)
                    // Blue channel
                    for (p in pixels) dos.writeByte(p and 0xFF)
                    // Alpha channel
                    for (p in pixels) dos.writeByte((p ushr 24) and 0xFF)

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

    private fun buildLayerDataSection(layers: List<PsdLayer>, width: Int, height: Int): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // Count of layers (negative indicates absolute alpha channel info)
        val count = layers.size
        dos.writeShort(count)

        // Layer info records
        val layerPixelBlocks = mutableListOf<ByteArray>()

        for (layer in layers) {
            // Top, Left, Bottom, Right
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeInt(height)
            dos.writeInt(width)

            // Number of channels: 4 (A=-1, R=0, G=1, B=2)
            dos.writeShort(4)

            val pixels = IntArray(width * height)
            layer.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val channelSize = width * height + 2 // 2 bytes for compression header (0)
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
            dos.writeByte(layer.opacity.coerceIn(0, 255)) // Opacity
            dos.writeByte(0) // Clipping = base
            dos.writeByte(if (layer.isVisible) 0 else 2) // Flags (bit 1 = visible)
            dos.writeByte(0) // Filler

            // Extra data block
            val extraBos = java.io.ByteArrayOutputStream()
            val extraDos = DataOutputStream(extraBos)
            extraDos.writeInt(0) // Layer mask data size
            extraDos.writeInt(0) // Layer blending ranges size

            // Layer name (Pascal string, padded to even length)
            val nameBytes = layer.name.take(31).toByteArray(Charsets.US_ASCII)
            extraDos.writeByte(nameBytes.size)
            extraDos.write(nameBytes)
            val namePadding = (1 + nameBytes.size) % 2
            if (namePadding != 0) extraDos.writeByte(0)

            val extraBytes = extraBos.toByteArray()
            dos.writeInt(extraBytes.size)
            dos.write(extraBytes)

            // Store raw uncompressed channel pixel data
            val chanBos = java.io.ByteArrayOutputStream()
            val chanDos = DataOutputStream(chanBos)

            // Alpha
            chanDos.writeShort(0) // Raw
            for (p in pixels) chanDos.writeByte((p ushr 24) and 0xFF)
            // Red
            chanDos.writeShort(0)
            for (p in pixels) chanDos.writeByte((p shr 16) and 0xFF)
            // Green
            chanDos.writeShort(0)
            for (p in pixels) chanDos.writeByte((p shr 8) and 0xFF)
            // Blue
            chanDos.writeShort(0)
            for (p in pixels) chanDos.writeByte(p and 0xFF)

            layerPixelBlocks.add(chanBos.toByteArray())
        }

        // Write layer channel image data
        for (block in layerPixelBlocks) {
            dos.write(block)
        }

        dos.flush()
        return bos.toByteArray()
    }
}
