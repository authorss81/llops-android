# Phase 3: Honesty — remove or wire dead/fake features (FALSE-FEATURE AUDIT) [DONE]

You are working on **InkFlow/Noteflow**, an offline-first encrypted notes + canvas
Android app. This phase is about HONESTY: features that look real but are dead
code or stubs must either become real or be removed/relabeled. Do NOT add new
features. Build must stay green.

## Verified dead/fake code (from audit — fix each)

### 1. Fake "LibMyPaint Native Studio Engine"
`app/src/main/cpp/mypaint_jni.cpp` (88 lines) is a STUB: it only stores
x/y/pressure in a C++ struct, NEVER calls libmypaint, renders nothing, and has no
JNI method to retrieve pixels. `CMakeLists.txt` compiles only this stub. Yet
`LibMyPaintJni.kt` reports `isNativeLibraryLoaded == true`, and the settings
dialog (`BrushEngineSettingsDialog.kt`) shows a "C++ NDK / LibMyPaint Studio
Engine" option that renders nothing. Its consumer `LibMyPaintBrushEngine` (via
`BrushEngineFactory`) is never called — double-dead.

**Fix (choose the honest path):**
- Preferred: remove the fake native engine option from the UI and delete the
  stub JNI (or keep the .cpp as an explicitly-empty placeholder only if the build
  requires it). The Kotlin `LibMyPaintJni`/`LibMyPaintBrushEngine` can remain as
  a cleanly-labeled "not implemented" tier, but the UI must NOT show an engine
  that renders nothing.
- The `BrushEngine` multi-engine facade (`BrushEngine.kt`), `BrushEngineFactory`,
  and `settings.renderingEngineOverride` (SettingsManager.kt:76, read ONLY by
  HardwareProfiler.kt:116, never by the render path) are ornamental. Verified:
  `createEngine` (BrushEngine.kt:130) has zero callers; the canvas uses a
  DIFFERENT class `WetBrushEngine` directly (AnnotationCanvas.kt:353-370,607-609,
  etc.). The `BrushEngineSettingsDialog` IS reachable (EditorScreen.kt:1332,1346)
  but its selection is inert — no render effect.
  Either wire `renderingEngineOverride` so selecting an engine actually changes
  rendering, or remove the engine selector UI and the dead setting.

### 2. Dead `ShapeRecognitionHelper` (roadmap §26.6 lies)
`app/src/main/kotlin/com/authorss81/noteflow/services/ShapeRecognitionHelper.kt`
has ZERO call sites. Roadmap claims shape auto-snap/straightening is "integrated
into AnnotationCanvas" — it isn't.

**Fix:** Wire it into `AnnotationCanvas` so the existing shape tools (line/rect/
ellipse/arrow) snap/straighten on draw-end, OR delete it and remove the roadmap
claim. Prefer wiring if the helper is functional; it's the honest fix.

### 3. Dead + misnamed `FtsSearchEngine` (roadmap §26.7 lies)
`FtsSearchEngine.kt` is DEAD (zero callers; loops in-memory with `contains()`
at :50-84, not FTS5). The current search path (`NoteRepository.kt:176-184`,
`decryptPageIfNeeded` :700-714) decrypts EVERY active page's title+extractedText
on every keystroke (300ms-debounced `LaunchedEffect` in HomeScreen.kt:148-157).

**Fix (honest, no new deps):** EITHER remove `FtsSearchEngine.kt` and the
"Implemented via FtsSearchEngine" roadmap claim, OR — better and still cheap —
keep the current decrypted search but: (a) cache the decrypted search corpus in
memory (invalidate on save), (b) move the decrypt+search to `Dispatchers.IO`, so
per-keystroke search stops decrypting the entire vault. Remove the misleading
FTS name. Do NOT add a new FTS5 schema (deferred, needs approval).

### 4. `HandwritingRecognitionService` is a dictionary-hash stub (roadmap §26.1 lies)
`recognizeStrokesToText`/`transcribeWord` (`HandwritingRecognitionService.kt`
:30, :104-159) maps aspect-ratio/point-count into a fixed dictionary hash →
deterministic garbage. It is NOT recognition. Verified: `HandwritingToTextDialog`
has ZERO callers — the feature is not even reachable in the UI, and the dialog's
"copy" writes recognized text to the clipboard without `ClipboardGuard.recordCopy()`.

**Fix (honest, small):** Since the feature is unreachable AND fake:
- Preferred: remove the `HandwritingToTextDialog` trigger/entry points and the
  dialog from the UI entirely (or keep the dialog only if you also wire it), and
  delete `HandwritingRecognitionService.kt` OR reduce it to an honest stub that
  returns null/empty with a "not implemented" label rather than confidently-wrong
  dictionary words.
- Do NOT claim it recognizes handwriting. (A real ML-Kit implementation is
  deferred — needs user approval for the dependency.)

### 5. `baseline-prof.txt` lingering artifact + roadmap lies
`app/src/main/baseline-prof.txt` still exists but roadmap §21.3 claims it was
"DELETED IN COMMIT 8ab42a2" and §32.9 marks baseline profiles `[x]` done. Both
are false — the ArtProfile Gradle tasks are disabled (`app/build.gradle.kts` ~:123).

**Fix:** Delete `app/src/main/baseline-prof.txt`. Update `ROADMAP.md` §21.3 and
§32.9 to accurately state baseline profiles are NOT wired (deferred).

### 6. `InkApiTest.kt` silent-pass persists
It still catches `UnsatisfiedLinkError` and prints "Skipping" instead of failing
loudly (roadmap §21.10/28.4 claim).

**Fix:** This test runs in the pure-JVM `testDebugUnitTest` suite where the
androidx.ink native libraries are NOT available (that's inherent — do NOT
blanket-fail the whole unit-test gate, or every phase fails CI). Make it fail
loudly ONLY on what a JVM run can verify: assert the ink model classes
(`Brush`, `Stroke`, `MutableStrokeInputBatch`) are on the classpath and
constructible, and only skip the JNI-execution step with an explicit printed
reason. If a native-load assertion is truly required, move it to an instrumented
/Robolectric test instead — never a blanket fail in the JVM suite.

### 7. Stale `AGENTS.md` claims
`AGENTS.md` "Known broken things" section (AGENTS.md:32-36) is stale — the
"AGSL wet-mix shaders unwired (zero call sites)" clause (:35) is refuted by the
code (WetBrushEngine is invoked throughout AnnotationCanvas.kt).

**Fix:** Scope the AGENTS.md cleanup to ONLY what THIS phase actually changes:
correct the `pointsJson`-is-plaintext claim (:34 — it's field-encrypted now, and
phase-02 fixes the restore re-key gap), fix the AGSL-unwired clause (:35), and
note the items this phase removes (libmypaint stub, shape-snap, FTS, handwriting
stub). Do NOT claim AGSL wet-mixing/WebDAV are "fully real and verified" in
AGENTS.md — those are completed by later phases (04 and 06) and R8/FLAG_SECURE
are already true; keep the doc's remaining false-claims list accurate for what
is actually done TODAY after this phase runs.

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes.
- No dead feature is still presented as working in the UI.
- ROADMAP.md/AGENTS.md claims match reality (no lies about features).

## Constraints
- No new third-party dependencies (ML-Kit, libmypaint, etc. are deferred).
- Do NOT change the DB schema.
- Do NOT edit `.github/workflows/`.
- When in doubt between "wire it" and "remove it", prefer removing/relabeling —
  honest absence beats fake presence.
