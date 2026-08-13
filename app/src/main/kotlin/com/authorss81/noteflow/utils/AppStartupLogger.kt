package com.authorss81.noteflow.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AppStartupLogger {

    private const val TAG = "AppStartupLogger"
    private const val LOG_FILE_NAME = "app_startup.log"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInitialized = false

    // Phase 08: startup event logging must not do file I/O on the main thread
    // (cold-start on a low-end device). Event writes go through a single
    // daemon executor; crash logs stay synchronous so they survive the dying
    // process.
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

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logCrash(appContext, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
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

    private fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val writer = java.io.StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val stackTrace = writer.toString()

        val logBlock = """
            |==================================================
            |CRASH DETECTED
            |Time: $timestamp
            |Thread: ${thread.name} (${thread.id})
            |Exception: ${throwable.javaClass.name}
            |Message: ${throwable.message}
            |
            |StackTrace:
            |$stackTrace
            |==================================================
            |
        """.trimMargin()

        Log.e(TAG, logBlock)
        appendToFile(context, logBlock)
    }

    private fun appendToFile(context: Context, text: String) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            FileWriter(logFile, true).use { fileWriter ->
                fileWriter.append(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
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
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
}
