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
- `PERSISTENT_FAILURE_THRESHOLD` = 10 (`:50`) — DISTINCT NOTES (see Review fixes below: the initial
  `table:recordId:fieldName` keying was corrected to `note:<pageId>` so a single broken note can
  never trip the threshold on its own, while a whole-vault mismatch still does).
- `isPersistent(distinctFailedRecords)` (`:75`), `isStructuralCiphertext(value)` (`:82` — structural
  payload-shape classification via `EncryptionService.isEncryptedPayload`, content-independent), and
  `render(storedValue, decrypted, isCiphertext)` (`:91`) — the ONLY render outcome: legacy plaintext
  verbatim, authenticated plaintext, or the marker. A raw ciphertext blob can never be rendered.
- Non-alarming notices: `DECRYPT_FAILURE_NOTICE` (`:57-61`) and `PERSISTENT_DECRYPT_FAILURE_NOTICE`
  (`:66-70`). Pure JVM (java.util.Base64 + `EncryptionService`), API 26+ floor, no fallback needed.

### 2. Repository rewiring — `app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt`

- New per-session, deduped ledger + listener (after `requireEncryptionKey()` at `:56`):
  `decryptFailureRecordIds` (synchronized `LinkedHashSet<String>`, keyed `note:<pageId>` — one
  entry per damaged note, `:79-80`), `decryptFailureListener` (`:88`), `decryptFailuresPersistent`
  (`:90-91`), `decryptFailureRecordCount` (`:93-94`), `resetDecryptFailures()` (`:97-99`),
  `recordDecryptFailure(noteId)` (`:101-106`, fires the listener exactly once per session on
  threshold-cross), `decryptFieldForDisplay(...)` (`:122-146`, the only field-decrypt path for
  display, note-scoped + optional `recordFailures` for non-display reads) and
  `decryptStoredGeometryOrBlank(...)` (`:148-158`).
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

- Isolated (few) unreadable notes render the marker without blocking the vault; the failure is
  surfaced honestly per render and counted for the session (one entry per damaged NOTE — a single
  broken note, however many of its rows/fields fail, can never trip the threshold on its own).
- Only when ≥ 10 DISTINCT records fail while the DEK is provably present does the existing recovery
  flow take over (re-key mismatch / manipulated DB) — the user is offered restore/re-key/start-fresh
  instead of a vault silently degraded to markers.
- Phase-74's transient-decrypt-failure body fallback (deflate to composition snapshot) and phase-64's
  keystore-key-lost flow are untouched: they are distinct failure classes downstream/upstream of the
  DEK-presence gate in the ledger.

## Constraints honoured

- No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched.
- `allowBackup=false`, ClipboardGuard, FLAG_SECURE intact; nothing logs keys, passwords, decrypted
  content or raw ciphertext. The ledger keys are `note:<pageId>` identifiers only.
- API 26+ floor: policy is pure `java.util.Base64` + existing `EncryptionService`; no fallback needed.
- The structural classifier deliberately uses `EncryptionService.isEncryptedPayload` — a raw blank or
  legacy plaintext is never treated as a payload, so genuine content can never be falsely "marked".

## Out of scope (documented, NOT touched)

- **Search-corpus decrypts** (`NoteRepository` corpus builders, `:216-235`) are **now corpus-safe**
  (phase-88 review fix): `loadSearchCorpus` maps through the new `decryptPageOrNullForCorpus`
  — an undecryptable page is DROPPED from the corpus (never a rankable "Unreadable" marker, never
  raw ciphertext) and its failure is NOT recorded against the persistent ledger there (the
  Home-list/display reads already count it once). The phase-88 initial commit wrongly cited the
  `:333-389` migration pass (`migrateFieldRecordAad`) as "the corpus" and claimed corpus reads
  already fail-safe to `null` — they did NOT (they ran through `decryptPageIfNeeded`, emitting
  markers + recording failures). Corrected by the review fix.
- **`readMarkdownNoteBody`** transient-fallback (phase-74) and **keystore-key-lost screens**
  (B1-CRYPTO-05/phase-64): distinct failure classes, guarded by the DEK-presence gate.
- **B1-CRYPTO-06** fail-open re-baseline and the residual `main+wal` HMAC trade-offs: phase-91/phase-87.
- **Row-level recovery** for a single undecryptable note (the marker is the honest surface; the
  note-granularity threshold prevents a one-note corruption from blocking the vault).
- The finding's "logging a tampered note as a normal note" angle: logs were ALREADY content-free
  (B2-LOG/B1-PLAT-5); the marker is a display-layer fix and no new logging was introduced.

## Review fixes (applied 2026-08-16)

Result of reviewing the phase-88 commit `bf5dedb`; three behavioral corrections + test hardening.

1. **Ledger key granularity — note-level, not `table:recordId:field`** (`NoteRepository.kt:79-158`).
   The initial commit keyed distinct failures as `table:recordId:fieldName`, so ONE corrupted note
   easily crossed the 10-key threshold on its own (1 page title+body + 4-6 failed strokes × 2
   fields = ≥10) — hard-blocking the ENTIRE vault behind the recovery screen for a single broken
   note, contradicting the phase's own "isolated rows render markers without blocking" guarantee.
   `recordDecryptFailure` now keys on `note:<pageId>` (all four sinks pass the containing note id:
   `getStrokesForPage` `:947/:957`, `getMediaEmbedsForPage` `:1203`,
   `getNoteVersions` `:1417/:1422`, `decryptPageIfNeeded` `:1441/:1443`), so one note counts once
   regardless of how many rows/fields fail; a whole-vault re-key/restore mismatch still trips the
   threshold (one key per broken note).
2. **`decryptPageIfNeeded` no longer passes raw ciphertext when the DEK is null** (`:1439-1451`).
   The initial commit kept an `if (key == null) return page` early-return, so the pages sink — the
   single biggest display surface — still rendered the encrypted base64 blob in the
   zeroize-before-dispose race window in `lock()`, unlike the strokes/embeds/versions sinks (which
   render the marker). The early-return is gone; title/extractedText always route through
   `decryptFieldForDisplay` (marker on failed auth, verbatim on legacy plaintext). Test now also
   pins the absence of `if (key == null) return page`.
3. **Search corpus never renders markers nor double-counts failures** (see Out of scope above).
   Undecryptable pages are dropped from `loadSearchCorpus` instead of entering the rank/filter
   surface as "Unreadable (decryption failed)" entries.

Test hardening: the ViewModel source pins are now comment-independent and correctly anchored
(`initializeDataCore` block bounded on the following migration check; the `lock()` reset scoped on
`ClipboardGuard.scrubIfOwnCopy(appContext)` … `if (settings.hasMasterPassword)`, asserting the
reset precedes the connection drop). Dead `STRUCTURAL_CIPHERTEXT_MIN_BYTES` constant removed from
`DecryptFailurePolicy.kt`.

## Verification output (phase-88 initial)

| Check | Command | Result |
|---|---|---|
| New test class in isolation (debug) | `gradle testDebugUnitTest` | BUILD SUCCESSFUL — aggregate `1561` tests, `0` failures, `0` errors (1548 pre-existing + 13 new; the historic `B1Plat01ReleaseSigningTest` 2-assert gap also green this run) |
| Full unit suite | `gradle testDebugUnitTest` | BUILD SUCCESSFUL — `1561` tests green (two B1Db08 assertions failed mid-development on stale pin anchors and were fixed in-tree; final run 0 failures) |
| Debug APK | `gradle assembleDebug` | BUILD SUCCESSFUL — `90 actionable tasks: 49 executed, 41 up-to-date` |

Test run: 2026-08-16 on the GA runner, gradle 8.13 / JDK 21.