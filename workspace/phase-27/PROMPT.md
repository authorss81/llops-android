# Phase 27: False-implementation audit & general audit (non-security) [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. Phase 26 audits SECURITY; this phase audits EVERYTHING ELSE: false/claimed
implementations, broken wiring, dead UI, and general code quality. The owner
wants an honest accounting of what is REAL versus what is claimed.

Your deliverable is **`docs/general-audit-report.md`** (create fresh; append if
present). Write findings INCREMENTALLY and commit/push as you go.

## Part A — False-implementation audit (honesty gate)
For every feature the app claims (in `ROADMAP.md`, `AGENTS.md`, `README.md`,
`docs/`, phase `PROMPT.md`/`REPORT.md`, changelog), verify against the code with
`file:line` evidence whether it is:
- **REAL** — wired, called, works, reachable in UI,
- **STUB/FAKE** — looks functional but does nothing (e.g. returns hardcoded
  values, no-op, dead code path),
- **BROKEN** — wired but fails or is unreachable,
- **CLAIM-ONLY** — documented but no implementation exists,
- **DEAD** — implemented but never reachable from any UI entry.
Cover every area: canvas/brushes, AGSL wet-mixing, WebDAV E2EE sync, plugins
(OCR/web search/export/etc.), stickers, LocalSend, dictation, TTS, translation,
assistant, encryption, import/export, markdown preview, knowledge graph,
templates, voice notes, performance fixes, release signing. Update
`ROADMAP.md`/`AGENTS.md` claims that are FALSE (correct them honestly) — never
leave a known false claim standing.

## Part B — General audit (non-security quality)
- Dead code / unused imports / unused functions (grep-verify, not vibes).
- Performance smells (main-thread I/O, per-frame allocations, missing
  `remember`/`derivedStateOf`, recomposition hotspots, large lists).
- Correctness smells (swallowed exceptions, `!!` on nullable, silent
  fallbacks, magic numbers).
- UX reachability: every feature reachable within ≤3 taps; no orphan UI.
- Test quality: tests that assert nothing, or test the wrong thing.
- Docs consistency between files.
For each finding: `file:line`, category, suggested fix, priority. Prefer
documenting; only fix trivial things (typos, dead import) this phase.

## Definition of done
- `docs/general-audit-report.md` written: verdict per feature
  (REAL/STUB/FAKE/BROKEN/CLAIM-ONLY/DEAD) with `file:line`, plus a Part-B
  findings section with priorities.
- False claims in `ROADMAP.md`/`AGENTS.md`/`README.md` corrected honestly.
- A summary table: count per verdict, top broken/dead items.
- `gradle assembleDebug` + `gradle testDebugUnitTest` pass (docs/trivial fixes
  only — do not refactor in this phase).
- No finding lives only in a reply; everything is in the file.

## Constraints
- Security findings belong in Phase 26's `docs/security-report.md` — do NOT
  duplicate them here (cross-reference if needed).
- Do NOT change the DB schema or `.github/workflows/`. No new deps.
- Do NOT refactor subsystems (AGENTS.md: major architectural change requires
  user approval) — this phase audits and reports, it does not rewrite.
- Be brutally honest: label fakes as fakes. The goal is a clean, truthful
  picture that later phases can act on.