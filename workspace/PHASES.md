# LLOPS — InkFlow Android App — Phase Plan

This repo builds **InkFlow** (a.k.a. Noteflow), an offline-first, encrypted
notes + digital painting canvas Android app, entirely via the LLOPS auto-pipeline
(opencode agents running in GitHub Actions, cron-driven every 30 min).

The InkFlow source (from `inkflow (1).zip`) is the base. Phases 1-35 of the
app's own ROADMAP.md are largely done; this plan is the AUDIT-DRIVEN CONTINUATION
based on 3 batches of strict subagent audits (security, UX, false-implementations,
painting engine, free features).

## Phase list

| Phase | Goal | Source |
|-------|------|--------|
| phase-01 | App scaffold (DONE — the InkFlow source IS the scaffold) | scaffold |
| phase-02 | **Security: fix restore/sync data-loss** (cross-device stroke loss, DB auto-wipe, failed-restore brick, schema-version guard, plaintext backup) | Batch 1 security audit |
| phase-03 | **Honesty: remove/wire dead & fake features** (libmypaint stub, shape-snap, FTS, handwriting stub, baseline-prof, InkApiTest, AGENTS.md) | Batch 1 honesty audit |
| phase-04 | **Real AGSL watercolor/oil**: wet-on-wet alpha, pigment mixing, dirty-rect, wire/kill WetCanvasEngine grid | Batch 1 graphics audit |
| phase-05 | **UX/accessibility quick wins**: markdown back-save data-loss, 48dp targets, Snackbars, reduce-motion, haptics, status-bar polarity | Batch 1 UX audit |
| phase-06 | **WebDAV sync made real+safe+honest**: INTERNET permission, WAL checkpoint, transactional restore, HTTPS, honest copy | Batch 1 security audit |
| phase-07 | **Free painting features**: stabilizer, pressure curve, symmetry, color harmony, reference layer, paper textures, WebP | Batch 1 feature audit |
| phase-08+ | Pending Batch 2/3 verification findings | Batch 2/3 |

## How phases run

- The workflow's cron (`*/30 * * * *`) wakes; `select-phase` picks the lowest
  phase-NN without a `.done` marker (or resumes a `.deferred` one).
- `phase_runner.sh` runs `opencode run --prompt <PROMPT.md>` on
  `opencode/deepseek-v4-flash-free` via OpenCode Zen. The agent edits code, runs
  `gradle assembleDebug` + `gradle testDebugUnitTest`, and the reviewer subagent
  verifies. Success → `.done` → next phase on the next tick. Rate limit →
  `.deferred` → retried by the next cron tick.
- Prompts must be self-contained and honest. "Definition of done" always includes
  a green build + unit tests. Phases must not change the Room DB schema, must not
  edit `.github/workflows/`, and must follow AGENTS.md hard rules.

## Writing a new phase

1. Create `workspace/phase-NN/` (zero-padded).
2. Add `PROMPT.md`: verified problem → exact fix → definition of done → constraints.
3. Push. The cron picks it up automatically.
