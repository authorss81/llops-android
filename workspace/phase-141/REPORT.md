# Phase 141 — Export/share hygiene: staging deleted on every outcome, chooser-gated share, no note-title metadata

**Status: DONE (2026-08-18)**

Closes three OPEN findings from `docs/security-report-round2.md`:

- `R2-B1P-02` (LOW) — a CANCELLED whole-vault PLAINTEXT export leaves the entire
  decrypted vault in app cache; the failed-write path deletes the staging copy instead
- `R2-B1P-03` (LOW) — Export Engine launch uses `ACTION_SEND` WITHOUT a chooser;
  plaintext note files linger in the FileProvider-grantable cache root
- `R2-b2b3-LOG-04` (LOW) — Export Engine publishes the note title as `EXTRA_SUBJECT`
  (metadata echo into every share target + Android share-history)

No DB schema change, no new dependencies, `.github/workflows/` untouched.

---

## R2-B1P-02 (LOW) — staging cleanup on every picker outcome

### Before (root cause)

`ui/components/SaFExporter.kt:75-97` — the picker callback handled the staging file
only in one branch:

```kotlin
if (result.resultCode == Activity.RESULT_OK) {
    val uri = result.data?.data
    if (uri != null) {
        scope.launch {
            val ok = ... // SAF copy
            runCatching { file.delete() }   // :88 — deleted EVEN when ok == false
            done?.invoke(ok)
        }
    } else {
        done?.invoke(false)                 // :91-93 — no-data, file untouched
    }
} else {
    done?.invoke(false)                     // :94-97 — cancel, file untouched
}
```

So a cancelled whole-vault PLAINTEXT export (Obsidian / HTML-site / vault zips staged
under `cacheDir/vault_exports`, `html_exports`, `html_vault_exports`, `obsidian_exports`)
accumulated decrypted content until the system purged cache — and a transient I/O error on
a *confirmed* destination silently destroyed the export the user did generate.

### After

New pure-JVM decision table `services/ExportStagingPolicy.kt` (`cleanupAfterSaF`,
`:46-56`) owns the every-outcome contract:

| outcome | `destinationUriPresent` | `copySucceeded` | verdict |
|---|---|---|---|
| delivered (ok) | true | `true` | **DELETE** (transfer-then-delete) |
| delivered, copy FAILED | true | `false` | **KEEP** (never destroy a fresh export) |
| RESULT_OK + uri, no copy ran (unreachable) | true | `null` | KEEP (conservative) |
| RESULT_OK, no URI (no-data) | false | `null` | **DELETE** |
| NOT RESULT_OK (cancel/dismiss) | any | `null` | **DELETE** |

`SaFExporter.kt:78-104` now routes EVERY picker result through the policy inside one
branched callback (`ExportStagingPolicy.cleanupAfterSaF(...)`, `:92-102`) and deletes the
staging file only on a `DELETE` verdict (`:105`). The plaintext-warning consent dialog's
dismiss-always (`:118`) AND explicit Cancel (`:152`) also delete the staged decrypted file.

### Before → after (file:line)

| | before | after |
|---|---|---|
| SUCCESS delete | `SaFExporter.kt:88` `runCatching { file.delete() }` unconditional | `SaFExporter.kt:96-105` — only when `ExportStagingPolicy.cleanupAfterSaF(...) == DELETE` |
| cancel/no-data | `SaFExporter.kt:91-97` `done(false)` with the file untouched | `SaFExporter.kt:92-102` policy decides DELETE → `:105` `runCatching { file.delete() }` |
| copy-failed | `SaFExporter.kt:88` deleted anyway | `SaFExporter.kt:51-52` (`ExportStagingPolicy.kt`) → KEEP |
| consent-dialog dismiss / Cancel | staged file untouched | `SaFExporter.kt:118` + `:152` delete the staged (decrypted) file |

## R2-B1P-03 (LOW) — chooser-gated Export Engine share + delete-after-dismiss

### Before (root cause)

`EditorScreen.kt:1383-1387` (pre-fix) took the raw `ACTION_SEND` from
`ExportShareHelper.shareFile` and called `context.startActivity(shareIntent)` directly —
no `Intent.createChooser`. A device default handler for the MIME auto-received the
decrypted note with a read grant and no per-send confirmation; a dismissed share sheet
left the plaintext export in `cacheDir/exports`, the FileProvider-grantable root
(`file_paths.xml:11`).

### After

- `plugins/export/ExportEnginePlugin.kt:58-91` — `ExportShareHelper.shareFile` → renamed
  `chooserForExport(context, file, mime)`: builds the `ACTION_SEND`
  (`FLAG_GRANT_READ_URI_PERMISSION` + FileProvider URI on the inner intent) and ALWAYS
  wraps it in `Intent.createChooser(send, CHOOSER_TITLE)`, mirroring the grant flag on the
  chooser. Target is always user-chosen.
- `ui/screens/EditorScreen.kt:203-209` — `pendingExportFile` + an
  `ActivityResultContracts.StartActivityForResult` launcher; the callback deletes the
  plaintext staging file once the chooser dismisses (share delivered OR dismissed —
  transfer-then-delete, as in SaFExporter).
- `EditorScreen.kt:1487-1503` — the overflow-menu "Share via Export Engine…" sets
  `pendingExportFile`, launches `exportShareLauncher.launch(chooser)`; a no-receiver launch
  exception deletes the file immediately and shows a snackbar (no plaintext survives in the
  grantable root).

### Before → after (file:line)

| | before | after |
|---|---|---|
| intent | `ExportShareHelper.shareFile` raw `ACTION_SEND` (`ExportEnginePlugin.kt:60-72`) | `chooserForExport` → `Intent.createChooser(send, CHOOSER_TITLE)` (`ExportEnginePlugin.kt:87`) |
| launch | `EditorScreen.kt:1478` `context.startActivity(shareIntent)` | `EditorScreen.kt:1492` `exportShareLauncher.launch(chooser)` |
| cleanup | none (file lingered in `cacheDir/exports`) | `EditorScreen.kt:207` launcher-callback delete + `:1497` launch-failure delete |

## R2-b2b3-LOG-04 (LOW) — no note-title metadata in the share subject

### Before (root cause)

`ExportEnginePlugin.kt:69` — `putExtra(Intent.EXTRA_SUBJECT, file.name)` where
`file.name = "${sanitizeBaseName(title)}.$ext"` (`ExportPayloadAssembler.kt:95`): the note
title was echoed into every share target and Android's share-history/notifications.

### After

- `ExportEnginePlugin.kt:67` — `SHARE_SUBJECT = "Exported note"` (generic; never the note
  title or filename-derived subject); `:81` `putExtra(Intent.EXTRA_SUBJECT, SHARE_SUBJECT)`
  is now the ONLY `EXTRA_SUBJECT` writer in production code (source-pinned).
- The shared FILE NAME still derives from the title (`ExportPayloadAssembler.kt:95`) — that
  is the export's content filename by design (the user sees it in the chooser), not share
  metadata; documented as accepted.

### Before → after (file:line)

| | before | after |
|---|---|---|
| subject | `putExtra(Intent.EXTRA_SUBJECT, file.name)` (`ExportEnginePlugin.kt:69`) | `putExtra(Intent.EXTRA_SUBJECT, SHARE_SUBJECT)` (`ExportEnginePlugin.kt:81`), `SHARE_SUBJECT = "Exported note"` (`:67`) |

---

## Verification results

New tests (all pure-JVM / comment-stripped source pins against real files):

- `ExportStagingPolicyTest` (8) — the decision table through the exact fake-ActivityResult
  seam `SaFExporter` wires: delivered-ok → DELETE, delivered-copy-failed → KEEP,
  RESULT_OK+uri+no-copy → KEEP, cancel → DELETE (incl. stray-uri cancel), no-data →
  DELETE, plus a full cartesian sweep matching the documented every-outcome contract.
- `Phase141ExportHygieneTest` (7) — source pins: SaFExporter routes every outcome through
  `ExportStagingPolicy.cleanupAfterSaF(` and deletes only on a DELETE verdict
  (`runCatching { file.delete() }` under `== ExportStagingPolicy.Cleanup.DELETE`); the
  consent-dialog dismiss AND Cancel delete the staged plaintext file;
  `Intent.createChooser(send, CHOOSER_TITLE)` present in `ExportEnginePlugin.kt`;
  EditorScreen uses `exportShareLauncher.launch(chooser)` (no
  `context.startActivity(shareIntent)`, no `shareFile(`); subject is the generic constant and
  `EXTRA_SUBJECT, file.name` is banned repo-wide (the constant is the ONLY EXTRA_SUBJECT
  writer). Existing `B1Plat03ExportConsentTest` (SaFExporter `file.delete()` pin) and
  `ExportPayloadAssemblerTest` still green.

**Review fixes (2026-08-18, see git log "phase-141 review fixes"):**
- Rotation/process-death retention closed on BOTH new flows: `SaFExporter`'s
  `pendingRequest`/`pendingWarningKind` and `EditorScreen`'s `pendingExportFilePath` are
  `rememberSaveable` (path-backed) so a recreation mid-picker/mid-chooser still resolves the
  pending staging cleanup; the launcher callback deletes the share file after the recreated
  composition restores it, and the SAF picker keeps its every-outcome cleanup contract.
- The `(Boolean) -> Unit` export callback is replaced by the 3-way `SaFExportResult`
  (SAVED / CANCELLED / FAILED) so a copy-failure on a confirmed destination is no longer
  reported to the user as a cancel ("Export to the chosen destination failed" etc.);
  PSD layer-capped notice fires only on SAVED.
- `chooserForExport(...)` moved inside the try so a chooser-build failure also deletes the
  staging file (it previously could leak a fresh export on a FileProvider throw).
- REPORT/docs test-count typo fixed: `ExportStagingPolicyTest` (8) + `Phase141ExportHygieneTest` (7).

Commands (Linux/CI, system `gradle`, no wrapper):

```
gradle :app:compileDebugKotlin    # BUILD SUCCESSFUL (first pass: nested-comment fix in ExportStagingPolicy.kt KDoc)
gradle testDebugUnitTest          # 1978 total (app 1928 + plugins:llm 50), 0 fail / 0 err / 0 skip
gradle assembleDebug              # BUILD SUCCESSFUL
```

Debug artifact: `app/build/outputs/apk/debug/app-debug.apk`
(173,976,410 bytes; SHA-256 `3b93f5925f4e6d92f52d808555c1f404ff7547210a0259d03f6a59e59980fd7e`).
Release build intentionally NOT run locally: `RELEASE_KEYSTORE_B64`/`KEYSTORE_FILE` unset and
the release build fails closed when unset (B1-PLAT-1, `docs/RELEASE.md`).

## Definition of done

- [x] R2-B1P-02 closed — `ExportStagingPolicy.kt` every-outcome table; `SaFExporter.kt`
      routes every picker outcome (ok / ok-but-copy-failed / cancel / no-data) through it;
      consent-dialog dismiss + Cancel delete the staged file; no decrypted staging survives
      a cancel/no-data path (`workspace/phase-141` REPORT + policy/tests).
- [x] R2-B1P-03 closed — Export Engine share always `Intent.createChooser(...)`, launched
      via an ActivityResult launcher; the plaintext staging file is deleted when the
      chooser dismisses and on a no-receiver launch failure (transfer-then-delete).
- [x] R2-b2b3-LOG-04 closed — `EXTRA_SUBJECT` is the generic "Exported note", never the
      note title / filename; grep-pinned as the only writer.

## Constraints

- [x] No DB schema change; no new dependencies; `.github/workflows/` untouched.
- [x] No keys/passwords/decrypted note content logged anywhere.
- [x] `allowBackup="false"` untouched.

## Residual notes (documented, NOT fixed here)

- **Delete-on-dismiss race with the target's read (unavoidable with transfer-then-delete on
  a URI grant):** the launcher callback deletes the export as soon as the chooser dismisses
  (for `createChooser` + `StartActivityForResult` this is typically right after the user
  picks a target, usually before the target app cold-starts and reads the content URI).
  `SaFExporter`'s delete is NOT equivalent: there the bytes are confirmed written to the
  user-picked destination *before* `file.delete()`. On this share path there is NO delivery
  confirmation — we delete on chooser-return and hope the target already read. Slow-cold-start
  or lazy-reading targets can lose the file mid-read. This is the transfer-then-delete
  trade-off the finding explicitly requests ("delete the export file once the share is
  delivered/dismissed"), the note export is a single small file, and any alternative
  (time-of-return vs actual-read coordination) is not achievable with a one-shot grant.
  Kept and documented accurately.
- **File name still derives from the title** (`ExportPayloadAssembler.kt:95`) — that is the
  export's content filename by design, shown to the user in the chooser; the finding's
  subject/metadata echo was the *share subject*, which is now generic. The file NAME in a
  share remains title-derived (sanitized); accepted and documented in
  `docs/security-report-round2.md` LOG-04 row.
- **Optional stale-staging sweep not added** (finding marked it "optionally"): with the
  cancel/no-data deletion in place, staging only survives the intentional
  copy-failed → KEEP path, whose deterministic export names overwrite on retry; the sweep
  would add no security benefit.
- **`plugins/llm` module untouched** by the subject/chooser change (no share path there).