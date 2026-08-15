package com.authorss81.noteflow.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * B2-LOG-01 (phase-48): startup EVENT timing logger only. It must NEVER install
 * an uncaught-exception handler nor dump stack traces — the raw trace of a
 * crash embeds app-private paths (vault layout, note-title filenames) and was
 * written VERBATIM to logcat, defeating the app's privacy-first crash reporter.
 * PrivacyCrashReporter is the SOLE owner of uncaught-exception logging.
 */
object AppStartupLogger {

    private const val TAG = "AppStartupLogger"
    private const val LOG_FILE_NAME = "app_startup.log"
    private var isInitialized = false

    // Phase 08: startup event logging must not do file I/O on the main thread
    // (cold-start on a low-end device). Event writes go through a single daemon
    // executor.
    private val logWriterExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AppStartupLogger").apply { isDaemon = true }
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val appContext = context.applicationContext
        logEvent(appContext, "AppStartupLogger initialized")
    }

    fun logEvent(context: Context, event: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val logLine = "[$timestamp] EVENT: $event\n"
        Log.i(TAG, logLine.trim())
        val appContext = context.applicationContext
        logWriterExecutor.execute {
            appendToFile(appContext, logLine)
        }
    }

    private fun appendToFile(context: Context, text: String) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            FileWriter(logFile, true).use { fileWriter ->
                fileWriter.append(text)
            }
        } catch (e: Exception) {
            // Never pass the exception (its stack can embed app-private paths) to logcat.
            Log.e(TAG, "Failed to write log to file")
        }
    }

    fun getLogs(context: Context): String {
        return try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists()) {
                logFile.readText()
            } else {
                "No logs available."
            }
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            // Never pass the exception to logcat (see appendToFile).
            Log.e(TAG, "Failed to clear logs")
        }
    }
}