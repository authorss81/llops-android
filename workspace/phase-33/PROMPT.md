# Phase 33: Generate security-fix phases from the audit report + final doc-fix phase [DONE]
You are working on **InkFlow/Noteflow**. `docs/security-report.md` now contains
the full security findings (Phase 30 source audit + Phase 32 APK attack). This
phase (1) READS that report, (2) CREATES a new set of phases that fix the
loopholes — critical first, each finishable by an AI in under 30 minutes — and
(3) adds a final **document-fix** phase at the very end of the pipeline.

## Step 1 — Read and triage
Read `docs/security-report.md` (and `docs/general-audit-report.md` for
non-security items worth fixing). Group findings by severity:
CRITICAL → HIGH → MEDIUM → LOW → INFO. For each finding capture: id/short title,
severity, `file:line`, the exploit, and the suggested fix.

## Step 2 — Create fix phases (one per finding or tight group)
For EVERY finding you will fix, create a phase directory. Number them
**dynamically**: run `Get-ChildItem workspace -Directory -Filter "phase-*"` (or
the git equivalent), find the highest existing `phase-NN` number, and start the
new fix phases at that + 1 (e.g. if the highest is phase-38, the first fix phase
is phase-39, then 40, 41, …). Do NOT assume a fixed number — the repo gains
phases over time. Each phase:
- Must be scoped so an AI can finish it in **under 30 minutes** (one finding or
  a small tightly-related group per phase — no mega-phases).
- CRITICAL findings first, then HIGH, MEDIUM, LOW, INFO (don't skip LOW/INFO —
  they still get fixed, later in order).
- Write `workspace/phase-NN/PROMPT.md` following the repo's established phase
  format and the AGENTS.md hard rules:
  - **Tell exactly where and how to write/fix**: state the `file:line` to change
    and the expected fix shape (e.g. "replace `crypto` in `EncryptionService.kt`
    with X"), the verification (which test command: `gradle testDebugUnitTest`,
    `gradle assembleDebug`), and the Definition of done.
  - Respect constraints: no DB-schema changes unless the fix requires one (then
    a migration-safe note is mandatory), do not edit `.github/workflows/`, no new
    deps unless required (justify), never log keys/content, use `ClipboardGuard`.
  - Each PROMPT must reference the source finding (id + severity + file:line).
- IMPORTANT: do NOT create a phase for a finding you judge as already fixed or
  non-applicable — list those in `docs/security-report.md` as "resolved at
  triage" with the reason.

## Step 3 — Final document-fix phase (always last)
After all fix phases, add a FINAL phase (number = highest existing phase + 1 at
that time) titled **Document fix — final status & consistency sweep**:
- Re-run a status audit of ALL phases (like Phase 20) and mark every heading
  `[DONE]`/`[DEFERRED]`/`[BLOCKED]`/`[PARTIAL]`/`[NOT STARTED]`/`[CANCELLED]`
  beside it.
- Update `docs/phase-status.md` to final truth; update `ROADMAP.md`/`AGENTS.md`
  so no stale claim remains; verify `docs/security-report.md` findings are all
  marked fixed/closed with evidence, and `docs/general-audit-report.md` items
  are marked resolved.
- Write its PROMPT.md with the same format.

## Definition of done
- Every security finding in `docs/security-report.md` maps to exactly one new
  phase (or is marked resolved-at-triage in the report).
- All new phases numbered sequentially, CRITICAL-first, each under-30-min scope.
- The final document-fix phase is the last phase in the pipeline.
- A manifest file `workspace/SECURITY_FIX_PLAN.md` lists: finding id → phase
  number → severity → file:line → verification command.
- All PROMPT.md files committed and pushed. Do NOT implement the fixes in this
  phase — only create the plan + phase prompts.

## Constraints
- Do NOT modify application code in this phase — you are PLANNING the fixes.
- Do NOT edit `.github/workflows/`. No new deps. Do not run builds.
- Be precise about "where and how to write": every PROMPT has file:line + fix
  shape + verification + DoD.
- Keep each phase genuinely under 30 minutes of AI work — split big findings.
