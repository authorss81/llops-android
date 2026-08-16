package com.authorss81.noteflow.services

/**
 * B2-LOG-03 (phase-71): import/export failure logging may only ever emit the
 * exception CLASS NAME — never the exception object. Passing `e` as the last
 * argument to `Log.e`/`Log.w` prints the FULL throwable, whose message text
 * embeds app-private file paths (the sanitized note-title filename under
 * `filesDir/noteflow/imports/<safeName>`, per B1-DB-4) straight into logcat,
 * bypassing PrivacyCrashReporter's sanitizer entirely.
 *
 * Pure-JVM decision table: the only data derived from a failure here is its
 * simple class name; `e.message`, `localizedMessage` and the stack trace are
 * never read. Every `Log.e("ImportExportService", ...)` call site in
 * `services/ImportExportService.kt` routes its failure text through
 * [safeLogMessage] so a path-carrying exception message can never reach the log
 * line. API 26+ floor, no platform calls, unit-testable on the plain JVM.
 */
object FailureLogPolicy {

    /**
     * Builds the logcat-safe line for a failed import/export operation: a FIXED
     * [operation] label (never constructed from throwable data) plus the
     * sanitized exception class-name token.
     */
    fun safeLogMessage(e: Throwable, operation: String): String =
        "$operation (${classNameToken(e)})"

    /**
     * The sanitized token: the exception's simple class name only. Deliberately
     * never `e.message` — note-title filenames, absolute vault/import paths and
     * user data ride inside message text, which is exactly what B2-LOG-03 leaks.
     */
    fun classNameToken(e: Throwable): String =
        e.javaClass.simpleName.ifBlank { "Exception" }
}