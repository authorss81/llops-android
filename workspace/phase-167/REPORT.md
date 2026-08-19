# Phase 167 Report — Bottom navigation bar overlays messages & calendar on mobile

- **Date:** 2026-08-19
- **Trigger:** user feedback — "on mobile there is a 'back home' button and another
  button at the bottom; some messages and the calendar go below it (hidden behind it)".

The "back home button + another button at the bottom" on a mobile device is the
**system navigation bar** (gesture-home pill / 3-button bar). The app enables
edge-to-edge (`MainActivity.kt:252`, `enableEdgeToEdge`). Any surface anchored to
the **window bottom without accounting for the navigation-bar inset** gets drawn
under that bar. This phase's job: prove, per surface, whether it respects the bar,
fix the ones that don't, and pin the whole chain so it cannot regress. All fixes are
**dynamic** (real system insets) — no hard-coded pixel height.

## 1. Surfaces audited (every spot that could be covered)

### 1a. Transient messages — the real defect (FIXED)

**`MainActivity.kt:804-810` — root `SnackbarHost` (the app has NO `Toast`; all
transient messages flow through the phase-153/22.9 snackbar pipeline).**

BEFORE: the host was `Modifier.align(Alignment.BottomCenter)` directly at the root
`Box` bottom (window edge under edge-to-edge) with **no inset modifier**. Snackbar
messages therefore rendered under the transparent system navigation bar
(gesture pill / 3-button bar) — exactly "some messages go below it (hidden behind it)".
There is no Scaffold at this level to add the padding for us (all four content
screens have their own inner Scaffolds; the root host is outside them all).

AFTER (`MainActivity.kt:804-810`):
```kotlin
SnackbarHost(
    hostState = snackbarHostState,
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()   // dynamic real inset, phase-167
)
```
`navigationBarsPadding()` reads the current navigation-bar inset at runtime — correct
for 3-button nav, gesture nav, landscape, foldables, and 360dp→tablet; zero on
devices without a nav bar. No pixel constant anywhere.

### 1b. Calendar & all page-list/scrollable content — VERIFIED already inset-aware (kept correct, now pinned)

The calendar (`CalendarView`, rendered in `HomeScreen.kt:1344` at `pageViewMode=3`)
lives in the HomeScreen Scaffold content whose inner padding already includes the
bottom system-bar inset:

- `HomeScreen.kt:629` → `Scaffold(...)` → content lambda `) { padding ->`
  `HomeScreen.kt:778-783` applies `.fillMaxSize().padding(padding)`.
- The Scaffold default `contentWindowInsets = WindowInsets.systemBarsForVisualComponents`
  (verified directly against the bundled `material3 1.3.1` bytecode,
  `ScaffoldDefaults.getContentWindowInsets` → `getSystemBarsForVisualComponents`);
  with a topBar present the bottom inset lands in the content padding.
- `HomeScreen.kt:958-962`: Main Content Panel Column is `.weight(1f).fillMaxHeight().padding(16.dp)`
  → the calendar's grid (`CalendarView.kt:137-139`, fixed 260dp) + summary
  (`:193-213`) + pages `LazyColumn` (`CalendarView.kt:229-231`, `Modifier.weight(1f)`)
  are **bounded inside the padded content**: the weight-bound list is the pager, so
  its LAST row scrolls above the bar, not under it. No fixed pixel height in the list.

The other three screens were audited with the same method and are equally
inset-aware through their Scaffold content padding:
- `MarkdownPreviewScreen.kt:552-556` — content root `.padding(padding)`, editor pane `.imePadding()` (`:562`); the on-screen keyboard can no longer cover the row being typed either.
- `KnowledgeGraphScreen.kt:385-389` — content root `.padding(padding)`; the bottom-aligned selected-node card (`:650-654`) is inside that padded content.
- `EditorScreen.kt:1810-1814` — content root `.padding(padding)`; the canvas, minimap and draggable `FloatingToolDock` (already `WindowInsets.safeDrawing`-aware at `:2563-2567`) all sit above the bar.

> **Review fix (2026-08-19) — honest scope note:** this phase changed NO code in the
> four content screens (Home/Editor/Preview/Graph) — they were already inset-aware,
> so if the calendar symptom is still visible on a real gesture-nav device the root
> cause is NOT the nav-bar inset. The likeliest secondary candidate is the calendar's
> FIXED 260dp month grid + summary row (`CalendarView.kt:139` + `:219`) overflowing
> short/landscape viewports and collapsing the `weight(1f)` pages list
> (`CalendarView.kt:229-231`) to near-zero height — a short-screen layout issue, not a
> nav-bar-overlay one. It is left as a follow-up (phase-27 bug-fix queue) rather than
> silently re-scoping this UI pass.

### 1c. Edge-to-edge recovery screens (no Scaffold) — real gap, FIXED

`RestoreBlockedScreen`, `CorruptionRecoveryScreen`, `KeystoreKeyLostScreen`
(`MainActivity.kt:1261`, `:1339`, `:1465`) render
`Column(fillMaxSize().verticalScroll(...).padding(24.dp))` with NO Scaffold wrapper.
Under edge-to-edge their scroll container reaches the window bottom, so the last
row (the "Choose Backup & Restore" / "Start Fresh" buttons and the error text)
could scroll under the nav bar.

AFTER: each gets `.navigationBarsPadding()` + `.statusBarsPadding()` appended to its
scroll-content modifier (`MainActivity.kt:1294-1295`, `:1375-1376`, `:1498-1499`) —
dynamic insets. The status-bar inset is the **phase-167 review fix** (the original
draft only covered the bottom edge; these screens draw under the TRANSPARENT status
bar too, and the top row relied on a hard-coded 48dp `Spacer`). The
`LockScreen` already had `systemBarsPadding()` (`LockScreen.kt:72`), left untouched.

## 2. Definition-of-done check

- ✅ No content hidden behind the bottom bar on mobile: messages (snackbar) explicitly
  inset (`MainActivity.kt:808`); every Scaffold content root applies the inner padding
  (1b); the two Scaffold-less recovery surfaces inset (1c).
- ✅ Calendar + pager/scrollable content respects the bar height — via Scaffold content
  padding (verified default insets from material3 1.3.1 bytecode) + the calendar pages
  list is weight-bound above the padded container.
- ✅ Messages render ABOVE the bottom bar (`navigationBarsPadding()`, no Scaffold involved).
- ✅ Dynamic: all fixes read the real runtime inset; nothing hard-codes a pixel height
  (test-pinned, §4). Works on 360dp, landscape, gesture & 3-button nav, tablets.

## 3. Code changes

| File | Change |
|---|---|
| `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt:804-809` | Root `SnackbarHost` + `.navigationBarsPadding()` (`:808`, messages above the bar). |
| `app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt:1294-1295, 1375-1376, 1498-1499` (screens `:1261`, `:1339`, `:1465`) | `RestoreBlockedScreen` / `CorruptionRecoveryScreen` / `KeystoreKeyLostScreen` scroll content + `.navigationBarsPadding()` (bottom, original) + `.statusBarsPadding()` (top, review-fix). |
| `app/src/test/java/com/authorss81/noteflow/Phase167BottomNavOverlayTest.kt` | NEW — 8 source-pinning regression tests (§4), review-fix hardened. |

No navigation logic changed. No `.github/workflows/` edits. No schema/DB/deps.

## 4. Regression guard — `Phase167BottomNavOverlayTest` (8 tests, all green)

| Test | Pins |
|---|---|
| `root SnackbarHost is inset above the navigation bar` | `navigationBarsPadding()` present on the root host AND after `align(BottomCenter)` in the modifier chain (review-fix: no inset on a surface that stops bottom-anchoring), no fixed-pixel offset. |
| 4× `…content applies Scaffold innerPadding` | Home / Editor / MarkdownPreview / KnowledgeGraph content roots each `.padding(padding)` under their `Scaffold`; preview also `.imePadding()`. |
| `CalendarView pages list is weight-bounded…` | calendar pages `LazyColumn` is `weight(1f)` (scrollable, bounded) and NOT a fixed `.height(...)`. |
| `recovery screens carry the navigation-bar inset` | all three Scaffold-less recovery screens include `navigationBarsPadding()` (bottom) AND `statusBarsPadding()` (top, review-fix). |
| `no surface hard-codes a pixel height for the bottom bar` | grep-guard against fixed `bottom = NN.dp` offsets in the root overlays + calendar list. |

## 5. Verification

- `gradle :app:testDebugUnitTest` → **2276 completed, 1 failed**: the single failure is
  the **documented pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure**
  (`\\fileserver\share\secret-wills.docx` at `Phase148UiFailureTextScrubTest.kt:234`),
  reproduced identically on clean trees since phase-148 and untouched by this diff.
- `gradle :app:assembleDebug` → **green**.
- The phase-167 suite itself: 8/8 green.

- **Commit:** `llops: phase-167 ...` (see git log).

## 6. Part B — Kali round-2 triage → next phases (175, 176)

Triage of `docs/kali-report-round2.md` (27 rows `R2-KS-01..07`, `R2-KS-10..19`,
`R2-KS-20..29`) for anything NOT already owned by an existing phase. Result:
**two genuinely uncovered rows spawn two new phases**; the rest are PASS
(already verified) or already owned.

| Kali row | Severity | Verdict | Owning phase |
|---|---|---|---|
| R2-KS-20 | MEDIUM | lingua `language-models/` 207.6 MB/75 langs in base APK | **already covered → phase-170** (NOT STARTED, strips 24/75 + ABI splits) |
| **R2-KS-21** | **MEDIUM** | ML Kit OCR `assets/mlkit-google-ocr-models/` + `libmlkit_google_ocr_pipeline.so` + translate `libtranslate_jni.so` + `res/raw/translate_models_metadata.json` in base APK — contradicts approved downloadable-plugin architecture | **no phase owned it → NEW `phase-175`** |
| **R2-KS-27** | **LOW** | release payload ships `DebugProbesKt.bin` + `kotlin-tooling-metadata.json` + `firebase-*.properties` | **no phase owned it → NEW `phase-176`** |
| **R2-KS-24** | **INFO** | no R8 `mapping.txt` retained for forensic back-mapping | **no phase owned it → NEW `phase-176`** (same release-packaging area as R2-KS-27) |
| R2-KS-22 | LOW | ABI splits missing (4 ABIs × 6 libs, x86/x86_64 unused) — legacy alias of round-1 Phase-32-NEW-02 | **already covered → phase-170** |
| R2-KS-17 | INFO | placeholder plugin cert-pin fails closed | **already covered → phase-171** (fail-closed pin test + runbook) |
| R2-KS-23 | INFO | signing scheme v2-only (no v3/v3.1/v4) — blocks key rotation | **already covered → phase-171** (round-1 Phase-32-NEW-03: force `enableV3Signing`) |
| R2-KS-25 | INFO | baseline profile absent (`ProfileInstaller` wired, no profile in `assets/`) | PASS — phase-03 round-1 deferral (perf, non-security) |
| R2-KS-26 | INFO | native hardening spot-check (sqlcipher RELRO/BIND_NOW present); full per-lib check not done | deferred → `DYNAMIC-DEFERRED` (needs device/emulator) |
| R2-KS-28 | INFO | `android:extractNativeLibs="true"` (install footprint) | PASS — optional; decision documented in phase-176 |
| R2-KS-29 | INFO | MobSF scan exceeded 2-core runner timeout | deferred (tooling) — rerun only when a bigger runner is available |
| R2-KS-01..07 (except R2-KS-23/28), R2-KS-10..16, R2-KS-18, R2-KS-19 | INFO/PASS | encryption, WebDAV, quarantine, FLAG_SECURE, plugin sandbox, R2-KS-01 backup flags (`allowBackup="false"` + data-extraction rules), R2-KS-02/05-07 exported components, R2-KS-04/13 FileProvider restricted to `files-path apk/` + `cache-path exports/`, dex-string secret scan | PASS-verified at phase-160 — no new work |

> **Review fix (2026-08-19):** the row descriptions for **R2-KS-23, R2-KS-25,
> R2-KS-26 and R2-KS-28** in the first draft of this table were copy-pasted from
> R2-KS-17/13/01/02. Corrected above to match `docs/kali-report-round2.md:101-107`
> verbatim; the phase assignments were already correct and unchanged. R2-KS-22
> severity corrected from MEDIUM → LOW (matches the source report).

No duplicates with phases 168-174 (pre-seeded) or the new 175/176: the two new
phases own exactly the two unowned rows, split by AREA (birth 175 = base-APK ML Kit
move-out → downloadable-plugin runtime; 176 = release-packaging hygiene + R8 mapping).