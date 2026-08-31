# Phase 246 — Strict verification of the 4 phase-243 claimed fixes + gap closure

Status: **DONE**
Date: 2026-08-31
Verified HEAD: `6fb1fc1` (fix: drawing dots + wrong-password on restart)
Pre-fix comparison refs: `e9dec37^` (= the phase-243 parent), `e9dec37` (phase-243), `2709453` (phase-243 review fixes)

## Executive summary

All 4 claimed root causes are **confirmed real** at the pre-fix tree (`e9dec37^`), and all 4 defects are **truly closed at HEAD** — but only in part by phase-243 itself, and with one mechanism discrepancy vs. the user summary. Two of the fixes (paper texture, minimap) were actually delivered by **phase-247** and **phase-244/248**, not phase-243; one (rotation) was removed **wholesale**, which is strictly stronger than the claimed "flip the default to false"; and one (markdown) was fixed by `replaceContentRun`, **not** by the summary's "switch to `replaceBlockSource`" (the byte-run fix is strictly more correct than the summary's proposal).

**No functional gap remains in the 4 claimed areas at HEAD.** Two code changes were still needed this phase: a **HEAD-breaking syntax error** introduced by `6fb1fc1` in `LockScreen.kt` (an orphaned comment continuation without its `//`) — the tree would not compile at all — and one new regression test proving the exact user-facing heading symptom. Both are in.

---

## Step 0 — Root causes on the pre-fix tree (`e9dec37^`)

| # | Claimed root cause | Verified at `e9dec37^` | Verdict |
|---|---|---|---|
| 1 | Live typing called the splitting `replaceBlock` on every keystroke | `ui/components/markdown/HybridMarkdownEditor.kt:103-105` — `emitBlockEdit(blockIndex, newRaw)` = `doc = MarkdownBlockTokenizer.replaceBlock(doc, blockIndex, newRaw)`; invoked from `RawBlockEditor.onValueChange` (`:132`). `replaceBlockSource` (the summary's "non-splitting" helper) already existed at `services/MarkdownBlockTokenizer.kt:391` but was NOT the editor's path (still index-based, string-returning). | **Confirmed** — splitting/whole-window replace per keystroke, stale block index |
| 2 | `PaperTextureStrengthPolicy.grainDrawAlpha` had a hardcoded `MIN_ALPHA = 0.02f` floor | `services/PaperTextureStrengthPolicy.kt:40` `const MIN_ALPHA = 0.02f`; `:52-53` `grainDrawAlpha(0) = MIN_ALPHA + 0·(MAX−MIN) = 0.02f`; `grainScale(0) = 0.02/0.045 ≈ 0.444` → ~44% of default grain remained at dial 0 | **Confirmed** |
| 3 | Two-finger twist `canvasTwistEnabled` defaulted to `true` | `services/SettingsManager.kt:243-249` — `var canvasTwistEnabled: Boolean get() = prefs.getBoolean("canvas_twist_enabled", true)`; `CanvasRotationPolicy.kt` present (134 lines, deleted in `e9dec37`); `AnnotationCanvas.kt` had the twist gesture + `rotationZ` layers (7 `rotationZ`/`rotationAngle` refs pre-fix) | **Confirmed, and understated by the summary** |
| 4 | Minimap anchored to `LocalConfiguration.screenWidthDp/screenHeightDp` | `ui/components/AnnotationCanvas.kt:3420-3423` — `val configuration = LocalConfiguration.current; val screenW = configuration.screenWidthDp.dp.toPx(); val screenH = …` feeding `defaultAnchorBottomEnd(screenW, screenH, …)` (`:3427`) | **Confirmed** |

---

## Step 1 — Verification at HEAD

### 1. Markdown duplication — FIXED (mechanism differs from the summary)

| Claimed cause line | Actual cause line | Fixed line at HEAD |
|---|---|---|
| `HybridMarkdownEditor.kt` per-keystroke `replaceBlock` | `e9dec37^:HybridMarkdownEditor.kt:105` | `HEAD:HybridMarkdownEditor.kt:115-143` (`emitBlockEdit(prevRaw, newRaw)`); primary `replaceContentRun(doc, at, endByte, newRaw)` at `:127` anchored via `doc.content.indexOf(prevRaw, editingAnchorByte)` `:122`; guard-anchored `replaceBlock` fallback `:136-140` (only a block whose line range still overlaps `editingAnchorByte`); `editingAnchorByte` captured at editor-open `:212` |

**Discrepancy — documented, not a defect.** The user summary says the fix "switches live typing to the non-splitting `replaceBlockSource`". **It does not.** `replaceContentRun` (`services/MarkdownBlockTokenizer.kt:568-635`) is the delivered mechanism and it is strictly *more* correct:

- `replaceBlockSource` (`MarkdownBlockTokenizer.kt:391-400`) still addresses the edit **by block index** and returns a raw string — a keystroke that re-splits the block window leaves the index stale, which is *precisely* the duplication vector (old fragments survive while the writer hits a drifted slot). Switching to it would NOT have fixed the bug.
- `replaceContentRun` locates the previous edited source **by text** (`content.indexOf(prevRaw, editingAnchorByte)`) and replaces exactly those contiguous bytes (`replaceRange(startByte, endByte, newRaw)`), then re-tokenizes only the affected window. Its KDoc contract (`:563-565`) is that the result is **byte-identical to a fresh full `tokenize`** of the patched content — unit-tested.

**Proof (typing a second line into a heading no longer duplicates):**
- `Phase243MarkdownEditorDuplicationTest` now has **9** tests (8 phase-243 + 1 added this phase), all green:
  - New `typing a second line into a heading block never duplicates it` — `EditorSim("# Title\n\nBody paragraph\n\nTail", editingBlock=0)`; types `"# Title\nSecond line"` then `"# Title\nSecond line again"`; asserts content byte-exact, `assertConsistent` (== fresh `tokenize` for content/blocks/candidates), heading + edited line each appear exactly once, neighbours intact.
  - `second keystroke after a block window resplit never duplicates` (the phase-243 reproduction).
  - `replaceContentRun matches a full re-tokenize on random docs` (800 probes) and `replaceContentRun agrees with replaceBlock for a single block run` (500 probes).
- `Phase151MarkdownMainThreadPerfTest` source pin (`:374-375`) asserts the editor contains **no** `replaceBlockSource(` call and pins `replaceContentRun(doc, at, endByte, newRaw)` as the keystroke primary (`:379`).

**Verdict: TRULY FIXED** (the summary's strict claim — "only fully re-tokenize on Done" is also satisfied: the editor only does window re-tokenization per keystroke; full re-tokenize happens on external value adoption `:148-156`, and the fallback edge stays window-scoped).

### 2. Paper texture dots — FIXED (delivered by phase-247; no phase-246 code change)

| Claimed cause line | Actual cause line | Fixed line at HEAD |
|---|---|---|
| `PaperTextureStrengthPolicy.grainDrawAlpha` `MIN_ALPHA=0.02f` floor | `e9dec37^:PaperTextureStrengthPolicy.kt:40` | `HEAD:PaperTextureStrengthPolicy.kt:77-79` — `grainDrawAlpha = if (clamp(strength)==0) 0f …`; `grainScale` `:89-91` same; `shaderGain` `:103-105` defensive (was already 0); `shaderStrength` `:94` (already 0). Anchors byte-identical: `grainDrawAlpha(50)==0.045f`, `grainScale(50)==shaderGain(50)==1f` |

- The summary's exact ask — *"return `0f` exactly at strength 0"* — is **present at HEAD**, landed in phase-247 (`PaperTextureStrengthPolicy.kt:31-45` KDoc: "Phase 247 — TRUE ZERO at strength 0"). The `MIN_ALPHA = 0.02f` constant remains only as the lerp base for strengths 1..100 (`:58`), matching the phase-247 spec (0→1 dial jump is intentional).
- **Call sites honor 0** (verified):
  - Cached-tile raster path: `AnnotationCanvas.kt:1053-1054` `grainScale(paperTextureStrength)` → `drawPaperCard(grainScale=…)` `:2998/:3074/:3173` → `drawPaperGrain` `:4929-4949` draws with `alpha = scale.coerceIn(0f,1f)` (`:4945`) → **0 at strength 0**, additive boost only when `scale > 1` (`:4947`).
  - Wet AGSL shader: `AnnotationCanvas.kt:5967-5970` `uPaperGrain = wetCanvasEngine.brushParams.paperGrain * shaderGain(paperTextureStrength)` → **0 at strength 0**.
- **Tests:** `PaperTextureStrengthZeroTest` (10: `grainDrawAlpha(0)==grainScale(0)==shaderGain(0)==shaderStrength(0)==0f`, 50-anchor `0.045f` float32 bit-identity `0x3d3851ec`, unity 50 scales, ceiling 100 `0.07f`, monotonic rise, clamp `-5→0 / 999→0.07`) + `PaperTextureStrengthPolicyTest` updated off the old floor. All green.

**Verdict: TRULY FIXED — "already fixed, no code change" (the phase-246 snapshot in the prompt predates phase-247; the fix now lives in the tree).**

### 3. Canvas page rotation — FIXED, WHOLESALE (stronger than the claimed default-flip)

| Claimed cause line | Actual cause line | Fixed line at HEAD |
|---|---|---|
| `canvasTwistEnabled` default `true` | `e9dec37^:SettingsManager.kt:249` (`prefs.getBoolean("canvas_twist_enabled", true)`) | Wholesale removal (`e9dec37`, + review fix `2709453`): `services/CanvasRotationPolicy.kt` **deleted** (134 lines); `SettingsManager` has zero `canvas_twist`/`canvasRotationDegreesForPage` (prefs `canvas_twist_enabled`/`canvas_rotation_<page>` gone); `checkstate` below |

- `grep 'canvasTwist\|CanvasRotationPolicy\|canvas_rotation\|canvasRotation\|rotationDegreesForPage\|twistEnabled'` across `app/src/main` + `app/src/test`: **0 hits** (single leftover mention is a comment in `Phase223PerspectiveGridPolicyTest.kt:180` noting the rotation tests "were deleted with CanvasRotationPolicy.").
- The one remaining "rotation" surface is the **per-sticky-note / per-embedded-media** rotation (`note.rotationDegrees`, `embed.rotationDegrees`, `CanvasItemRotationMath`, `SelectionRotationHandle`) — a distinct pre-existing feature that phase-243 explicitly kept and the phase-243 report documents as kept. Grep of `rotationZ|rotationAngle` in `AnnotationCanvas.kt` shows only these.
- Two-finger handler is now classic pinch-zoom+pan only: `AnnotationCanvas.kt:1400-1425` uses `event.calculateZoom()` + `event.calculatePan()` and never `calculateRotation`. The `import androidx.compose.foundation.gestures.calculateRotation` at `:14` is an **orphaned unused import** (cosmetic; zero behavior — kotlinc/lint warn only, no error). Left untouched as neutral; noted for cleanliness only.
- Deleted with the feature: `Phase240RotationGateTest.kt` (11 tests), the 5 `CanvasRotationPolicy` tests inside `Phase223PerspectiveGridPolicyTest`, and the Paparazzi `rotatedCanvas` snapshot. `EditorScreen` lost the Canvas-Twist toggle + Rotated-N°-Reset rows (`EditorScreen.kt` — zero `twist` hits at HEAD).

**Verdict: TRULY FIXED — and removal is strictly stronger than the claimed "flip default to false" (feature gone, not merely opt-in).**

### 4. Minimap off-screen — FIXED (delivered by phase-244 + phase-248; no phase-246 code change)

| Claimed cause line | Actual cause line | Fixed line at HEAD |
|---|---|---|
| Minimap anchored to `LocalConfiguration.screenWidthDp/screenHeightDp` | `e9dec37^:AnnotationCanvas.kt:3420-3423` | `HEAD:AnnotationCanvas.kt:3498-3499` — `val paneW = canvasBoxW; val paneH = canvasBoxH`; `canvasBoxW/H` captured from the canvas's own `BoxWithConstraints` scope `:2560-2561` (`with(LocalDensity.current){ maxWidth.toPx() }` / `maxHeight.toPx()`); `defaultAnchorBottomEnd(screenW = paneW, screenH = paneH, …)` `:3513-3519`; drag `pointerInput(minimapDraggable, minimapWidthPx, minimapHeightPx, paneW, paneH)` `:3544` + `constrainWithinSafeArea(…, paneW, paneH, …)` `:3552-3557`; `WindowInsets.safeDrawing` retained `:3530-3534` |

- The prompt's step-1.4 snapshot (3358-3410 reading `LocalConfiguration`) **predates** the phase-248 review that bound every remaining minimap-block `screenW/screenH` reference (zoom viewport, zoom-to-fit, both 2D-map `pointerInput` keys, pan/pinch, viewport frame) to `paneW/paneH`. At current HEAD there are **ZERO** `LocalConfiguration.current.screenWidthDp/screenHeightDp` reads in `AnnotationCanvas.kt` (only two comments mention the phrase `:2559/:3489`, both explaining why device dims must NOT be used).
- Split/freeform math pinned: `Phase248MinimapPaneSizeTest` (11) — `pane-vs-window anchor` (600dp pane inside 1200dp window ⇒ `defaultAnchorBottomEnd(600,…)` ≠ window anchor; the old window math placed the map at x≈1064 past a 600px pane), oversized-widget clamp, plus source pins asserting `val paneW = canvasBoxW`/`paneH = canvasBoxH` and the dragged keys. In a 700×700 freeform the map is always `x ≤ paneW − mapW − margin`.

**Verdict: TRULY FIXED — "already fixed, no code change".**

---

## Gap closed this phase

### Gap 1 (CRITICAL to any verification): HEAD did not compile
`6fb1fc1` ("fix: drawing dots + wrong-password on restart") introduced a **syntax error** in `ui/screens/LockScreen.kt:126` — the wrapped KDoc comment lines `:120-126` (`Phase 255 (lock bug): trim the input…`) lost the `//` prefix on the final continuation line (`because only outer whitespace is removed.` parsed as code → `Expecting an element` at `126:55`/`:65`). Every phase-243/244/… claimed fix is only as good as the tree it lands in; a tree that cannot compile can neither be verified nor released.
- **Fix applied (1 char that was not present):** restored the `//` prefix on the continuation line (`LockScreen.kt:126`). Comment semantics unchanged. All builds/tests now green.
- Root cause of the syntax slip: likely a bad merge/word-wrap of the comment block in `6fb1fc1`, not a logic error — no behavior change.

### Gap 2 (verification hardening): heading second-line regression not directly pinned
The phase-243 suite reproduced resplit-duplication on paragraphs/lists, but the user's spoken symptom — *typing a second line into a block's editor (Enter) duplicates rows* — was not pinned on a **heading** block.
- **Fix applied:** new test `typing a second line into a heading block never duplicates it` in `Phase243MarkdownEditorDuplicationTest` (the file's 9th test). Explicitly simulates `# Title` → Enter → `Second line` → `Second line again` with `assertConsistent` (== fresh full `tokenize`) on every keystroke and once-only assertions.

---

## Step 3 — Proof (all green)

| Check | Result |
|---|---|
| `gradle :app:testDebugUnitTest` | **3659 / 0 failures / 0 errors / 0 skipped** (337 test files; baseline 3658 + 1 new heading test). Relevant classes green: `Phase243MarkdownEditorDuplicationTest` (9), `PaperTextureStrengthZeroTest` (10), `PaperTextureStrengthPolicyTest` (7), `Phase248MinimapPaneSizeTest` (11), `Phase244InkBarDrawingPolicyTest` (5), `Phase151MarkdownMainThreadPerfTest`, `Phase223PerspectiveGridPolicyTest`. |
| `gradle :app:assembleDebug` | BUILD SUCCESSFUL (57 tasks) |
| `gradle :app:assembleRelease` | BUILD SUCCESSFUL (85 tasks, R8 + signed, fail-closed keystore env present) |
| `gradle :app:lintDebug` | BUILD SUCCESSFUL — **0 errors / 0 fatal**, 106 pre-existing warnings (matches the phase-253 baseline) |

## Claim vs. reality table (the 4 rows)

| # | Claimed cause | Claimed fix | Actual cause (`e9dec37^`) | Actual fix at HEAD | Fixed? |
|---|---|---|---|---|---|
| 1 | `replaceBlock` per keystroke splits Sibling blocks → duplicates | switch live typing to `replaceBlockSource` | `HybridMarkdownEditor.kt:105` per-keystroke `replaceBlock(doc, staticIndex, …)` | `replaceContentRun` byte/text-run anchor `:127` (+ guard-anchored fallback `:139`). **Mechanism differs from the summary** — `replaceBlockSource` is index-based too and would NOT have fixed it; `replaceContentRun` is byte-run anchored. Verified: content == fresh `tokenize` on every keystroke (9-test suite, 800+500 random probes + heading reproduction). | **YES** |
| 2 | `grainDrawAlpha` `MIN_ALPHA=0.02f` floor → dots never vanish | return `0f` at strength 0 | `PaperTextureStrengthPolicy.kt:40/52` `grainDrawAlpha(0)=0.02`, `grainScale(0)≈0.444` | TRUE ZERO early-returns at `clamp==0` (`:77-79/:89-91/:103-105`), delivered by **phase-247**; 50-anchors byte-identical; call sites (`drawPaperGrain` alpha, AGSL `uPaperGrain`) honor 0. Pin: `PaperTextureStrengthZeroTest` (10). | **YES** (already fixed at HEAD — no phase-246 code change) |
| 3 | `canvasTwistEnabled` defaulted `true` | flip default to `false` | `SettingsManager.kt:249` default `true` | **Wholesale removal** (`e9dec37`): policy/pref/gesture/UI/tests/snapshot deleted; 0 hits for the feature surface; two-finger = classic zoom+pan. Stronger than the claimed default-flip. | **YES** |
| 4 | Minimap uses `LocalConfiguration` device screen | use container `BoxWithConstraints` bounds | `AnnotationCanvas.kt:3420-3423` device dims | Pane-bound: `paneW/paneH = canvasBoxW/H` from `BoxWithConstraints` (`:2560-2561` → `:3498-3519`), drag + clamp on pane dims, safe insets kept; 0 device-dims reads in the file. Delivered by **phase-244/248**. Pin: `Phase248MinimapPaneSizeTest` (11). | **YES** (already fixed at HEAD — no phase-246 code change) |
| GAP | — | — | — | `LockScreen.kt:126` orphaned comment line (missing `//`) broke compilation at HEAD | **FIXED this phase** |

## Files touched this phase
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/LockScreen.kt` — restored `//` on the phase-255 comment continuation (1-char-equivalent). **Compile-blocking defect at HEAD.**
- `app/src/test/java/com/authorss81/noteflow/Phase243MarkdownEditorDuplicationTest.kt` — +1 heading two-line regression test (9 total).

## Constraints checklist
- No Room schema / migration. ✅ (no DB touched)
- No `.github/workflows/` edits. ✅
- No new dependencies; all tests pure JVM. ✅ (added test uses existing tokenizer API)
- `verification-metadata.xml` untouched. ✅
- Base-APK-size rule intact; no deps added. ✅
- Every finding/claim cited with `file:line` or `commit:file:line`. ✅ (above)
- Dead/leftover note (rot): orphaned unused `import …gestures.calculateRotation` at `AnnotationCanvas.kt:14` — cosmetic only, no behavior, left as-is (neutral).