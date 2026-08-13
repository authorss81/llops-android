# Phase 14: Production readiness — full audit, security, LocalSend file transfer

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. This is the FINAL phase of the pipeline. It has two jobs: (1) a full
production-readiness + security audit that verifies EVERYTHING from Phases 2–13
is real and shipping-safe, and (2) adding REAL file transfer via LocalSend-style
local networking. It must end with a signed release APK that is genuinely usable.

## Part A — Full audit & production readiness

### A1. Feature-claim audit (honesty gate)
Verify every previous phase's claims against the code with `file:line` evidence.
For each: is it REAL (wired, called, works) or still a claim?
- Phase 2 security paths, Phase 3 dead-code removal, Phase 4 AGSL wet-mixing,
  Phase 5 UX, Phase 6 WebDAV (E2EE sync), Phase 7 painting features (stabilizer,
  pressure curves, symmetry, harmony), Phase 8 perf fixes, Phase 10 plugin
  framework, Phase 11 plugin infrastructure (lifecycle, isolation, deps),
  Phase 12 OCR + Web Search plugins, Phase 13 brushes/stickers/
  rotation.
- Fix anything false. Never leave a known false claim in `ROADMAP.md`/`AGENTS.md`
  — update them honestly.

### A2. Security audit (defense-in-depth check)
- Confirm: `allowBackup="false"`, `data_extraction_rules.xml` intact, no exported
  components, no secrets/keys/decrypted content logged, `ClipboardGuard` used on
  all copy paths, encryption (PBKDF2 600k, AES-256-GCM, AndroidKeyStore DEK,
  zeroization on lock) intact.
- Confirm the new plugins (Phases 11–12) added no unsafe surface: Web Search URL
  construction is safe (no injection), plugin error isolation is real (Phase 11),
  OCR handles errors without leaking paths.
- Confirm no INTERNET permission creep beyond WebDAV + Web Search plugin.
- Confirm no debug-only code (`BuildConfig.DEBUG` leaks) in the release build.

### A3. Release readiness
- `gradle assembleDebug`, `gradle testDebugUnitTest`, `gradle assembleRelease`
  ALL succeed.
- Produce the signed release APK. Note: the project currently falls back to the
  auto-generated debug keystore. Document clearly in `docs/RELEASE.md` how to add
  a real release keystore (keytool + GitHub secrets) so a future maintainer can
  publish. Do NOT fake a production keystore.
- `CHANGELOG.md` updated with an honest summary of Phases 2–14.

## Part B — Real file transfer (LocalSend-style)

Add the ability to **send a note/export as a file to a nearby device** over the
local network, using the **LocalSend protocol** (or a compatible, real, working
implementation). "Nearby" = same Wi-Fi; NO internet/cloud required.

- **Real implementation required.** Options (choose the honest path):
  - Preferred: implement a minimal **LocalSend-protocol** sender (UDP discovery
    + HTTP POST to the receiver's `/api/v2/package/request` endpoint) using the
    app's HTTP client. This is real and interoperable with the LocalSend app.
  - Alternative if full protocol support is not tractable: a real, tested local
    HTTP server + receiver UI on the SAME device pair you control, clearly
    labeled as "LocalSend-compatible experimental." Do NOT fake it.
- Runs off the main thread. Shows progress, cancel, and completion/failure
  states. Security: send only what the user explicitly shares; require the
  receiving device to accept (LocalSend's confirm flow); never auto-accept.
- Requires `NEARBY_WIFI_DEVICES`/`ACCESS_WIFI_STATE` or the LocalSend discovery
  port — check what the platform allows; add ONLY the minimum permission needed
  and explain it.
- Unit test the protocol logic in pure JVM (URL building, JSON request body,
  response parsing) — no network in tests.

## Definition of done
- All three gradle gates pass.
- `workspace/phase-14/AUDIT_REPORT.md` written: per-phase verdict table
  (PASS/FIXED/REMOVED) with `file:line`, security checklist results, and the
  release artifact path.
- LocalSend transfer works between two devices on the same network (documented),
  or — if the full protocol was not tractable — the honest, labeled fallback is
  shipped with the exact limitation stated in `AUDIT_REPORT.md`.
- `docs/RELEASE.md` written.
- Release APK artifact produced.

## Constraints
- NO new third-party dependencies unless strictly required for the transfer
  implementation (then say so explicitly and justify).
- Do NOT add INTERNET just for transfer — LocalSend is local-network only.
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- No weakening tests to make them pass.
- Be honest above all: `AUDIT_REPORT.md` must distinguish verified truth from
  "not verified this phase." Never overstate.