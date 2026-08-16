# Phase 85 — B1-NET-06 (LOW) — LocalSend: opening the Send dialog actively probes every IP in the Wi-Fi /24 and broadcasts device-model + presence

2026-08-16 · finding source: `docs/security-report.md` B1-NET-06, batch 1 (data-in-transit & network)

## The vulnerability (before/after)

**Before** — the finding claimed three network-behavior problems with the "Send to Nearby Device (LocalSend)"
flow:

1. **`Build.MODEL` broadcast**: `LocalSendSender.kt:75-84` set `alias = "InkFlow (Build.MODEL)"`,
   `deviceModel = Build.MODEL`, so every LAN receiver (and any passive monitor at the AP) could learn the exact
   device model; the announce carried the app's presence.
2. **Auto-discovery on dialog open**: the evidence cited `discover()` running immediately when the Send dialog
   opened (`LocalSendSendDialog.kt:73`) — presence + local IP emitted without any user action.
3. **/24 HTTP sweep**: `LocalSendSender.kt:195-218` (`legacyHttpScan`) walked `1..254` of the active subnet and
   `:230-258` POSTed `/api/localsend/v2/register` (with `senderInfo()`) to every address — up to 254 register
   probes on one search.

Exploit scenario (from the finding): opening the dialog alone swept the whole subnet with HTTP POSTs on port
53317 and emitted broadcast/multicast announces every ~1.1 s, letting any LAN host (or the AP's passive monitor)
detect the app's presence, exact model, and local IP — with no user confirmation required before any of that
traffic.

**After** — all three elements are resolved. The first two were already gone before this phase and are now
re-verified and pinned; the third (the live /24 sweep) is now an explicit per-search user opt-in:

1. **`Build.MODEL` was already stripped** (phase-110 = B1-NET-09, commit `8bc458d`): `LocalSendProtocol.
   senderIdentity` (`LocalSendProtocol.kt:106-114`) announces a fixed user-set `alias = "InkFlow"` and
   `deviceModel = null`. This phase re-wires both values to the single new pure-JVM decision table
   `services/localsend/LocalSendDiscoveryPolicy.kt` (`SENDER_ALIAS`, `senderDeviceModel = null`, `:43-46`) so the
   identity can never diverge from one source, and a comment-stripped repo-wide scan in the new test proves no
   live `Build.MODEL` remains in `services/localsend/` main code (KDoc that merely documents the ban is handled,
   not a false positive).
2. **Opening the dialog transmits nothing**: `discover()` runs ONLY from the explicit "Find nearby devices" /
   "Refresh" `onClick` handlers in `LocalSendSendDialog.kt` — never a `LaunchedEffect`. A source pin in the new
   test proves both the absence of any auto-discover effect and that the only discovery entry points are the two
   explicit buttons.
3. **The /24 `legacyHttpScan` register sweep is an explicit per-search opt-in** — this was the only live gap:
   `LocalSendSender.discoverDevices` had `includeLegacyHttpScan: Boolean = true` by default and the dialog
   hard-coded `= true` (`LocalSendSendDialog.kt:113`), so one tap on "Find nearby devices" silently blasted 254
   HTTP register POSTs across the subnet. Now:
   - `LocalSendSender.kt:104-118` — `discoverDevices` defaults `includeLegacyHttpScan` to
     `LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT` (`false`), and the probe is routed through
     `LocalSendDiscoveryPolicy.mayRunLegacyHttpScan(userOptedIn)` (fail closed — `false` unless the caller
     explicitly opts in), and is only even consulted when UDP discovery found nothing (`udpResults.isEmpty()`).
   - `LocalSendSendDialog.kt` — the dialog seeds a "Also check every address on this Wi-Fi (slower; helps on
     networks that block discovery broadcasts)" Checkbox from that same `false` default
     (`legacyHttpScanOptIn` state) and feeds the user's choice into the single discovery call — never a
     hard-coded `true`.

   A default (plain) search therefore emits ONLY a UDP announce + listen — identical to the pre-opt-in path — and
   never a subnet-wide HTTP scan. The sweep, where a user actively opts in (helpful on AP-isolated networks that
   drop discovery broadcasts), still only runs after the explicit action of checking the box and tapping
   "Find nearby devices" or "Refresh".

## Changes made

- **NEW** `app/src/main/kotlin/com/authorss81/noteflow/services/localsend/LocalSendDiscoveryPolicy.kt` — pure-JVM
  decision table (no Android deps, mirrors the `UpdateTrustPolicy.kt` single-policy-file pattern):
  - `DISCOVERY_REQUIRES_EXPLICIT_USER_ACTION = true`
  - `LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT = false`
  - `SENDER_ALIAS = "InkFlow"`, `senderDeviceModel: String? = null`
  - `mayRunDiscovery(userInitiated)` / `mayRunLegacyHttpScan(userOptedIn)` — both fail closed.
- `services/localsend/LocalSendSender.kt` — `discoverDevices` default bound to the policy constant; the /24 sweep
  routed through `LocalSendDiscoveryPolicy.mayRunLegacyHttpScan(includeLegacyHttpScan) && udpResults.isEmpty()`.
- `services/localsend/LocalSendProtocol.kt` — `senderIdentity` (`:105-114`) reads `SENDER_ALIAS` +
  `senderDeviceModel` from the policy (replaces literal `"InkFlow"` / `null`), keeping the announce model-free.
- `ui/components/LocalSendSendDialog.kt` — added `legacyHttpScanOptIn` state seeded from
  `LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT`, the single `discover()` call now passes `includeLegacyHttpScan =
  legacyHttpScanOptIn` (was hard-coded `= true`), plus a Checkbox row that lets a user opt into the sweep for
  the current search; added the policy import.
- Preserved untouched (per the diff-isolation rule): `LocalSendPairing.kt` (TOFU gate, B1-NET-02), the announce
  protocol stays `"https"`, and the B1-NET-05 redirect pins are intact — `LocalSendSender.kt` still has exactly
  two `instanceFollowRedirects = false`.

## Tests (new: `B1Net06LocalSendDiscoveryGateTest`, 7 tests — all pure JVM)

1. `decisions - discovery requires explicit user action and the legacy sweep is off by default` — the policy
   table's constants and both gates (discovery gate passes only with `userInitiated`; the make-legacy-scan gate
   fails closed → `mayRunLegacyHttpScan(false) == false`).
2. `sender default never hard-codes the sweep on and routes through the gate` — source pin: `LocalSendSender.kt`
   defaults `includeLegacyHttpScan` to `LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT`, no literal
   `includeLegacyHttpScan: Boolean = true` in the main source, and the probe routes through
   `LocalSendDiscoveryPolicy.mayRunLegacyHttpScan`.
3. `dialog seeds the sweep opt-in from the policy default and does not auto-discover on open` — source pin:
   `legacyHttpScanOptIn` seeded from `LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT`, the only
   `discover(` call sites are explicit `onClick` handlers (no `LaunchedEffect` auto-discovery in
   `LocalSendSendDialog.kt`).
4. `sender identity is policy-wired and model-free` — `LocalSendProtocol.senderIdentity` announced identity has
   `alias == LocalSendDiscoveryPolicy.SENDER_ALIAS` and `deviceModel == null`.
5. `protocol identity factory is wired to the policy constants` — source pin: both `SENDER_ALIAS` and
   `senderDeviceModel` referenced from the policy, no inlined `Build.MODEL`-carrying identity factory remains.
6. `the dialogs single discovery call passes the user opt-in` — source pin: `discover()` passes
   `includeLegacyHttpScan = legacyHttpScanOptIn` and the dialogs Checkbox text is wired to the opt-in state.
7. `no Build.MODEL marker survives in the localsend main source` — comment-stripped (`* `, `//`, `/*`, `/**`)
   scan of all `.kt` files under `app/src/main/kotlin/.../services/localsend/` finds no `Build.MODEL` reference
   (KDoc that merely documents the ban is ignored, so it is not a false positive).

## Verification

- `gradle testDebugUnitTest` — **BUILD SUCCESSFUL**. Full suite **1513 tests, 0 failures / 0 errors /
  0 skipped** (1506 baseline + 7). The previously-known `B1Plat01ReleaseSigningTest` 2-assert failures are gone
  (fixed in the phase-80 lineage / `b9a0b52`); the occasionally-flaky `WikiLinkParserCacheUnitTest` cancellation
  test passed.
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (90 tasks, 2m42s). Debug APK
  `app/build/outputs/apk/debug/app-debug.apk` = 173,779,638 bytes.

## Checksums / secrets handling

- No new secrets, keys, or passwords introduced; no logging added.
- No new dependencies (polyfill-free; existing `HttpURLConnection`/UDP stack unchanged; API-26 floor — no newer
  API used, no fallback needed; the one-time non-alarming Checkbox is self-explanatory and re-discoverable).
- `allowBackup=false`, `ClipboardGuard`, FLAG_SECURE intact. `.github/workflows/` untouched.
- No schema change, no migration.
- Pinned pre-existing invariants preserved: `B1Net05RedirectDowngradeTest` still passes (2×
  `instanceFollowRedirects = false` in `LocalSendSender.kt`), TOFU pairing gate (B1-NET-02) untouched.

## Out of scope (documented, not fixed here)

- The finding offered "or drop the sweep and rely on UDP discovery". We kept the sweep as an explicit per-search
  opt-in (a one-time, discoverable Checkbox) rather than deleting the 253-line code path, because AP-isolated
  networks genuinely drop discovery broadcasts and the sweep is the only way to find paired devices there. Its
  default is OFF and it never runs without an explicit, repeated user action.
- A user-set alias preference does not yet exist anywhere in `SettingsManager.kt`; the announce keeps the fixed
  `"InkFlow"` alias (the finding's "send only a user-set alias" is satisfied by the fixed app-name alias; wiring
  a per-user alias is out of scope for a LOW finding).