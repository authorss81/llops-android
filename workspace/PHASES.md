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
| phase-01 | App scaffold [DONE] | scaffold |
| phase-02 | **Security: fix restore/sync data-loss** [PARTIAL] (C1/H3/H4 shipped; H1/H2 completed in phase-09) | Batch 1 security audit |
| phase-03 | **Honesty: remove/wire dead & fake features** [DONE] | Batch 1 honesty audit |
| phase-04 | **Real AGSL watercolor/oil** [DONE] (marker-only commit 22cc99c; real impl in 7854551) | Batch 1 graphics audit |
| phase-05 | **UX/accessibility quick wins** [DONE] | Batch 1 UX audit |
| phase-06 | **WebDAV sync made real+safe+honest** [DONE] | Batch 1 security audit |
| phase-07 | **Free painting features** [DONE] (reference layer not shipped) | Batch 1 feature audit |
| phase-08+ | Pending Batch 2/3 verification findings [phase-08 DONE, phase-09 PARTIAL, phase-10/11/12 DONE, phase-13 PARTIAL, phase-14/15/16 DONE, phase-17 CANCELLED, phase-18/19 DONE, phase-20 DONE, phase-21/22/23/24/25/26/27/28/29 DONE, phase-30 DONE, phase-31 DONE, phase-32 DONE, phase-33 DONE, phase-34 DONE, phase-35 DONE, phase-36 DONE, phase-37 DONE, phase-38 DONE — see docs/phase-status.md] | Batch 2/3 |
| phase-39..phase-114 | Security-fix pipeline (B1/B2 findings; every phase DONE except phase-77 = NOT STARTED, false `.done` removed 2026-08-17) | Batch 1/2 security audits |
| phase-115 | **Final doc-fix — status & consistency sweep (this phase)** | Batch 2/3 |
| phase-116 | Round-2 Security Audit (full source review) | Round-2 audit |
| phase-117 | **Build Release APK + verify it assembles without error** (feeds phase-118) | Round-2 audit |
| phase-118 | Kali full-environment dynamic pentest on the phase-117 APK | Round-2 audit |
| phase-119 | Round-2 Triage → generate new phases | Round-2 audit |
| phase-120..133 | Pre-seeded user-requested UI/UX feature phases (added 2026-08-17 commit `7a4b6e6`, renumbered by `c3a1789`; run before 134+ per numeric order) | user requests |
| phase-134..153 | Round-2 fix phases (all 43 findings bundled 2-3 per phase by area/root cause; generated 2026-08-17 by phase-119) | Round-2 audit |
| phase-154..158 | Round-2 feature phases (2-3 related features each, incl. UI/UX + plugin ideas; generated 2026-08-17 by phase-119) | Round-2 audit |
| phase-159 | **Build Release APK + verify it assembles without error** (feeds phase-160) | Kali redo |
| phase-160 | Kali full-environment dynamic pentest (6-hr budget) on the phase-159 APK → `docs/kali-report-round2.md` | Kali redo |
| phase-161 | Triage the Kali round-2 report → generate new phases | Kali redo |
| phase-162 | Build & code failure fixes (dup methods, deprecated project.exec, trusted-artifacts) + verify decryption-safety edge cases | build/code hardening |
| phase-163 | "Don't show again" must persist for data-recovery screens (per-event, not per-launch) | user-reported |
| phase-164 | Tag vault scoped to current notebook only (not all notebooks) | user-reported |
| phase-165 | Beautify Gallery page thumbnails (Material 3 card design) | user-reported |
| phase-166 | Buttons/text fit screens & cards — wrap or shorten (paging arrows) | user-reported |
| phase-167 | Bottom nav bar no longer overlays messages & calendar (dynamic padding/insets) | user-reported |
| phase-168 | App opens the last-used notebook on cold start | user-reported |
| phase-169 | Fix post-export "Unreadable (decryption failed)" + keep fail-closed UX | user-reported |

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
