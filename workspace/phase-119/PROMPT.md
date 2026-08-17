# Phase 119: Round-2 Triage → Generate new phases (2-3 related features each + UI/plugin ideas) [NOT STARTED]

You are working on **InkFlow/Noteflow**. The round-2 audits are done:
- `docs/security-report-round2.md` = the SHARED single file with BOTH phase-116
  (source audit) and phase-118 (Kali dynamic pentest) findings.
- `docs/ARCHITECTURE.md`, `docs/phase-status.md`, `AGENTS.md` = current state.
- `workspace/SECURITY_FIX_PLAN.md` = round-1 workspace manifest (if present).

Your job: read ALL of it, triage, and **generate the next phases** of the
pipeline. Number them DYNAMICALLY after the highest existing `phase-NN` (run
`git ls-tree -r --name-only origin/main -- workspace` or `Get-ChildItem
workspace -Directory -Filter "phase-*"`, find the max, start at max+1).

## Step 1 - Triage the round-2 findings
- Read `docs/security-report-round2.md`. Group findings into logical buckets.
- CRITICAL/HIGH findings → one fix phase each (or tight groups). MEDIUM/LOW →
  bundle **2-3 related findings per fix phase** (same area/theme) so the pipeline
  is not fragmented into 80 tiny phases again.
- Each generated phase reuses the repo PROMPT format:
  `# Phase NN: <title> [NOT STARTED]`, context ("Read docs/phase-status.md +
  docs/ARCHITECTURE.md first"), real `file:line` references, Definition of done,
  Constraints (no `.github/workflows/` edits, no DB schema change without user
  approval, base-APK-size rule, reduce-motion/low-end rules, never log
  decrypted content, keep security model intact).

## Step 2 - Add improvement/feature phases (2-3 related features each)
- Beyond fixes, think as a product engineer and add NEW feature phases. Bundle
  2-3 RELATED features per phase (not one-per-feature).
- Include at least:
  - **UI/UX improvements**: derive from the codebase (glass theme, editor,
    canvas, knowledge graph, empty states) - what would a user love? List
    concrete ideas (e.g. split-view, quick-note widget, gesture shortcuts,
    theme store, onboarding, accessibility passes).
  - **Plugin ecosystem improvements**: from `docs/PLUGINS.md`,
    `docs/plugin-architecture.md`, the capability set in
    `plugin-sdk/.../plugins/PluginCapability.kt`, and any round-2 plugin-runtime
    findings - new capabilities, store improvements, update UX.
- Each generated phase must be REAL and actionable: name real files/anchors,
  real tests to add, real DoD.

## Step 3 - Manifest + status update
- Append every new phase (fix + feature) to `workspace/PHASES.md` and add rows
  to `docs/phase-status.md` (status `NOT STARTED`).
- Write `workspace/phase-119/REPORT.md`: triage table (finding → phase), the
  list of new phases with their rationale, and the UI/plugin idea list.

## Definition of done
- `docs/security-report-round2.md` findings all triaged (each maps to a phase or
  is explicitly marked `resolved at triage` with a reason, mirroring round 1).
- New phases generated: fix phases (CRITICAL/HIGH single, MEDIUM/LOW bundled
  2-3) + feature phases (2-3 related features each, incl. UI + plugin ideas).
  All with real file refs + DoD + constraints.
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