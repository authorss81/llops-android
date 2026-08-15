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
  (`LocalSendSender.kt:306-319`) and refuses with the gate's clear message;
  the success description uses the *paired* alias (`:364`), not the
  attacker-forgeable wire alias.
- `/prepare-upload` 200 is documented as ZERO evidence of human consent
  (`:331-335`) — consent is the pairing + the dialog's per-send confirmation.
- `openConnection` now refuses any non-`https` URL outright (was: allowed the
  `http` branch): TLS is required for any payload (`:455-464`), defense-in-depth
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
constant-time compare and refuses a mismatched entered code, but the current
`LocalSendSendDialog.confirmPairing()` UI calls it with the code derived from
the receiver's announced fingerprint (`enteredCode = request.code`); a
receiver's announced fingerprint therefore always produces a "matching" code.
That is intentional: the sending user performs the actual out-of-band check by
comparing the displayed fingerprint + short pairing code against the receiving
device's own identity screen (an attacker's forged certificate yields a
different code/fingerprint the user will not recognise), and pairing is only
persisted after that explicit "Pair & Send" confirmation. The mismatch-reject
path is real and tested (`confirmPairing_requiresMatchingCode`) and is the
guard a future typed-code flow would use; the current UI relies on the
human-comparable identity display instead.