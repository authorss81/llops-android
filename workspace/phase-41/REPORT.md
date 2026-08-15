# Phase 41 — B1-NET-02 Fix: LocalSend Confirmed Pairing (TOFU)

**Status:** DONE
**Finding:** B1-NET-02 (HIGH) — "LocalSend: unauthenticated one-way transfer lets a
same-LAN attacker's fake receiver obtain PLAINTEXT note/vault content; the
'human-accept' and TLS fingerprint are both receiver-announced"
(`docs/security-report.md:167`).

## What changed

### 1. New pure-JVM pairing module — `services/localsend/LocalSendPairing.kt`

- `LocalSendPairedDevice` + `LocalSendPairedDeviceStore` (interface) +
  `InMemoryLocalSendPairedDeviceStore` + `LocalSendPairedDeviceCodec` (JSON serde)
  — the TOFU persistence seam, fully testable without Android.
- `LocalSendPairingCodes`:
  - `normalizeFingerprint` (lowercase, colons stripped) — canonical store key.
  - `formattedFingerprint` — `XXXX:XXXX:…` display form (matches LocalSend apps).
  - `pairingCode` — deterministic 6-digit out-of-band code derived from the
    receiver's TLS cert fingerprint (same fingerprint → same code).
- `LocalSendPairing`:
  - `gate(device, store) -> Allowed|Denied` — the hard pre-send gate: refuses
    `protocol:"http"` receivers, receivers without a fingerprint, and receivers
    whose fingerprint was never paired. This is evaluated BEFORE any payload
    byte leaves the device.
  - `startPairing(device)` — only for HTTPS+fingerprint devices.
  - `confirmPairing(request, code)` — constant-time code compare
    (`ConstantTime.stringEqual`); returns the normalized device.
  - `pair(store, request, code)` — confirms + persists the TOFU anchor.

### 2. `services/localsend/LocalSendSender.kt`

- Constructor now takes a `LocalSendPairedDeviceStore` (defaults to the
  fail-closed `InMemoryLocalSendPairedDeviceStore`).
- `sendFile` gates through `LocalSendPairing.gate` **before any network I/O**
  (`LocalSendSender.kt:313-326`) and refuses with the gate's clear message;
  the success description uses the *paired* alias (`:399`), not the
  attacker-forgeable wire alias.
- Every payload connection is pinned to the STORED paired fingerprint
  (`trustedFingerprint`, `:325`), never to the wire-announced one.
- `/prepare-upload` 200 is documented as ZERO evidence of human consent
  (`:337-341`) — consent is the pairing + the dialog's per-send confirmation.
- `openConnection` now refuses any non-`https` URL outright (was: allowed the
  `http` branch): TLS is required for any payload (`:492-503`), defense-in-depth
  behind the gate so a downgrade/bug can never fall back to cleartext.
- `LocalSendTrustManager` doc updated: the pinned fingerprint is now the
  *paired* fingerprint, so the pin authenticates the receiver (a fake receiver
  announcing its own cert is stopped at the pairing gate).

### 3. `services/localsend/LocalSendProtocol.kt`

- `Info.protocol` default and `senderIdentity(...)` now announce `"https"`,
  never `"http"` (`LocalSendProtocol.kt:77,107`) — the app never advertises a
  cleartext endpoint. `parseDiscoveryResponse` still parses whatever the
  receiver announces (parse remains permissive; the send gate is where TLS is
  enforced).

### 4. `services/SettingsManager.kt` + `services/localsend/SettingsLocalSendPairedDeviceStore.kt`

- TOFU anchors persist in SharedPreferences: `localsend_paired_<normalized-fp>`
  → `LocalSendPairedDeviceCodec` JSON (`SettingsManager.kt:323-345`), same
  pattern as `SettingsPluginInstallStore`. SharedPreferences only — **no DB
  schema change, no migration**.

### 5. `utils/ConstantTime.kt`

- Added `stringEqual` — constant-time equality for fixed-length ASCII PIN/codes
  (the 6-digit pairing code). The pairing code is not a secret (it is derived
  from the public fingerprint) but the compare is kept constant-time to follow
  the repo's single "pin compare funnel" rule (CWE-650).

### 6. `ui/components/LocalSendSendDialog.kt`

- Device list shows the pairing/security state per device: sendable devices show
  "Paired · HTTPS"; unpaired HTTPS devices show "Not paired — tap to verify &
  pair"; `http`/no-fingerprint devices are shown DISABLED with the reason and
  cannot be clicked.
- Pairing sub-view: receiver alias, formatted TLS fingerprint (monospace),
  short 6-digit pairing code, and an explicit "Pair & Send" confirm button.
  Documented instruction to verify the fingerprint on the receiving device
  out-of-band.
- Per-send confirmation sub-view before every transmission ("Send X to Y? …
  verified TLS, pinned to your paired certificate").
- `settings = SettingsManager(context)` + the settings-backed store are wired
  into the sender.

### Before / After (exploit path)

- **Before:** `LocalSendSender.kt:75-84` announced `protocol="http"`; the wire
  `alias`/`protocol`/`fingerprint` (`LocalSendProtocol.kt:178-185`) were fully
  attacker-controlled; `sendFile` sent `device.baseUrl()` bytes to anything that
  answered 200; `LocalSendTrustManager` pinned to the self-announced fingerprint
  with no trusted anchor.
- **After:** an attacker's forged announce is refused at the pairing gate
  (unpaired fingerprint) or at the TLS-only gate (http) **before** any byte
  moves; a genuinely-paired device is pinned to its user-verified cert; a
  per-send explicit confirmation is the only consent, with `/prepare-upload` 200
  explicitly treated as zero evidence of human acceptance.

## Verification

- `gradle testDebugUnitTest` — full run **924 tests, 0 failures** (1 run had a
  pre-existing flaky `PluginUpdateEngineTest` timing failure that passes in
  isolation and is untouched by this diff — same flake documented in the
  phase-40 report; re-run green).
- `gradle assembleDebug` — **BUILD SUCCESSFUL**.
- New tests:
  - `LocalSendPairingTest` (19): pairing code determinism/format, gate denials
    (http, no-fingerprint, unpaired), gate allow after TOFU, fingerprint-change
    refusal, code-mismatch refusal, store persistence, codec round-trip/garbage.
  - `LocalSendProtocolTest` (25, was 21): +`senderIdentity_announcesHttpsNeverHttp`,
    +`infoDefaultProtocol_isHttps`.
- `LocalSendProtocolTest` (18 existing behaviour) kept green; no existing test
  regressed.

## Checksums / secrets handling

- No new secrets introduced. The pairing code is derived from the receiver's
  public TLS fingerprint (not a secret); `confirmPairing` uses constant-time
  compare. Nothing new is logged; no decrypted note content ever touches the
  network path beyond the pre-existing plaintext HTML/vault-export payloads the
  user explicitly chooses to share with a now-paired device. `allowBackup=false`,
  `ClipboardGuard`, FLAG_SECURE untouched.

## Out of scope (documented, NOT fixed here)

- **B1-NET-06** (LOW): the `/24` legacy HTTP register sweep + announce traffic
  before user consent — different phase.
- **B1-NET-09** (INFO): generic User-Agent / `Build.MODEL` leak — phase-110
  already addressed the alias/model; UA header still `HttpUserAgent.GENERIC`.
- The legacy `httpRegisterProbe` (discovery-only, sends our identity JSON, no
  payload) is unchanged; it is discovery, and B1-NET-06's consent gating is out
  of scope.
- Findings B1-NET-03..B1-NET-05, B1-DB-x, etc. are separate phases.

## Rerun verification (attempt 2 — 2026-08-15)

This phase's work (commit `f1020a1`) was committed during the previous agent
run, leaving a clean working tree; `phase_runner.sh`'s evidence gate therefore
saw no post-run delta and never wrote `.done`, leaving `.no_work` + `.attempts`
and re-selecting the phase. This rerun independently re-verified the committed
fix in a clean checkout and applied two `.editorconfig` conformance fixes
(`insert_final_newline = true`):

- `app/.../services/localsend/SettingsLocalSendPairedDeviceStore.kt` — added
  missing final newline.
- `app/.../utils/ConstantTime.kt` — added missing final newline.

Independent verification (this run, clean tree, Gradle 8.13 / JDK 21 as in CI):

- `gradle testDebugUnitTest` — **BUILD SUCCESSFUL, 924 tests, 0 failures, 0
  errors, 0 skipped** (JUnit XML). `LocalSendPairingTest` 19/19,
  `LocalSendProtocolTest` 25/25 — both green, no regression.
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (90 actionable tasks).

Design note recorded for honesty: `confirmPairing(request, code)` uses a
constant-time compare and refuses a mismatched entered code. **Phase-41 review
fixes (below) now make that mismatch path REACHABLE from the UI** — pairing no
longer auto-passes the self-derived code for every user.

## Review fixes applied (2026-08-15, post-review)

Follow-up review of phase-41 surfaced one blocker + several hardening items;
all were applied in working-tree commits after `0c70974`:

1. **BLOCKER FIXED — pairing flow was unreachable.** The device list bound
   `.clickable(enabled = sendable && phase == "Idle")`, where `sendable` is only
   true for an already-paired receiver. Unpaired HTTPS receivers (the one device
   class that can ever go through pairing) were rendered disabled, so
   `startSend` never fired, `pairingDevice` was never set, and the pairing /
   `Pair & Send` sub-views were dead code — LocalSend could never send to any
   device. Rows are now `clickable(enabled = phase == "Idle")`
   (`LocalSendSendDialog.kt:378`) and `startSend` routes every state: paired
   → per-send confirm, unpaired HTTPS → pairing sub-view, http/no-fingerprint →
   explicit "cannot send" message.
2. **Typed-code verification is now real.** `LocalSendSendDialog.confirmPairing`
   (`LocalSendSendDialog.kt:158-199`) no longer always calls
   `pair(store, request, request.code)`. It requires ONE of: (a) a verification
   code typed from the receiving device, verified against the fingerprint-derived
   code (constant-time; a mismatch refuses pairing and surfaces `pairingError`),
   or (b) an explicit "fingerprints match" acknowledgement after the user
   compares the displayed TLS fingerprint with the receiver's identity screen
   out-of-band (`PairingBody` at `:444` gains the code field + acknowledgement
   checkbox). The previously-dead `confirmPairing_requiresMatchingCode` path is
   now a real UI rejection path.
3. **Pin source hardened.** `LocalSendSender.sendFile` now extracts the STORED
   paired device and uses its `normalizedFingerprint` as
   `trustedFingerprint` (`LocalSendSender.kt:317-325`), and every payload
   connection (`/prepare-upload`, `/upload`, `/cancel`) pins to that stored
   value (`:353,381,390`) instead of the wire-supplied `device.fingerprint` — so
   a later forged announce with a different fingerprint can never influence the
   pin even if some future path bypassed the pre-send gate.
4. **Wire alias no longer embedded in denial messages.** `LocalSendPairing.gate`
   denial reasons no longer interpolate the attacker-controllable `device.alias`
   (`LocalSendPairing.kt:133-162`) — the untrusted value was surfaced verbatim
   in `SendResult.description`. Messages now describe the refusal without naming
   the receiver (the device row already shows the alias).
5. `.editorconfig` `insert_final_newline` conformance: final newlines added to
   `LocalSendPairingTest.kt`, and to the pre-existing `LocalSendSender.kt` /
   `LocalSendProtocol.kt`.

Out of scope (unchanged): the legacy `/24` cleartext discovery sweep
(`includeLegacyHttpScan` / `httpRegisterProbe`) is B1-NET-06 and remains in a
separate phase; the pre-existing flaky `PluginUpdateEngineTest` rollback timing
failure is untouched and unrelated.
