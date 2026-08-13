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

1. Create `workspace/phase-NN/` directory (zero-padded, e.g. `phase-02`).
2. Add a `PROMPT.md` — the precise instruction the opencode agent receives.
   Be explicit: files to touch, constraints ("use Hilt", "targetSdk 34", "do not
   remove existing code"), and the definition of done ("app builds with
   `./gradlew assembleDebug`").
3. Push the folder. The cron (`*/30 * * * *`) wakes, the `select-phase` job
   picks the lowest-numbered phase without a `.done` marker, and runs it.
4. The pipeline runs it, reviews via `reviewer` subagent, fixes findings,
   commits, and pushes. On success the phase dir gets a `.done` marker and the
   next cron tick advances to the next phase.

## Phase prompt style guide

Good PROMPT.md examples:

```markdown
# Phase 2: Notes with Room

- Add androidx.room (version catalog entry in gradle/libs.versions.toml).
- Create Note entity, NoteDao, AppDatabase singleton.
- Wire a ViewModel + RecyclerView list in MainActivity.
- Do NOT remove or rename existing classes from phase-01.
- minSdk 24, targetSdk 34, compileSdk 34, Kotlin 2.x.
- Definition of done: ./gradlew assembleDebug succeeds.
```

## Auto-advance

The workflow's cron schedule (`*/30 * * * *`) wakes the pipeline periodically.
The "Determine phase" step scans `workspace/` and picks:
1. a `.deferred` phase first (rate-limit retry), else
2. the lowest `phase-NN` without a `.done` marker.
If none exist it exits idle (~0 minutes). Phases chain automatically until the
plan is done.