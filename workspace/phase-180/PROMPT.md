# Phase 180: Real syntax highlighting for code blocks via highlighted-kt [NOT STARTED]

You are working on **InkFlow/Noteflow**. ROADMAP **21.8** honesty part shipped (code
blocks are honestly labeled "PLAIN TEXT (NO SYNTAX HIGHLIGHTING)" in
`MediaEmbedComponents.kt`), but the real highlighting was deferred: "REAL
HIGHLIGHTING VIA HIGHLIGHTED-KT **DEFERRED - needs user approval (new dependency).**"
USER APPROVAL IS GRANTED (explicitly approved 2026-08-20). This phase adds real
syntax highlighting to fenced code blocks using the **highlighted-kt** library
(current-maintained fork of the old `highlight.js` Kotlin port).

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## WORKFLOW RULE
Work in small steps; `git add -A && git commit -m "llops: phase-180 step N: <desc>" && git push`
after EVERY step. Never sit on uncommitted work.

## Step 1 - Dependency (commit it)
- Add `highlighted-kt` (JVM/Android) to `app/build.gradle.kts` dependencies +
  `gradle/libs.versions.toml` version catalog. Verify the artifact resolves and
  `gradle assembleDebug` still builds.
- Run `gradle dependencies` to confirm it is a pure Kotlin/JVM lib (no native,
  no network at runtime) and that it does not inflate the release APK
  meaningfully. NOTE: it adds a small jar; the base-APK-size rule must be
  re-verified in Step 3.
- COMMIT this step.

## Step 2 - Highlight code blocks in BOTH markdown renderers
- Apply syntax highlighting to fenced code blocks in `MarkdownRenderer.kt`
  (`:609-617` area) and `MarkdownPreviewScreen.kt` (`:1304-1312` area), i.e. both
  paths that render markdown. Highlight at parse/annotate time (pure JVM) and
  render with theme-aware colors (light + dark).
- Language detection: use the fence language tag; fall back to language
  auto-detection if available in highlighted-kt, else plain-text styling.
- Preserve the existing copy-to-clipboard / line behavior; highlighting must not
  change the copied text.
- COMMIT this step.

## Step 3 - Honest label + size check
- Update the "PLAIN TEXT (NO SYNTAX HIGHLIGHTING)" honesty label in
  `MediaEmbedComponents.kt` ONLY where it is now false (real highlighting is on).
  Keep honest copy for any renderer path that still can't highlight.
- Re-verify base APK size did not grow unreasonably and document before/after.
- COMMIT this step.

## Step 4 - Regression proof
- `gradle assembleDebug` green + `gradle testDebugUnitTest` green (except the
  pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure + the 2
  `B1Plat01ReleaseSigningTest` asserts, untouched).
- Pure-JVM tests: highlighted spans are emitted for a known fence (e.g. Kotlin
  `val x = 1`), unknown/absent language tag renders plain text without crashing,
  copy text is the un-highlighted raw source, light/dark theme colors resolve.

## Definition of done
- Fenced code blocks render with real syntax highlighting in both markdown paths,
  theme-aware, copy-paste still returns raw source, honesty label updated where
  true, base-APK size delta documented.
- `workspace/phase-180/REPORT.md`: dependency + version, integration points,
  before/after size, test list.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. The new dependency is acceptable (user
  approved); keep it out of the base-APK hot path if possible and keep the size
  delta small.
- No DB schema change. Never log decrypted content.
- If highlighted-kt does not resolve for Android/JVM, STOP and document the
  blocker in REPORT.md instead of vendoring a fork.