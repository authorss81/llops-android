package com.authorss81.noteflow.services

import android.util.Log

/**
 * JNI Bridge Wrapper for LibMyPaint C++ Native Brush Engine (`libmypaint.so`).
 * 
 * Safe loading pattern:
 * Tries `System.loadLibrary("mypaint")` on initialization.
 * If the native shared library `.so` file is compiled and present in `app/src/main/jniLibs/`,
 * native methods are available. Otherwise, `isNativeLibraryLoaded` reports false and
 * callers fall back cleanly to AGSL/Vector rendering without crashing.
 */
object LibMyPaintJni {

    private const val TAG = "LibMyPaintJni"

    var isNativeLibraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("mypaint")
            isNativeLibraryLoaded = true
            Log.i(TAG, "libmypaint.so loaded successfully via JNI")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLibraryLoaded = false
            Log.w(TAG, "libmypaint.so is not available in jniLibs. C++ studio engine falling back to AGSL/Vector: ${e.message}")
        } catch (e: Throwable) {
            isNativeLibraryLoaded = false
            Log.e(TAG, "Failed to initialize LibMyPaint JNI bridge: ${e.message}")
        }
    }

    // --- Native C++ Entry Points (matching JNI C/C++ signature) ---

    private external fun nativeInitEngine(): Long
    private external fun nativeSetBrush(enginePtr: Long, brushId: String, size: Float, opacity: Float, hardness: Float): Boolean
    private external fun nativeNewStroke(enginePtr: Long, x: Float, y: Float, pressure: Float)
    private external fun nativeStrokeTo(enginePtr: Long, x: Float, y: Float, pressure: Float, dtime: Float): Boolean
    private external fun nativeEndStroke(enginePtr: Long)
    private external fun nativeFreeEngine(enginePtr: Long)

    // --- High Level Kotlin Safe Calls ---

    fun initEngine(): Long {
        if (!isNativeLibraryLoaded) return 0L
        return try {
            nativeInitEngine()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeInitEngine failed: ${e.message}")
            0L
        }
    }

    fun setBrush(enginePtr: Long, brushId: String, size: Float, opacity: Float, hardness: Float): Boolean {
        if (!isNativeLibraryLoaded || enginePtr == 0L) return false
        return try {
            nativeSetBrush(enginePtr, brushId, size, opacity, hardness)
        } catch (e: Throwable) {
            false
        }
    }

    fun newStroke(enginePtr: Long, x: Float, y: Float, pressure: Float) {
        if (!isNativeLibraryLoaded || enginePtr == 0L) return
        try {
            nativeNewStroke(enginePtr, x, y, pressure)
        } catch (_: Throwable) {}
    }

    fun strokeTo(enginePtr: Long, x: Float, y: Float, pressure: Float, dtime: Float): Boolean {
        if (!isNativeLibraryLoaded || enginePtr == 0L) return false
        return try {
            nativeStrokeTo(enginePtr, x, y, pressure, dtime)
        } catch (e: Throwable) {
            false
        }
    }

    fun endStroke(enginePtr: Long) {
        if (!isNativeLibraryLoaded || enginePtr == 0L) return
        try {
            nativeEndStroke(enginePtr)
        } catch (_: Throwable) {}
    }

    fun freeEngine(enginePtr: Long) {
        if (!isNativeLibraryLoaded || enginePtr == 0L) return
        try {
            nativeFreeEngine(enginePtr)
        } catch (_: Throwable) {}
    }
}
