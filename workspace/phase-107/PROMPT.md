# Phase 107: B2-CRYPTO-09 - Field AEAD AAD is a single global constant -... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-CRYPTO-09, LOW) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-CRYPTO-09` (LOW)
- **Area:** Batch 2 - Crypto side-channels & edge cases
- **Evidence:** `FIELD_AAD = "Noteflow-Vault-Field-Encryption-v1"` (`EncryptionService.kt:21`) applied identically to every field (`:67`); legacy payloads decrypted with `withAad = false` (`:93,109-111`); the same constant covers pages.{title,extractedText}, strokes.{textContent,pointsJson}, media_embeds.textContent, note_versions.{title,extractedText} (`ImportExportService.kt:1107-1112`)
- **Exploit scenario:** A write-capable adversary (root, downloadable plugin per B1-AUTH-01, crafted restore per B1-AUTH-05, or DB-layer tampering) can transplant a note's title/extractedText ciphertext into any other record - the tag verifies and the vault renders swapped content as authentic. Content-granularity integrity is genuinely unenforced.

## The fix (where & how)

`EncryptionService.kt:21,67` and every encrypt/decrypt call site in `NoteRepository.kt` - include a per-record binding in the AAD (table + record id + field name, e.g. `pages|$pageId|title`); stop writing legacy no-AAD rows; migrate existing versioned rows to the per-record AAD in the same pass that fixes phase-105 (B2-CRYPTO-05)


## Verification

- Unit tests: a ciphertext transplanted into a different record id fails to decrypt; legacy rows still decrypt via the migration path; new writes use per-record AAD. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-CRYPTO-09 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-107/REPORT.md` committed: what changed (file:line), the
  checksum/secrets handling, verification output, and any input you judged
  out-of-scope.

## Constraints

- NO DB schema change unless this fix requires one - then a migration-safe note
  in REPORT.md is MANDATORY, and the migration must never delete user data.
- Do NOT edit `.github/workflows/`. Do not add new dependencies unless required
  by the fix (then justify in the commit).
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`,
  `ClipboardGuard`, and FLAG_SECURE intact.
- Do not fix OTHER security findings in this phase - that is a different phase.
  If you find a new related bug, document it in REPORT.md, do not fix it here.
