# Phase 239 — Fix Phase 235 (Instrumented Tests) Properly [FIX]

## Goal
Fix the 4-hour timeout failure of `phase-235` (instrumented Android tests) by addressing the THREE root causes:
1. **x86_64 emulator boot fails** (no HW acceleration on GitHub runners)
2. **Edit tool had JSON parse errors** causing "multiple matches" and "no changes" failures
3. **Dependency verification failed** for `androidTest` runtime classpath (`javawriter-2.1.1.pom` + 13 others not in `gradle/verification-metadata.xml`)

## Context — From `logs/phase-235.log:3260-3268` (run 33237785322)
```
FAILURE: Build failed with an exception.
Execution failed for task ':app:processDebugAndroidTestManifest'.
> Dependency verification failed for configuration ':app:debugAndroidTestRuntimeClasspath'
  14 artifacts failed verification:
    - javawriter-2.1.1.pom (com.squareup:javawriter:2.1.1) from repository MavenRepo
```

**And `phase-235.log:460`:** `x86_64 emulation currently requires hardware acceleration!`

## Files to Fix

### 1. Skip the emulator (no HW acceleration on GitHub runners)
- Don't use `gradle connectedDebugAndroidTest` with a real AVD — impossible without KVM
- Instead: add `org.gradle.jvmargs=-Xmx4g` to `gradle.properties` if memory is an issue
- Use only the `androidx.compose.ui:ui-test-junit4` for **unit** tests (runs on JVM, not AVD)
- Move ALL `composeRule.setContent { ... }` tests to `app/src/test/` (not `androidTest/`) so they run in `gradle testDebugUnitTest` (no emulator needed)
- Keep `androidTest/` empty (or remove the `ui-test-junit4` testImplementation that requires it)

### 2. Fix `gradle/verification-metadata.xml` for new androidTest deps
- The 14 missing artifacts (including `com.squareup:javawriter:2.1.1`) come from `espresso` transitive deps
- Add them to `gradle/verification-metadata.xml` with their SHA-256 hashes
- OR: don't add `espresso` / `androidx.compose.ui:ui-test-junit4` to `androidTestImplementation` (use only `testImplementation` which is already in verification)

### 3. Skip the failing edit tool calls
- The agent's edit tool failed with "JSON Parse error: Unterminated string" — use file writes instead
- Don't try to add new dependencies that require verification updates
- Instead, use ONLY existing dependencies for tests

## Implementation Plan (SAFE Path)

### Step 1: Add only testImplementation deps (no new verification needed)
```kotlin
// app/build.gradle.kts dependencies { ... } add:
testImplementation("androidx.compose.ui:ui-test-junit4") // in composeBom
testImplementation("androidx.compose.ui:ui-test-manifest") // in composeBom
debugImplementation("androidx.compose.ui:ui-test-manifest") // for createAndroidComposeRule
```

These are already in `composeBom 2024.12.01` (`gradle/libs.versions.toml:88`) so no new verification entries needed.

### Step 2: Create tests in `app/src/test/` (NOT `app/src/androidTest/`)
The 5 test files in `phase-235/PROMPT.md`:
- `EditorScreenCanvasDrawTest.kt`
- `ColorPickerBottomSheetTest.kt`
- `LassoSelectionTest.kt`
- `MarkdownEditorTest.kt`
- `WetShadeRegressionTest.kt`

These should be in `app/src/test/kotlin/com/authorss81/noteflow/` (runs on JVM, no AVD).

### Step 3: Don't create `app/src/androidTest/` directory
- `phase-235/AUDIT_REPORT.md` confirmed `app/src/androidTest/` does NOT exist — keep it that way
- All Compose tests can use `createComposeRule()` from `ui-test-junit4` in `app/src/test/`

### Step 4: Verify DoD
- `gradle testDebugUnitTest` green with 3420+ tests (existing) + new 5 tests
- `gradle :app:assembleDebug` + `assembleRelease` green
- No new `gradle/verification-metadata.xml` entries needed
- No `app/src/androidTest/` directory created
- All tests run on JVM (no emulator)

## Constraints
- **No emulator** — no `gradle connectedDebugAndroidTest` (GitHub runners lack HW acceleration)
- **No new verification-metadata entries** — use only already-verified deps
- **No schema change** — pure test additions
- **No workflow edits** — keep `llops.yml` untouched
- **No `app/src/androidTest/`** — keep it absent

## DoD
- `app/src/test/kotlin/com/authorss81/noteflow/` has 5 new test files
- `gradle :app:testDebugUnitTest` green with 3420+ + 5 new tests
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- `gradle/verification-metadata.xml` UNCHANGED
- `app/src/androidTest/` does NOT exist
- `workspace/phase-239/REPORT.md` with test count + execution time + before/after

## Timeout
90 minutes (only adds test files, no new deps)
