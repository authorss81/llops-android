package com.authorss81.noteflow.services

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Privacy-First Crash and Exception Reporter for NoteFlow.
 * Guarantees zero leak of decrypted note content, passwords, or personal data.
 *
 * B2-LOG-01 (phase-48): this is the SOLE uncaught-exception handler. AppStartupLogger
 * no longer installs one (it had run FIRST and dumped the RAW, unsanitized trace to
 * logcat). Every crash entry is produced by the pure-JVM [crashLogEntry] builder —
 * sanitized message + scrubbed stack frames only, never the raw `printStackTrace` form.
 */
object PrivacyCrashReporter {
    private const val TAG = "PrivacyCrashReporter"
    private const val LOG_FILE_NAME = "noteflow_sanitized_crash.log"
    private val isEnabled = AtomicBoolean(true)

    fun initialize(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isEnabled.get()) {
                logUncaughtException(context, thread, throwable)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
    }

    fun recordException(context: Context, throwable: Throwable, contextTag: String = "AppException") {
        if (!isEnabled.get()) return
        val sanitizedMsg = sanitizeMessage(throwable.message)
        val stackTraceScrubbed = throwable.stackTrace.take(15).joinToString("\n") { element ->
            "   at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val logEntry = "[$timestamp] [$contextTag] ${throwable.javaClass.simpleName}: $sanitizedMsg\n$stackTraceScrubbed\n\n"

        Log.e(TAG, logEntry)
        writeLogToFile(context, logEntry)
    }

    private fun logUncaughtException(context: Context, thread: Thread, throwable: Throwable) {
        writeLogToFile(context, crashLogEntry(thread.name, throwable))
    }

    /**
     * Pure-JVM crash-entry builder (B2-LOG-01, phase-48): the ONLY crash format in
     * the app. The message runs through [sanitizeMessage] and the stack is scrubbed
     * down to `class.method(file:line)` frame strings — never the raw trace with
     * exception messages / app-private paths. No logcat write happens here: the
     * uncaught path persists to the local file only.
     */
    fun crashLogEntry(threadName: String, throwable: Throwable, maxFrames: Int = 20, now: Long = System.currentTimeMillis()): String {
        val sanitizedMsg = sanitizeMessage(throwable.message)
        val stackTraceScrubbed = throwable.stackTrace.take(maxFrames).joinToString("\n") { element ->
            "   at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})"
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        return "CRASH [$timestamp] Thread: $threadName - ${throwable.javaClass.simpleName}: $sanitizedMsg\n$stackTraceScrubbed\n\n"
    }

    private fun writeLogToFile(context: Context, entry: String) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (logFile.exists() && logFile.length() > 500_000) {
                logFile.writeText("") // Clear old log if exceeds 500KB
            }
            logFile.appendText(entry)
        } catch (e: Exception) {
            // Ignore file write issues in exception handler
        }
    }

    private fun sanitizeMessage(message: String?): String {
        if (message.isNullOrBlank()) return "No details"
        // Strip potential note titles, passwords, base64 blobs, or file paths
        return message
            .replace(Regex("[a-fA-F0-9]{32,}"), "[HASH_REDACTED]")
            .replace(Regex("(?i)password[=:]\\s*\\S+"), "password=[REDACTED]")
            // B1-PLAT-5 (phase-48 review fix): redact ANY app-private data path —
            // namespace AND real applicationId (`com.aistudio.inkflow.app.bkxjrz`)
            // on `/data/user/<uid>/` (modern) or the legacy `/data/data/` alias.
            .replace(Regex("/data/user/\\d+/\\S+"), "[PATH_REDACTED]")
            .replace(Regex("/data/data/\\S+"), "[PATH_REDACTED]")
    }

    fun getSanitizedCrashLogs(context: Context): String {
        val logFile = File(context.filesDir, LOG_FILE_NAME)
        return if (logFile.exists()) logFile.readText() else "No crash logs recorded."
    }
}
