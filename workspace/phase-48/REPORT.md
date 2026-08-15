# Phase 48 — B2-LOG-01 (HIGH): AppStartupLogger's uncaught-exception handler dumps the raw, unsanitized crash trace to logcat — FIXED

- **Date:** 2026-08-15
- **Finding:** `B2-LOG-01` — *AppStartupLogger's uncaught-exception handler dumps the RAW, unsanitized stack trace to logcat AND `app_startup.log`, and because it is registered after PrivacyCrashReporter it runs FIRST — the entire "privacy-first sanitized crash reporting" guarantee is dead on arrival* (HIGH)
- **Scope:** one finding per phase (tight diff). No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched. Fix is API-floor-neutral (API 26+): the change is a pure removal + a pure-JVM helper extraction, no `Build.VERSION` branch is needed and no device/API fallback is required.

## Root cause (before)

1. `AppStartupLogger.init` (`AppStartupLogger.kt:38-42`, before) captured the previous default handler and installed its own via `Thread.setDefaultUncaughtExceptionHandler`. Because `MainActivity.kt:85-86` initializes PrivacyCrashReporter FIRST and AppStartupLogger SECOND, AppStartupLogger's handler captured PrivacyCrashReporter's and became the OUTERMOST — running first on every uncaught exception.
2. `AppStartupLogger.logCrash` (`AppStartupLogger.kt:55-77`, before) ran `throwable.printStackTrace(PrintWriter(StringWriter()))` — the FULL trace including every exception message — then `Log.e(TAG, logBlock)` (`:75`) VERBATIM to logcat and `appendToFile` (`:76`) also verbatim.
3. Contrast `PrivacyCrashReporter.kt:48-57` (before): its sanitized entry went to the local file only, never logcat, and it NEVER ran first. So a crash embedding an app-private path (e.g. `FileNotFoundException` on `/data/user/0/com.aistudio.inkflow.app.bkxjrz/files/noteflow/imports/<note_title>_<ts>.md`) was written unredacted to logcat for any `adb logcat` / `dumpstate` reader.

## What changed (after) — `file:line`

### 1. AppStartupLogger is a startup-*event* timer only — crash machinery removed — `utils/AppStartupLogger.kt`

- **`init` (`AppStartupLogger.kt:35-41`)**: no longer touches `Thread.getDefaultUncaughtExceptionHandler()` / `Thread.setDefaultUncaughtExceptionHandler(...)`. The `defaultHandler` field is deleted. `init` keeps only the startup-event log line.
- **`logCrash` + the raw-dump block (`:55-77` before) DELETED** — `printStackTrace`/`StringWriter`/`PrintWriter` imports removed, so there is no code path in the file that can render a raw trace.
- **`appendToFile` catch (`:59-62`)** and **`clearLogs` catch (`:84-87`)**: `Log.e(TAG, msg, e)` → `Log.e(TAG, msg)` (fixed message, exception object dropped) so even a log-file failure never prints a throwable's trace to logcat.
- Remaining logcat emission is `logEvent`'s `Log.i(TAG, "[timestamp] EVENT: <static startup event>")` (`:46`) whose callers are `init` ("AppStartupLogger initialized") and `MainActivity.kt:87` ("MainActivity.onCreate started") — no note/path content.

### 2. PrivacyCrashReporter is the SOLE uncaught-exception owner with a single sanitized entry format — `services/PrivacyCrashReporter.kt`

- **`logUncaughtException` (`:53-55`)** now just `writeLogToFile(context, crashLogEntry(thread.name, throwable))` — still NO logcat write (unchanged intent, now explicit).
- **New pure-JVM `crashLogEntry(threadName, throwable, maxFrames = 20, now) ` (`:64-71`)**: the ONLY crash-entry format in the app. Message runs through the existing `sanitizeMessage` (`:85-92`) and the stack is scrubbed down to `class.method(file:line)` frames (no raw `printStackTrace` form, no cause chain, no embedded messages).
- KDoc (`:11-19`) documents the single-owner invariant.

### 3. Net effect

- Grep of `app/src/main` now finds `setDefaultUncaughtExceptionHandler` in exactly ONE file (`PrivacyCrashReporter.kt`). There is no AppStartupLogger path into logcat that can carry a raw crash trace.
- `PrivacyCrashReporter.recordException` (`:39-51`, the in-app manual path) still emits the sanitized entry to logcat — unchanged, it was already the finding's sanctioned "logcat-safe output".

## Checksum / secrets handling

- No keys, passwords, salted hashes, or decrypted note content are touched by this diff.
- Crash messages/surfaces now route exclusively through `sanitizeMessage` (hash blob → `[HASH_REDACTED]`, `password=` → `password=[REDACTED]`, app-private path → `[PATH_REDACTED]`).
- No new `INTERNET` usage, no new permissions, `allowBackup="false"`, ClipboardGuard and FLAG_SECURE untouched.

## Verification

- **`gradle testDebugUnitTest`** — `BUILD SUCCESSFUL`, **1020 tests, 0 failures, 0 errors** (was 1013; +7 new `B2Log01CrashReportingTest` incl. the B1-PLAT-5 redaction case).
- **`gradle assembleDebug`** — `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` produced (173.6 MB). (First invocation reported a transient FAILURE with every subsequent re-invocation fully `UP-TO-DATE` green and the APK on disk — a daemon/task-environment hiccup, not a task failure; no task ever recorded a failure.)
- **New/updated pure-JVM tests** — `app/src/test/java/com/authorss81/noteflow/B2Log01CrashReportingTest.kt` (7 tests):
  1. `AppStartupLogger installs no uncaught-exception handler anymore` — source pin: no `get/setDefaultUncaughtExceptionHandler`, no `defaultHandler`, `logCrash`, `printStackTrace`, `StringWriter`, `PrintWriter`.
  2. `AppStartupLogger never passes a throwable to logcat` — source pin: no `, e)` third-argument `Log.e` form.
  3. `crash entry sanitizes app-private paths and the raw trace` — synthetic `FileNotFoundException("/data/user/0/com.authorss81.noteflow/files/noteflow/imports/Cancer-Treatment-Plan_1724567890.md")` → entry has `[PATH_REDACTED]`, no raw path, no note-title filename.
  4. `crash entry redacts the real runtime applicationId data dir too` — same assertion against the live appId path `/data/user/0/com.aistudio.inkflow.app.bkxjrz/files/...` (closes B1-PLAT-5's regex gap, see below).
  5. `crash entry scrubs the trace - no printStackTrace form, no cause chain` — throwable with a cause → entry has no `Caused by`, no cause message, no second filename; scrubbed `at class.method(file:line)` frames present.
  6. `uncaught path persists a sanitized entry without touching logcat` — source pin: `logUncaughtException` routes through `crashLogEntry` and contains no `Log.`.
  7. `PrivacyCrashReporter is the only uncaught-exception handler in the app` — scans every `.kt` under `app/src/main`; only `PrivacyCrashReporter.kt` may call `setDefaultUncaughtExceptionHandler`.

## Out-of-scope (documented, NOT fixed here)

- **Original B1-PLAT-5 note (now CLOSED here, not deferred):** the sanitizer's path regex was namespace-only. **Review fix applied in this phase:** `PrivacyCrashReporter.sanitizeMessage` now redacts ANY app-private data path — `/data/user/<uid>/...` (modern) and `/data/data/...` (legacy alias) — which covers both the namespace and the real applicationId dir `com.aistudio.inkflow.app.bkxjrz`. Test 3.4/4 pins both forms. `docs/security-report.md` B1-PLAT-5 row and `workspace/phase-89/PROMPT.md` are updated to reflect that the finding is fixed ahead of its planned phase.
- **B2-LOG-02 (phase-70):** `app_startup.log` still appends without a size cap/rotation. This diff partially helps (crash blocks are no longer appended), but the cap/rotation is a separate MEDIUM finding with its own phase.
- **B2-LOG-03 (phase-70/other):** `ImportExportService` `Log.e/w(..., e)` paths — separate finding, untouched.
- First-invocation transient `assembleDebug` failure: not reproducible (subsequent run fully green, all tasks `UP-TO-DATE`, APK present).