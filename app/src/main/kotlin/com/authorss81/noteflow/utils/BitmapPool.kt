package com.authorss81.noteflow.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * High-performance Bitmap Pool for reusing offscreen render buffers and PDF page textures.
 * Eliminates Garbage Collection (GC) pauses during rapid canvas scrolling or rendering.
 */
object BitmapPool {

    private const val TAG = "BitmapPool"
    private const val MAX_POOL_SIZE = 12

    private val pool = ConcurrentHashMap<String, ConcurrentLinkedQueue<Bitmap>>()

    private fun getKey(width: Int, height: Int, config: Bitmap.Config): String {
        return "${width}x${height}_${config.name}"
    }

    fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val key = getKey(width, height, config)
        val queue = pool[key]
        var bitmap = queue?.poll()
        if (bitmap != null && !bitmap.isRecycled && bitmap.width == width && bitmap.height == height) {
            bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            return bitmap
        }
        return Bitmap.createBitmap(width, height, config)
    }

    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable) return
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val key = getKey(bitmap.width, bitmap.height, config)
        val queue = pool.getOrPut(key) { ConcurrentLinkedQueue() }
        if (queue.size < MAX_POOL_SIZE) {
            queue.offer(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    fun getOptionsWithInBitmap(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = config
            inMutable = true
        }
        val key = getKey(width, height, config)
        val reusable = pool[key]?.poll()
        if (reusable != null && !reusable.isRecycled && reusable.width == width && reusable.height == height) {
            options.inBitmap = reusable
        } else {
            // Sizing/state mismatch: hand it back so the pool keeps its budget,
            // and never risk BitmapFactory's IllegalArgumentException on inBitmap.
            reusable?.let { release(it) }
        }
        return options
    }

    fun clear() {
        pool.values.forEach { queue ->
            while (true) {
                val b = queue.poll() ?: break
                if (!b.isRecycled) {
                    b.recycle()
                }
            }
        }
        pool.clear()
        Log.d(TAG, "BitmapPool cleared")
    }
}
