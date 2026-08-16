package com.authorss81.noteflow.utils

import android.content.Context
import android.util.Log
import com.authorss81.noteflow.services.StartupLogPolicy
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
 *
 * B2-LOG-02 (phase-70): the file is capped, rotated and pruned via the pure-JVM
 * [StartupLogPolicy]. The append gate runs BEFORE writing so an event line is
 * never split across a rotation and the active file can never grow past the cap;
 * prune-on-init clears any leftover over-budget file. Event lines are timestamps
 * + fixed strings only — the file never carries note content, and the raw
 * crash-dump path (removed in phase-48) excluded raw stack traces by
 * construction. The dead getLogs/clearLogs accessors are removed; if a
 * "export/share logs" UI ever ships, log text must be sanitized before leaving
 * the device (see StartupLogPolicy KDoc).
 */
object AppStartupLogger {

    private const val TAG = "AppStartupLogger"

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
        // B2-LOG-02: prune on init — bounded retention even if an earlier process
        // died mid-rotation. Runs on the background executor, never the main thread.
        logWriterExecutor.execute {
            StartupLogPolicy.pruneOnInit(appContext.filesDir)
        }
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
            val logFile = StartupLogPolicy.activeFile(context.filesDir)
            val incomingBytes = text.toByteArray(Charsets.UTF_8).size.toLong()
            // B2-LOG-02: rotate-on-size BEFORE the write — the active file never
            // grows past StartupLogPolicy.MAX_LOG_BYTES and a single event line is
            // never split across a rotation boundary.
            if (logFile.exists() && StartupLogPolicy.wouldExceedCap(logFile.length(), incomingBytes)) {
                StartupLogPolicy.rotateForAppend(context.filesDir)
            }
            FileWriter(logFile, true).use { fileWriter ->
                fileWriter.append(text)
            }
        } catch (e: Exception) {
            // Never pass the exception (its stack can embed app-private paths) to logcat.
            Log.e(TAG, "Failed to write log to file")
        }
    }
}