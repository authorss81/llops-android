# Phase 88 — B1-DB-8 (LOW): decrypt-failure fallbacks return RAW CIPHERTEXT as note content

## Finding (from `docs/security-report.md:302-308`)

Every decrypt-failure fallback in `NoteRepository` returned the RAW base64 AES-GCM blob as if it
were genuine note content:

| Sink | Pre-fix behaviour (finding cites, `NoteRepository.kt`) |
|---|---|
| `getStrokesForPage` | `catch (e) { rawText }` / `{ rawPointsJson }` — the ciphertext became the stroke's rendered text/geometry |
| `decryptPageIfNeeded` | whole-read `catch (e) { page }` — title/extractedText served as the raw blob |
| `getMediaEmbedsForPage` | `catch (e) { text }` — ciphertext became the embed's text |
| `getNoteVersions` | `decryptFieldOrNull(...) ?: v.title` / `?: v.extractedText` — ciphertext became the version title/body |

Exploit scenario: after a re-key, a cross-device restore with a mismatched DEK, or partial DB
manipulation, the app silently displayed base64 AES-GCM garbage as real note title/text. Because
the failure looked like legitimate content, it was never surfaced as the decrypt failure it is —
masking the tamper/re-key problem the integrity checks exist to catch.

> Note: the finding's line citations (449-457 / 609-613 / 800-807 / 810-828) are stale — the file
> has shifted since audit time. Actual pre-fix sites in the change-snapshot were `:942-960`
> (strokes), `:1198-1203` (embeds), `:1409-1417` (versions), `:1420-1438` (pages).

## Fix — what changed (file:line, before → after)

### 1. Single pure-JVM decision table — `app/src/main/kotlin/com/authorss81/noteflow/services/DecryptFailurePolicy.kt` (new)

- `UNREADABLE_MARKER` = `"Unreadable (decryption failed)"` (`:41`) — the ONLY display value a
  genuine-ciphertext auth failure may render as.
- `PERSISTENT_FAILURE_THRESHOLD` = 10 (`:50`) — DISTINCT records, never attempts (one broken row is
  read by several flows; counting attempts would false-trigger on a single corrupted note).
- `isPersistent(distinctFailedRecords)` (`:75`), `isStructuralCiphertext(value)` (`:82` — structural
  payload-shape classification via `EncryptionService.isEncryptedPayload`, content-independent), and
  `render(storedValue, decrypted, isCiphertext)` (`:91`) — the ONLY render outcome: legacy plaintext
  verbatim, authenticated plaintext, or the marker. A raw ciphertext blob can never be rendered.
- Non-alarming notices: `DECRYPT_FAILURE_NOTICE` (`:57-61`) and `PERSISTENT_DECRYPT_FAILURE_NOTICE`
  (`:66-70`). Pure JVM (java.util.Base64 + `EncryptionService`), API 26+ floor, no fallback needed.

### 2. Repository rewiring — `app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt`

- New per-session, deduped ledger + listener (after `requireEncryptionKey()` at `:56`):
  `decryptFailureRecordIds` (synchronized `LinkedHashSet<String>`, keyed `table:recordId:fieldName`,
  `:79-80`), `decryptFailureListener` (`:88`), `decryptFailuresPersistent` (`:90-91`),
  `decryptFailureRecordCount` (`:93-94`), `resetDecryptFailures()` (`:97-99`),
  `recordDecryptFailure(table, recordId, fieldName)` (`:101-106`, fires the listener exactly once per
  session on threshold-cross), `decryptFieldForDisplay(...)` (`:122-146`, the only field-decrypt
  path for display) and `decryptStoredGeometryOrBlank(...)` (`:148-158`).
- `getStrokesForPage`: text → `decryptFieldForDisplay(rawText, "strokes", entity.id, "textContent")`
  (`:947`); geometry → `decryptStoredGeometryOrBlank(rawPointsJson, entity.id)` (`:957`) — a genuine
  ciphertext that fails auth yields an EMPTY payload: `deserializeStrokes("")` = `emptyList()`
  (`EncryptionService.kt:471`), so an unreadable row draws NO phantom ink and no raw ciphertext ever
  reaches the stroke parser. The pre-fix `catch { rawText }` / `{ rawPointsJson }` fallbacks are
  deleted.
- `getMediaEmbedsForPage`: `decryptFieldForDisplay(text, "media_embeds", entity.id, "textContent")`
  (`:1203`) replaces `catch (e: Exception) { text }`.
- `getNoteVersions`: title/body via `decryptFieldForDisplay(...)` (`:1417`, `:1422`) replace
  `decryptFieldOrNull(...) ?: v.title` / `?: v.extractedText`.
- `decryptPageIfNeeded` (`:1439-1457`): the pre-fix whole-read `catch (e) { page }` that returned the
  encrypted page unchanged is gone; title/extractedText now route through `decryptFieldForDisplay`
  (`:1441`, `:1443`).
- Key design points baked into `decryptFieldForDisplay`/`decryptStoredGeometryOrBlank`:
  - a stored value that is NOT structurally ciphertext is legacy plaintext and renders verbatim —
    never "fixed" into the marker (the pre-field-encryption rows the B1-DB-4 sweep still supports
    must not become "Unreadable");
  - a genuine ciphertext that authenticates yields its plaintext;
  - a genuine ciphertext whose auth fails, or a locked-vault read with no DEK, yields the marker (or
    the empty geometry payload);
  - a locked read is RECORDED only when a DEK is actually present — a locked vault can never
    inflate the persistent ledger.

### 3. ViewModel escalation — `app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`

- `@Volatile decryptPersistenceEscalated` field (`:1286-1288`) — fires once per session.
- `initializeDataCore()` (`:1330-1347`): on every initialize, fresh ledger
  (`repository.resetDecryptFailures()` `:1336`, `decryptPersistenceEscalated = false` `:1337`) and
  the listener (`:1338-1345`): when `repository.decryptFailuresPersistent` it calls
  `DatabaseSecurityHelper.setCorruptionDetected(appContext)` + `_corruptionBlocked.value = true`
  (raises the existing `CorruptionRecoveryScreen`: restore-from-backup / re-key / start-fresh) and
  shows the non-alarming `PERSISTENT_DECRYPT_FAILURE_NOTICE` snackbar. This is precisely
  "treat persistent decryption failure as a corruption/restore event" — never silent degradation.
- Session boundaries reset the ledger + escalation so each starts from a clean count:
  `lock()` (`:3470`, immediately after `zeroizeKey()`), re-key success in `changeMasterPassword`
  (`:2576-2577`), successful WebDAV restore in `restoreEncryptedBackupFromZip` (`:3205-3206`); every
  `initializeData()` (unlock/biometrics/start-fresh) resets via `initializeDataCore`. The persisted
  corruption flag itself is only cleared by the user's explicit recovery action (existing behaviour).

### 4. Transient-vs-persistent guard (AGENTS.md "never silent degradation")

- Isolated (few) unreadable rows render the marker without blocking the vault; the failure is
  surfaced honestly per render and counted for the session.
- Only when ≥ 10 DISTINCT records fail while the DEK is provably present does the existing recovery
  flow take over (re-key mismatch / manipulated DB) — the user is offered restore/re-key/start-fresh
  instead of a vault silently degraded to markers.
- Phase-74's transient-decrypt-failure body fallback (deflate to composition snapshot) and phase-64's
  keystore-key-lost flow are untouched: they are distinct failure classes downstream/upstream of the
  DEK-presence gate in the ledger.

## Constraints honoured

- No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched.
- `allowBackup=false`, ClipboardGuard, FLAG_SECURE intact; nothing logs keys, passwords, decrypted
  content or raw ciphertext. The ledger keys are `table:recordId:fieldName` identifiers only.
- API 26+ floor: policy is pure `java.util.Base64` + existing `EncryptionService`; no fallback needed.
- The structural classifier deliberately uses `EncryptionService.isEncryptedPayload` — a raw blank or
  legacy plaintext is never treated as a payload, so genuine content can never be falsely "marked".

## Out of scope (documented, NOT touched)

- **Search-corpus decrypts** (`NoteRepository` corpus builders, `:333-389` region) already fail safe
  to `null` (records are excluded from the corpus, never displayed) — deliberately left as-is; they
  must not render markers in a filter/rank context.
- **`readMarkdownNoteBody`** transient-fallback (phase-74) and **keystore-key-lost screens**
  (B1-CRYPTO-05/phase-64): distinct failure classes, guarded by the DEK-presence gate.
- **B1-CRYPTO-06** fail-open re-baseline and the residual `main+wal` HMAC trade-offs: phase-91/phase-87.
- **Row-level recovery** for a single undecryptable note (the marker is the honest surface; the
  threshold prevents a one-note corruption from blocking the vault).
- The finding's "logging a tampered note as a normal note" angle: logs were ALREADY content-free
  (B2-LOG/B1-PLAT-5); the marker is a display-layer fix and no new logging was introduced.

## Tests

New `app/src/test/java/com/authorss81/noteflow/B1Db08DecryptFailureTest.kt` (13 tests).

Behavioral (pure JVM, REAL AES-GCM via `EncryptionService`):
- a genuine ciphertext read under a re-keyed (mismatched) DEK fails auth and `render` yields the
  marker — and the literal pre-fix outcome (`render` returning the raw blob) is asserted as the
  never-outcome;
- a locked-vault (no-DEK) read of a genuine payload renders the marker, not the blob;
- an authenticated ciphertext still renders its plaintext (no regression to the happy path);
- legacy plaintext renders verbatim and NEVER classifies as ciphertext (the reverse-regression
  guard: a pre-field-encryption row must not become "Unreadable");
- a tampered (tag-flipped) payload still classifies structurally as ciphertext so the marker
  applies, and its decrypt returns null (never the blob);
- persistent classification fires only at the 10-distinct-record threshold.

Source-level wiring pins:
- all four repository sinks route through `decryptFieldForDisplay`/`decryptStoredGeometryOrBlank`
  and the pre-fix `catch { rawText }` / `{ rawPointsJson }` / `{ text }` / `?: v.title` /
  `catch { page }` fallbacks are gone;
- the policy import is the single source of truth in the repository;
- the ViewModel wires `decryptFailureListener` at every `initializeData`, checks
  `decryptFailuresPersistent`, raises `setCorruptionDetected`, surfaces
  `PERSISTENT_DECRYPT_FAILURE_NOTICE`, and resets the ledger at lock/re-key/restore.

## Verification output

| Check | Command | Result |
|---|---|---|
| New test class in isolation (debug) | `gradle testDebugUnitTest` | BUILD SUCCESSFUL — aggregate `1561` tests, `0` failures, `0` errors (1548 pre-existing + 13 new; the historic `B1Plat01ReleaseSigningTest` 2-assert gap also green this run) |
| Full unit suite | `gradle testDebugUnitTest` | BUILD SUCCESSFUL — `1561` tests green (two B1Db08 assertions failed mid-development on stale pin anchors and were fixed in-tree; final run 0 failures) |
| Debug APK | `gradle assembleDebug` | BUILD SUCCESSFUL — `90 actionable tasks: 49 executed, 41 up-to-date` |

Test run: 2026-08-16 on the GA runner, gradle 8.13 / JDK 21.