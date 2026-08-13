# LLOPS Android App — Phase Plan

This repo builds an Android app one phase at a time, run entirely by opencode
(DeepSeek free model) inside GitHub Actions.

A phase = a bounded, buildable increment. The pipeline runs a phase, reviews it
with a subagent, fixes findings, pushes, then proceeds to the next phase.

## Phase list

| Phase | Goal | Deliverable |
|-------|------|-------------|
| phase-01 | Project scaffold | Gradle project, manifest, MainActivity, buildable skeleton |
| phase-02 | Core feature A | e.g. local note list with Room database |
| phase-03 | Feature B | e.g. add/search/filter notes, view model |
| phase-04 | UI polish | Material design screens, themes |
| phase-05 | Persistence & tests | Unit tests for core logic, verification |
| phase-06 | Release hardening | ProGuard/minify, signing config, validation |

## How to add a phase

1. Create `workspace/phase-NN/` directory.
2. Add a `PROMPT.md` — the precise instruction the opencode agent receives.
   Be explicit: files to touch, constraints ("use Hilt", "targetSdk 34", "do not
   remove existing code"), and the definition of done ("app builds with
   `./gradlew assembleDebug`").
3. Push the folder. The pipeline auto-picks the lowest-numbered pending phase.
4. The pipeline runs it, reviews via `reviewer` subagent, fixes findings,
   commits, and pushes. Optionally auto-triggers phase-N+1 via `workflow_run`.

## Phase prompt style guide

Good PROMPT.md examples:

```markdown
# Phase 1: Scaffold

- Initialize an Android Gradle project in this repo root.
- Kotlin, minSdk 24, targetSdk 34, compileSdk 34.
- Use version catalog (libs.versions.toml).
- Create MainActivity showing "Hello from LLOPS" in a TextView.
- Do NOT add third-party dependencies yet.
- Definition of done: ./gradlew assembleDebug succeeds.
```

## Auto-advance

The `workflow_run` trigger re-runs this workflow whenever the previous run
finishes on `main`. The "Determine phase" step scans `workspace/` and picks the
next pending phase, so phases chain automatically until the plan is done.