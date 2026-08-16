# Phase 130: Project metadata & LLM plugin build-script alignment [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` and
`docs/ARCHITECTURE.md` first.**

**THE TASK:** verify and finish aligning the project's **metadata** and the
**LLM plugin's Gradle build script** so they match the project configuration
and build environment (system Gradle 8.13, no wrapper, GitHub Actions CI).

## What to do
- **Metadata alignment:** check for a `metadata.json` (project/plugin/app
  metadata file) — verify its `name`, `applicationId`/namespace
  (`com.authorss81.noteflow` / `com.aistudio.inkflow.app`), version, and
  capability fields match the actual Gradle configuration
  (`app/build.gradle.kts`, version catalog `gradle/libs.versions.toml`).
  Create/fix it so it is consistent and kept in sync with the build files.
- **LLM plugin build script:** inspect `plugins/llm/build.gradle.kts` — ensure
  it is **command-invocation compatible** with the CI/system Gradle
  environment (correct task names, no wrapper assumptions, dependencies
  resolvable from the catalog, correct module wiring, and it must not break
  `gradle assembleDebug` / `gradle testDebugUnitTest` for the whole project).
- If the LLM plugin is a **downloadable plugin** (per the base-APK-size hard
  rule), verify its build artifacts are produced/consumed correctly and that
  the base app does not embed it.
- Run the project builds to prove alignment; fix any breakage you find.

## Verification
- `gradle testDebugUnitTest` + `gradle assembleDebug` must pass (or a
  documented pre-existing-only failure, proven unrelated).
- Pure-JVM tests only where meaningful (metadata parsing/validation helper).
- `workspace/phase-130/REPORT.md` committed: what was checked, what was
  fixed (file:line), build outputs.

## Definition of done
- `metadata.json` matches the project configuration; `plugins/llm` builds
  under system Gradle without breaking the app; REPORT.md documents it.

## Constraints
- NO DB schema change. Do NOT edit `.github/workflows/`. Do not add new
  dependencies unless required (then justify in the commit). Never log
  keys/decrypted content. Keep `allowBackup=false`, `ClipboardGuard`,
  FLAG_SECURE intact. Respect the base-APK-size rule (LLM stays downloadable).