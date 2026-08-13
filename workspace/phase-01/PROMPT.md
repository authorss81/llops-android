# Phase 1: Android project scaffold

Build the initial Android app skeleton in THIS repository.

## Requirements
- Set up a Gradle project (Kotlin) at the repo root.
- Use the Android Gradle Plugin with Kotlin DSL build files.
- **minSdk 24, targetSdk 34, compileSdk 34.**
- Use a version catalog (`gradle/libs.versions.toml`).
- Create `app/src/main/AndroidManifest.xml` with an `ApplicationId`
  `com.llops.android`.
- Create `MainActivity.kt` that renders a single `TextView` with the text
  `Hello from LLOPS`.
- Include a `settings.gradle.kts`, root `build.gradle.kts`, and
  `app/build.gradle.kts`.
- Add `gradle.properties` with `android.useAndroidX=true`.
- Do NOT add third-party dependencies beyond the AndroidX core-ktx already
  commonly used. Keep it minimal.

## Android SDK in CI
- The workflow already installs `platforms;android-34` and `build-tools;34.0.0`
  via `android-actions/setup-android` before the phase runs — do NOT edit the
  workflow file to install the SDK.

## Definition of done
- `./gradlew assembleDebug` succeeds on the Ubuntu runner using the
  pre-installed Android SDK at `$ANDROID_HOME` / `$ANDROID_SDK_ROOT`.

## Constraints
- Do not delete or rename anything created by a previous phase.
- Prefer simple, idiomatic Kotlin over cleverness.