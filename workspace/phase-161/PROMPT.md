# Phase 161: Triage Kali round-2 report → Generate new phases [NOT STARTED]

You are working on **InkFlow/Noteflow**. The Kali dynamic pentest is done:
- `docs/kali-report-round2.md` = the Kali round-2 findings (dynamic + static on
  the fresh release APK, evidence-backed, severity-rated).
- `docs/security-report-round2.md` = the phase-116 SOURCE audit findings
  (already triaged by phase-119 into phases 129-158). You focus on the KALI file.
- `docs/ARCHITECTURE.md`, `docs/phase-status.md`, `AGENTS.md` = current state.
- `workspace/SECURITY_FIX_PLAN.md` = round-1 workspace manifest (if present).

Your job: read ALL of it, triage the Kali findings, and **generate the next
phases** of the pipeline. Number them DYNAMICALLY after the highest existing
`phase-NN` (run `git ls-tree -r --name-only origin/main -- workspace` or
`Get-ChildItem workspace -Directory -Filter "phase-*"`, find the max, start at
max+1).

## Step 1 - Triage the Kali findings
- Read `docs/kali-report-round2.md`. Group findings into logical buckets.
- CRITICAL/HIGH findings → one fix phase each (or tight groups). MEDIUM/LOW →
  bundle **2-3 related findings per fix phase** (same area/theme).
- Cross-check against `docs/security-report-round2.md` + round-1 findings: if a
  Kali finding was already fixed by a round-1 phase, mark it `resolved at triage`
  with the fixing phase; if it is a re-report of something phase-119 already
  triaged into phases 129-158, do NOT duplicate — reference the existing phase.
- Each generated phase reuses the repo PROMPT format:
  `# Phase NN: <title> [NOT STARTED]`, context ("Read docs/phase-status.md +
  docs/ARCHITECTURE.md first"), real `file:line` references, Definition of done,
  Constraints (no `.github/workflows/` edits, no DB schema change without user
  approval, base-APK-size rule, reduce-motion/low-end rules, never log
  decrypted content, keep security model intact).

## Step 2 - Add improvement/feature phases (2-3 related features each)
- Beyond Kali fixes, think as a product engineer and add NEW feature phases the
  pentest surfaced or that the current state still lacks. Bundle 2-3 RELATED
  features per phase (not one-per-feature).
- Include at least:
  - **UI/UX improvements**: derive from the codebase (glass theme, editor,
    canvas, knowledge graph, empty states).
  - **Plugin ecosystem improvements**: from `docs/PLUGINS.md`,
    `docs/plugin-architecture.md`, the capability set in
    `plugin-sdk/.../plugins/PluginCapability.kt`, and any Kali plugin-runtime
    findings.
- Each generated phase must be REAL and actionable: name real files/anchors,
  real tests to add, real DoD.

## Step 3 - Manifest + status update
- Append every new phase (fix + feature) to `workspace/PHASES.md` and add rows
  to `docs/phase-status.md` (status `NOT STARTED`).
- Write `workspace/phase-161/REPORT.md`: triage table (finding → phase), the
  list of new phases with their rationale, and the UI/plugin idea list.

## Definition of done
- Every `docs/kali-report-round2.md` finding triaged (maps to a phase or is
  explicitly marked `resolved at triage` / `already triaged to phase-NN` with a
  reason).
- New phases generated: fix phases (CRITICAL/HIGH single, MEDIUM/LOW bundled
  2-3) + feature phases (2-3 related features each, incl. UI + plugin ideas).
  All with real file refs + DoD + constraints. NO duplicates of phases 129-158.
- Numbering is dynamic (after the true highest existing phase at generation
  time) and sequential - no gaps, no overlap with existing phases.
- `workspace/PHASES.md` + `docs/phase-status.md` updated. REPORT.md written.
- Commit + push.

## Constraints
- Do NOT create the phases' implementation code - only the PROMPT.md files
  (planning). The pipeline will run them in order.
- Do NOT edit `.github/workflows/`. Do NOT change app code in this phase.
- Respect AGENTS.md hard rules: architectural changes (DB schema, nav model,
  heavy deps) still need USER approval before implementation - flag any generated
  phase that would need approval in its PROMPT (e.g. "USER APPROVAL REQUIRED").
- Keep the base-APK-size rule: any heavy native feature must be a downloadable
  plugin, never baked into the base APK.