package com.authorss81.noteflow.ui.components

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi

object ThermalSanityHelper {
    private var cachedThermalStatus: Int = 0 // NONE

    /**
     * Get the current thermal status of the device. Returns 0 if not supported or below API 29.
     */
    fun getCurrentThermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.currentThermalStatus ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Safely register a listener to observe thermal changes in real time.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun registerThermalListener(context: Context, listener: (Int) -> Unit): PowerManager.OnThermalStatusChangedListener? {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val pmListener = PowerManager.OnThermalStatusChangedListener { status ->
                cachedThermalStatus = status
                listener(status)
            }
            powerManager?.addThermalStatusListener(pmListener)
            pmListener
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun unregisterThermalListener(context: Context, listener: PowerManager.OnThermalStatusChangedListener) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.removeThermalStatusListener(listener)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
