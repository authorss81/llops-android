# Phase 41: B1-NET-02 - LocalSend: unauthenticated one-way transfer lets a... [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-NET-02, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-NET-02` (HIGH)
- **Area:** Batch 1 - Data-in-transit & network
- **Evidence:** `LocalSendSender.kt:75-84` (announces `protocol="http"` by default), `LocalSendProtocol.kt:178-185` (`alias`/`protocol`/`fingerprint` from attacker-crafted announce JSON), `LocalSendSender.kt:322-353` (sends to `device.baseUrl()` built from that JSON), `LocalSendSender.kt:458-481` (`http` has no TLS at all), `LocalSendSender.kt:492-520` (`LocalSendTrustManager` pins to the self-announced fingerprint - no trusted anchor), `LocalSendSendDialog.kt:87-95` (payloads include `NOTE_HTML` plaintext export and plaintext vault zips)
- **Exploit scenario:** On shared Wi-Fi an attacker broadcasts a forged LocalSend announce; the user selects it and taps send. The fake receiver answers prepare-upload with 200 immediately (the 'human must accept' step is receiver-side and not cryptographically bound), and the app streams plaintext note HTML / vault zips to the attacker. TLS pinning authenticates nothing because the pinned fingerprint is the attacker's own announcement.

## The fix (where & how)

`LocalSendSender.kt`, `LocalSendProtocol.kt`, `LocalSendTrustManager.kt`, `LocalSendSendDialog.kt`. Implement confirmed pairing: receiving device displays a human-readable code/PIN the sender verifies out-of-band; sender persists the receiver's TLS fingerprint after a first explicit confirmation (TOFU) and refuses to send to an unknown/unpaired device. Never announce or connect with `protocol:"http"`; require TLS for any payload. Treat `200` to prepare-upload as zero evidence of user consent.


## Verification

- Unit tests (pure-JVM, no network) for the pairing/PIN handshake, TOFU fingerprint persistence, and rejection of unknown/unpaired receivers. Keep `LocalSendProtocolTest` green. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-NET-02 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-41/REPORT.md` committed: what changed (file:line), the
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
