# Phase 89 — B1-PLAT-5: PrivacyCrashReporter path-redaction regex (VERIFY-ONLY)

> **Status: `DONE` — no code change required.** B1-PLAT-5 (LOW) is already closed.
> The finding was fixed during the phase-48 review pass (2026-08-15) and is pinned
> by `B2Log01CrashReportingTest`. This phase ran the existing verification and
> confirmed the finding stays closed; per the PROMPT's own "SUPERSEDED" note, no
> code edit was made and the hardcoded-namespace regex was NOT re-introduced.

## 1. Source finding (recap)

- **B1-PLAT-5** (LOW, `docs/security-report.md:345`): `PrivacyCrashReporter.sanitizeMessage`
  built its redaction regex from the hardcoded *namespace*
  (`/data/user/\d+/com\.authorss81\.noteflow/\S+`), while the real runtime data dir uses the
  *applicationId* (`/data/user/0/com.aistudio.inkflow.app.bkxjrz/`,
  `app/build.gradle.kts:15`). The regex therefore never matched any real path and crash-log
  messages carried app-private paths (SQLCipher DB name, vault layout, imports/exports dirs)
  into `noteflow_sanitized_crash.log`.

## 2. Fix already in place (before/after evidence)

**Before (the finding):** `PrivacyCrashReporter.kt:77` — a hardcoded
`/data/user/\d+/com\.authorss81\.noteflow/\S+` redaction only.

**After (committed in the phase-48 review pass, `workspace/phase-48/REPORT.md`):**
`app/src/main/kotlin/com/authorss81/noteflow/services/PrivacyCrashReporter.kt:85-96`
`sanitizeMessage` now masks ANY app-private data path structurally, covering BOTH the
namespace and the real applicationId dir:

```kotlin
:94  .replace(Regex("/data/user/\\d+/\\S+"), "[PATH_REDACTED]")
:95  .replace(Regex("/data/data/\\S+"), "[PATH_REDACTED]")
```

- `/data/user/\d+/…` matches the modern device layout for ANY uid and ANY package/app id —
  both `com.authorss81.noteflow` and `com.aistudio.inkflow.app.bkxjrz`.
- `/data/data/…` matches the legacy alias.
- The namespace-specific literal (`com\.authorss81\.noteflow`) is gone; nothing hardcoded to
  a package name remains in the redaction path. This is deliberately NOT `context.packageName`-
  keyed regex building (the PROMPT's suggested approach) because the regex-based generic
  approach is simpler AND provably covers every package-name variant — a runtime-derived
  package regex would be another place where a package-name literal could go stale. The
  generic rules in the tree supersede the PROMPT's suggested implementation while satisfying
  its exact verification criteria (see §3). No OS/API-floor concern: pure `Regex`, no newer
  API required, API 26+ (no fallback needed — AGENTS.md hardware-reality satisfied).

## 3. Verification

### 3.1 Pin test (B1-PLAT-5 behavior)

`app/src/test/java/com/authorss81/noteflow/B2Log01CrashReportingTest.kt`:

- `:68-78` `crash entry sanitizes app-private paths and the raw trace` — namespace-form path
  (`/data/user/0/com.authorss81.noteflow/files/noteflow/imports/Cancer-Treatment-Plan_1724567890.md`)
  fully redacted → `[PATH_REDACTED]`; no `/data/user/0/`, no note-title filename.
- `:80-90` `crash entry redacts the real runtime applicationId data dir too` — real-path form
  (`/data/user/0/com.aistudio.inkflow.app.bkxjrz/files/noteflow/imports/Surgery_Checklist_1724567890.md`)
  fully redacted; the exact `com.aistudio.inkflow.app.bkxjrz` string must not appear.

Both mirror the finding's exploit scenario with the actual applicationId the device runs under.

### 3.2 Sources inspected (file:line)

- `app/src/main/kotlin/com/authorss81/noteflow/services/PrivacyCrashReporter.kt:85-96`
  (`sanitizeMessage`) — generic `/data/user/\d+/` + `/data/data/` redaction confirmed live.
- `app/src/main/kotlin/com/authorss81/noteflow/services/PrivacyCrashReporter.kt:64-71`
  (`crashLogEntry`) — every crash entry passes through `sanitizeMessage`; uncaught path
  `:53-55` writes the file copy only.
- `app/build.gradle.kts:15` — applicationId `com.aistudio.inkflow.app.bkxjrz` (the real dir).
- `docs/security-report.md:894` — B1-PLAT-5 row already `FIXED 2026-08-15`, i.e. annotated
  at (and by) the phase-48 review fix, before this verify-only phase ran (2026-08-16). This
  phase only re-confirmed that pre-existing closure; it did not change the row.

### 3.3 Command outcomes

1. `gradle testDebugUnitTest` (first full run): **1561 tests, 1 failed** —
   `WikiLinkParserCacheUnitTest` `a cancelled scan propagates cancellation…` — the known,
   documented pre-existing cancellation-timing flake (first flagged in phase-67, re-documented
   in 70/74/76/79/80/81/86/88; unrelated to this verify-only phase, which changed zero code).
2. `gradle :app:testDebugUnitTest --tests B2Log01CrashReportingTest --tests WikiLinkParserCacheUnitTest`
   (isolation): **BUILD SUCCESSFUL** — both the B1-PLAT-5 pin class (7 tests incl. the two
   path-redaction tests) AND the flaky class pass in isolation, proving the flake classifies
   as environment/timing, not a regression.
3. `gradle testDebugUnitTest` (re-run): **BUILD SUCCESSFUL** — 1561 tests green, the flake
   did not reproduce.
4. `gradle assembleDebug`: first invocation hit an unreproducible transient build failure
   (no code in this diff to break; the re-run completed fully). Re-run + fresh invocation:
   **BUILD SUCCESSFUL**, 57/57 `/ 90/90` tasks. APK on disk:
   `app/build/outputs/apk/debug/app-debug.apk` = 173,786,602 bytes,
   SHA-256 `1a076fe6db7d2e68f4169c50c70ab4f2d3156a64be3b7dd6f85789e35611ab9b`.

## 4. Out of scope (documented, not fixed here)

- `docs/security-report.md` B1-PLAT-6 — aligning `namespace` to the applicationId is a MAJOR
  architectural change (ROADMAP 21.10, requires explicit user approval per AGENTS.md); its
  only runtime consequence (the hardcoded regex) is already fixed by the generic redaction.
- B2-LOG-01's logcat-copy surface (`recordException` still `Log.e`s a sanitized line — by
  design; the raw trace never reaches logcat, and B2-LOG-01 is a separate finding, phase-48).
- `recordException`'s 500 KB log truncation is coarse (drop-and-recreate); proper cap +
  rotation is the B2-LOG-02 scope (phase-70).
- Already beyond B1-PLAT-5's scope (internal data-dir redaction only): the two generic rules
  do NOT cover the external-storage app dir `/storage/emulated/0/Android/data/<applicationId>/...`
  (e.g. blob/voice-note paths), so a crash message referencing that tree is not masked. Not
  fixed here (not part of the finding); documented for a future hardening phase if desired.

## 5. Checksum / secrets handling

- No secrets touched. `sanitizeMessage` redaction order unchanged for hashes/passwords
  (`:89-90`); this phase only re-confirms the path rules. No new permissions, no new deps,
  no `.github/workflows/` edits, no DB schema change, `allowBackup=false`/FLAG_SECURE intact.
- Working-tree diff for this phase: `workspace/phase-89/REPORT.md` plus the two docs files
  updated alongside it (`docs/ARCHITECTURE.md`, `docs/phase-status.md`) — all of it
  `8145588` — and the runner's own `logs/phase-89.*` artifacts (`037a00b`). No production
  code changed.
</content>