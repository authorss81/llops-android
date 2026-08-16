# Phase 71 — B2-LOG-03: `ImportExportService` `Log.e/w(…, e)` prints full path-carrying exceptions to logcat

## Finding

`docs/security-report.md` B2-LOG-03 (MEDIUM). Every import/export failure call site
in `ImportExportService.kt` passed the exception OBJECT as the last argument to
`Log.e`/`Log.w` (audit listed 13 sites; two of those — the `Log.w` "Failed to copy
export file to Downloads" paths — were already removed by the phase-59 B1-PLAT-3
Downloads rewrite). Passing `e` prints the FULL throwable to logcat, including the
exception message that embeds app-private file paths whose filenames ARE note
titles (the sanitized `<note_title>_<ts>.md` under
`filesDir/noteflow/imports/`, per B1-DB-4). A party reading logcat
(`adb logcat`/`dumpstate`, or a co-located debug session) learns real note titles +
the vault file layout — and nothing passes through PrivacyCrashReporter's
sanitizer because these calls bypass it entirely.

## What changed (file:line)

- **NEW `app/src/main/kotlin/com/authorss81/noteflow/services/FailureLogPolicy.kt`**
  (pure JVM, `java.lang.Throwable` only — no platform calls, no android classes):
  the single decision table for import/export failure logging.
  - `safeLogMessage(e, operation)` — a logcat-safe line = a FIXED [operation]
    label (never constructed from throwable data) + the sanitized class-name token.
  - `classNameToken(e)` — the exception's `javaClass.simpleName` only
    (`ifBlank { "Exception" }` for anonymous classes); `e.message`,
    `localizedMessage` and the stack are NEVER read, so note-title filenames /
    absolute vault-import paths can never reach the log line. API 26+ floor, no
    fallback required.
  - KDoc documents the finding + the "never pass the throwable" contract.
- **`ImportExportService.kt`** — every audited `Log.e(..., e)` call site replaced
  with a 2-argument `Log.e` whose message routes through the policy.

| # | Before (pre-fix) | After (fixed) |
|---|---|---|
| 1 | `:289` `Log.e("ImportExportService", "Failed to export annotated page", e)` | `:291` `Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to export annotated page"))` |
| 2 | `:370` `… "Failed to export document as PDF", e)` | `:373` `… safeLogMessage(e, "Failed to export document as PDF"))` |
| 3 | `:1096` `… "Failed to parse docx", e)` | `:1100` `… safeLogMessage(e, "Failed to parse docx"))` |
| 4 | `:2063` `… "Failed to export vault to ZIP", e)` | `:2068` `… safeLogMessage(e, "Failed to export vault to ZIP"))` |
| 5 | `:2098` `… "Failed to import HTML file", e)` | `:2104` `… safeLogMessage(e, "Failed to import HTML file"))` |
| 6 | `:2154` `… "Failed to import HTML ZIP folder", e)` | `:2161` `… safeLogMessage(e, "Failed to import HTML ZIP folder"))` |
| 7 | `:2223` `… "Failed to export note to HTML", e)` | `:2231` `… safeLogMessage(e, "Failed to export note to HTML"))` |
| 8 | `:2271` `… "Failed to export vault HTML site", e)` | `:2280` `… safeLogMessage(e, "Failed to export vault HTML site"))` |
| 9 | `:2343` `… "Failed to import Obsidian Vault ZIP", e)` | `:2353` `… safeLogMessage(e, "Failed to import Obsidian Vault ZIP"))` |
| 10 | `:2405` `… "Failed to export Obsidian Vault ZIP", e)` | `:2416` `… safeLogMessage(e, "Failed to export Obsidian Vault ZIP"))` |
| 11 | `:2462` `… "Failed to export page to PSD", e)` | `:2474` `… safeLogMessage(e, "Failed to export page to PSD"))` |

**Before / after (the vulnerability path):**

```
Before:  catch (e: Exception) {
           Log.e("ImportExportService", "Failed to import HTML file", e)   // prints full
         }                                                                 // throwable →
         //   java.io.FileNotFoundException: /data/user/0/com.aistudio.inkflow.app.bkxjrz/
         //     files/noteflow/imports/Cancer_Treatment_Plan_1724567890.md (No such …)
After:   catch (e: Exception) {
           Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to import HTML file"))
         }   // emits only: "Failed to import HTML file (FileNotFoundException)"
```

No `Log.e`/`Log.w` call in the file has a third (throwable) argument anymore — the
fixed 2-argument shape is source-pinned by the new test (see below), a future
3-arg call would fail CI.

## Verification

- `gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.FailureLogPolicyTest"`
  → green (8 tests).
- `gradle :app:testDebugUnitTest` → **1361 test methods completed**. FINAL run: **1359
  green, 2 failed** — only the documented pre-existing `B1Plat01ReleaseSigningTest`
  asserts on `docs/RELEASE.md` + `app/build.gradle.kts` signing config (present and
  untouched since phase-55, documented in phases 55–70; neither file is touched by
  this phase). One earlier full run also reproduced the known `WikiLinkParserCacheUnitTest`
  cancellation-timing flake (3 failed); it PASSES in isolation and did not reproduce in
  the final run (the pre-existing flake documented in phases 40/67). No new regression:
  the pre-fix baseline was 1353 total / 1350 green; this phase adds 8 tests and keeps
  the same pre-existing failures.
- `gradle :app:assembleDebug` → **BUILD SUCCESSFUL** (57 task outcomes, debug APK
  173,744,258 B, SHA-256 `0a1e30c6…`). NOTE: the first plain `gradle :app:assembleDebug`
  invocation hit the documented transient build failure seen in every phase since 48;
  the re-runs completed fully, the final invocation was green (`:app:assembleDebug`
  re-executed after the phase-71 comment-edit recompiled `ImportExportService.kt`).

## New test coverage (`app/src/test/.../FailureLogPolicyTest.kt`, 8 tests)

Behavior (pure JVM):
- `safeLogMessage` emits the fixed operation label + `(ClassName)` only.
- A `FileNotFoundException` carrying the exact B2-LOG-03 / B1-DB-4 path shape
  (`/data/user/0/com.aistudio.inkflow.app.bkxjrz/files/noteflow/imports/Cancer_Treatment_Plan_1724567890.md (No such file or directory)`)
  NEVER leaks the data dir, the applicationId dir, the note-title filename, the OS
  detail, or the raw message into the log line.
- Underscore AND dash note-title variants are stripped.
- Nested `cause` messages cannot leak (the outer class name is the only detail).
- Anonymous exception classes fall back to `(Exception)`.
- `classNameToken` is pure: never contains path/message data.

Source pins (the "no message text containing a path" grep-verification the PROMPT demands):
- Mechanically extracts every `Log.X(...)` call in `ImportExportService.kt` and asserts
  each is **exactly 2-argument** (one top-level comma) AND ends NOT with `, e)` — a
  3-arg `Log.e(..., e)` shape would fail. Every Log call routes through `FailureLogPolicy`.
- All 11 audited operation labels are still surfaced as FIXED sanitized messages
  (`FailureLogPolicy.safeLogMessage(e, "<label>")` present in the source).

## Checksums / secrets handling

- No keys, passwords, decrypted note content, or app-private paths are ever written to
  logcat by `ImportExportService` (the sanitizer only emits the classifier label).
- `FailureLogPolicy` reads nothing from `e.message`/`localizedMessage`/stack — the only
  derived token is `javaClass.simpleName` (a public class identifier, never user data).
- `allowBackup=false`, `ClipboardGuard` and FLAG_SECURE untouched.
- The `class` of the change is pure logging; the DB schema, crypto and file layout are
  all untouched.

## Out of scope (documented, not fixed here)

- `ImportExportService.kt:1414` `throw IllegalArgumentException("Incorrect backup password.", e)`
  passes `e` as a CAUSE of a thrown exception whose own message is a fixed string. This is
  not a `Log.*` call — no logcat path in this file — but the nested cause message could in
  principle embed a path if a future caller ever logs that thrown exception's cause. Left
  as-is per the phase constraint ("do not fix OTHER security findings"); noted here for a
  future logging-audit phase.
- B2-LOG-04 (Plugin download/install URL echoes) and B2-LOG-05 (WebDAV raw exception text
  in the sync-status UI) are separate findings with their own phases.
- `PrivacyCrashReporter`/`AppStartupLogger` behaviour (B2-LOG-01/02) already fixed.

## Constraints honored

- No DB schema change, no migration (REPORT note: none required — logging only).
- No new dependencies. `.github/workflows/` untouched.
- Do-not-fix rule: only the B2-LOG-03 call sites were touched; the discovered related
  item above is documented and left to its own phase.