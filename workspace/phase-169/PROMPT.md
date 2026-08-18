# Phase 169: Pages become "Unreadable (decryption failed)" after export / sometimes — fix + never expose contents [NOT STARTED]

You are working on **InkFlow/Noteflow**. User feedback: after exporting (or
sometimes), pages show "Unreadable (decryption failed)" and the page contents
don't show. This phase (1) investigates WHY decryption fails after export and
(2) hardens the fail-closed path so corrupted rows are surfaced properly and
recoverable WITHOUT raw ciphertext ever leaking.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## Context
- Fail-closed marker: `services/DecryptFailurePolicy.kt` renders
  `UNREADABLE_MARKER = "Unreadable (decryption failed)"` on AES-GCM auth failure;
  `data/repository/NoteRepository.kt` `decryptFieldForDisplay` uses it.
- Persistent failure threshold (~3 distinct records) trips
  `DatabaseSecurityHelper.setCorruptionDetected` → CorruptionRecoveryScreen.
- Export: `services/ImportExportService.kt` (recently changed by phase-135
  restore hardening — see git log) writes/reads `.nfbackup` archives and
  re-encryption (`reencryptPlaintextFields`).
- Investigate export-related causes:
  1. Backup password mismatch / wrong DEK unwrap after import (old
     `KeyLost`/`NoBlob` vs `Unlocked`).
  2. AAD context mismatch (pageId mutated during export/import → AES-GCM auth
     fails even with correct DEK) — see `EncryptionService.computeAad(table,
     entityId, columnName)`.
  3. Export writes ciphertext under the OLD AAD but re-import re-keys rows to
     new ids, or `reencryptPlaintextFields` misses `note_versions`.
  4. Threshold too low — 3 distinct failures on unrelated rows (e.g. a single
     bad import) triggers a scary CorruptionRecoveryScreen.
- The user's key complaint: pages become unreadable after export AND the
  contents don't show. Determine whether this is (a) a real data-loss/import
  bug (must fix the export path) or (b) correct fail-closed behavior being
  confusing (must improve the UI: show which rows failed, offer
  re-import/recovery per row, and the CorruptionRecoveryScreen).

## Definition of done
- Root cause identified for export-induced decryption failures (with file:line
  evidence). FIX it if it's a real export/import bug (AAD/re-key/password).
- If (b): improve the unreadable-row UX so users understand WHY and can act
  (e.g. per-page retry/re-import from a fresh backup) while NEVER exposing raw
  ciphertext or garbage (DecryptFailurePolicy stays fail-closed).
- After fix: export → import → pages decrypt correctly. Add a round-trip test
  (encrypt→export→import→decrypt) at unit level.
- `workspace/phase-169/REPORT.md`: diagnosis table (cause → evidence →
  fix/decision), the round-trip proof.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT weaken fail-closed decryption or the
  tamper-evidence/security model. Never log/display decrypted content or keys.
- No DB schema change without USER approval (prefer code fixes).