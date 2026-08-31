# Phase 246 — Verify 4 claimed fixes (markdown duplication, paper dots, rotation, minimap) + close any remaining gaps

## Goal
Strictly verify that the 4 issues described in the phase-243 summary were actually caused by the stated roots and are now **truly fixed**. If any gap remains, fix it. This is a VERIFICATION phase — be the strictest reviewer, not a cheerleader.

## The 4 claimed root causes / fixes to verify (user-provided summary)

1. **Markdown duplication — `HybridMarkdownEditor.kt` called the eager, splitting tokenizer (`replaceBlock`) on every keystroke.** Since headings/list runs are single-line blocks by construction, typing a second line into a block's raw editor immediately split it into new sibling blocks — but `editingBlock` only shielded one of them, so the rest rendered as duplicate-looking rows right below the still-open editor. Claimed fix: switch live typing to the (already-existing but unused) non-splitting `replaceBlockSource`, and only fully re-tokenize on "Done."

2. **Paper texture dots never fully disappear — `PaperTextureStrengthPolicy.grainDrawAlpha` had a hardcoded `MIN_ALPHA = 0.02f` floor even at dial position 0**, so grain never reached true zero (~44% of default remained). Claimed fix: return `0f` exactly at strength 0.

3. **Unwanted page rotation — the two-finger-twist canvas rotation gesture (`canvasTwistEnabled`) defaulted to `true`**, unlike every other opt-in gesture (ruler, stabilizer, shape auto-snap, etc.). Claimed fix: flip the default to `false`.

4. **Minimap outside screen — it anchored itself using `LocalConfiguration.screenWidthDp/screenHeightDp` (the full device screen) instead of its own container's measured size**, so in split-screen/multi-window/freeform layouts the computed corner falls outside the actual visible area. Claimed fix: use the local `BoxWithConstraints` bounds, matching how the ink bar already does it.

---

## What to do — strict verification protocol

### Step 0: Reproduce the claimed root cause on the pre-fix tree

Check out the parent commit of `e9dec37`/`a4ebeca` (the markdown/rotation fix) and confirm:
- `HybridMarkdownEditor.kt` line history: did live typing call `MarkdownBlockTokenizer.replaceBlock` on every keystroke? Grep `git log -p -- app/src/main/kotlin/com/authorss81/noteflow/ui/components/markdown/HybridMarkdownEditor.kt`
- `services/MarkdownBlockTokenizer.kt`: does `replaceBlockSource` exist and was it unused before phase-243? (`grep -n replaceBlockSource`)
- `services/PaperTextureStrengthPolicy.kt` at `e9dec37^`: `MIN_ALPHA = 0.02f` and `grainDrawAlpha(0) == 0.02`?
- `services/SettingsManager.kt` + `services/CanvasRotationPolicy.kt` at `e9dec37^`: `canvasTwistEnabled` default true? Where is default set?
- `ui/components/AnnotationCanvas.kt` minimap block at `e9dec37^`: grep `LocalConfiguration.current` + `screenWidthDp` vs `BoxWithConstraints`.

Record file:line for each. If the summary misstates the cause, say so honestly — do not bend evidence.

### Step 1: Verify each fix at HEAD

For each of the 4, at HEAD (`workspace/phase-246` context), confirm byte-exact fix:

1. **Markdown**: Read `HybridMarkdownEditor.kt:emitBlockEdit`. Current HEAD uses `MarkdownBlockTokenizer.replaceContentRun` anchored via `editingAnchorByte` + `doc.content.indexOf(prevRaw, editingAnchorByte)` with fallback to `replaceBlock`. **This is NOT `replaceBlockSource`.** Explain the discrepancy: is `replaceContentRun` equivalent to "non-splitting replaceBlockSource on every keystroke"? Prove that typing a second line into a heading block no longer duplicates — either via `Phase243MarkdownEditorDuplicationTest` (7 tests) or a fresh reproduction (simulate `EditorSim` with two-line edit, assert `doc.content` == fresh `tokenize(value).content` and `doc.blocks` == fresh). Show `HybridMarkdownEditor.kt:line` and `MarkdownBlockTokenizer.kt:replaceContentRun:568` evidence.

2. **Paper dots**: Open `services/PaperTextureStrengthPolicy.kt`. Current HEAD still has `MIN_ALPHA = 0.02f` and `grainDrawAlpha(0) = 0.02`. The claimed `0f at strength 0` is **NOT present**. Confirm via grep and by evaluating `grainDrawAlpha(0)` / `grainScale(0)`. If not fixed, FIX IT NOW: `grainDrawAlpha(0)` and `shaderGain(0)` must return exactly `0f` so grain + wet shader truly vanish. Preserve anchoring at 50 → scale 1.0, but strength 0 → 0. Implement as early-return `if (clamp(strength)==0) return 0f` or equivalent. Pin with a test `PaperTextureStrengthZeroTest` asserting `grainDrawAlpha(0)==0f`, `grainScale(0)==0f`, `shaderGain(0)==0f`, and `grainDrawAlpha(50)` still equals pre-fix default (0.045). Also verify call sites: `AnnotationCanvas` grain tile alpha uses `PaperTextureStrengthPolicy.grainScale`/`grainDrawAlpha`, and wet `uPaperGrain` uses `shaderGain`; both must honor 0. Test green required.

3. **Rotation**: Verify wholesale removal at HEAD: `services/CanvasRotationPolicy.kt` deleted, `SettingsManager` has no `canvasTwistEnabled`/`canvasRotationDegreesForPage`, `AnnotationCanvas.kt` has no `rotationDegrees`/`rotationZ`/`canvasTwistEnabled`, `EditorScreen.kt` has no twist toggle/degree pref. Grep `canvasTwist|CanvasRotationPolicy|rotationZ|canvas_rotation` must be 0 hits outside `workspace/` + `docs/` history notes. This exceeds "flip default to false" — document that deletion is stronger and correct. No fix needed unless stray pref read remains (migrate away with `prefs.contains` cleanup if found).

4. **Minimap**: Read `ui/components/AnnotationCanvas.kt:3358-3410`. Current HEAD still computes `val configuration = LocalConfiguration.current; val screenW = …screenWidthDp…; val screenH = …screenHeightDp…` and `defaultAnchorBottomEnd(screenW, screenH, …)`. This is STILL the full-screen metric, not the local `BoxWithConstraints` bounds. The ink bar's dock (`EditorScreen.kt:2712 BoxWithConstraints`) does use `maxWidth/maxHeight`, but minimap does not — so the claimed fix is **NOT present**. Fix it: wrap the minimap's anchor computation in the actual canvas container's `BoxWithConstraints` (or pass `maxWidth/maxHeight` from the surrounding `BoxWithConstraints` if already present) and compute `screenW/screenH` as the container's measured width/height (`with(density){ maxWidth.toPx() }`), constrained by `WindowInsets.safeDrawing` (already present). Keep drag `constrainWithinSafeArea` using the same container bounds, not `LocalConfiguration`. Ensure split-screen/multi-window freeform (e.g., 700×700) keeps minimap inside visible area. Pin with a source test asserting `AnnotationCanvas.kt` minimap block no longer reads `LocalConfiguration` and uses `BoxWithConstraints`.

### Step 2: Close gaps — only if verification failed, apply minimal surgical edits

- Paper: edit `PaperTextureStrengthPolicy.kt` only.
- Minimap: edit `AnnotationCanvas.kt` minimap block only (no other layout churn).
- Do NOT reintroduce `CanvasRotationPolicy.kt`.
- Do NOT revert `HybridMarkdownEditor.kt` to `replaceBlockSource` unless you prove `replaceContentRun` is wrong (it is correct — document why).

### Step 3: Prove it

- `gradle :app:testDebugUnitTest` — must stay 3556+ green (new zero-test adds to count, no failures).
- `gradle :app:assembleDebug` + `assembleRelease` green (R8 + lintVital).
- `gradle :app:lintDebug` 0 errors.
- Each of the 4 gets a file:line table: claimed cause line vs. actual cause line vs. fixed line.

## Constraints
- No Room schema / migration.
- No `.github/workflows/` edits.
- No new heavy deps. Tests pure-JVM if possible.
- Base-APK-size rule intact (`verification-metadata.xml` untouched unless needed for test dep, then pin correctly).
- Every claim must cite `file:line` or `commit:file:line`.

## DoD
- `workspace/phase-246/REPORT.md` with strict verification table (4 rows: claim vs. reality vs. fixed? + evidence) and any fixes applied.
- If paper/minimap needed fixing, the fix is in this phase; otherwise REPORT states "already fixed, no code change" with proof.
- All 3 gradle checks green, REPORT committed, `.done` marker implied.
