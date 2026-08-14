# Phase 20: Document fix — phase-status audit & clean labeling

You are working on **InkFlow/Noteflow**, an autonomous-LLM-driven Android app.
The pipeline has completed many phases. This phase audits every phase directory
and every markdown file, determines the TRUE status of each phase, and labels
the phase headings accordingly so the docs stay honest and clean.

## Task
1. Enumerate ALL phase directories (`workspace/phase-01` … `workspace/phase-NN`)
   and ALL markdown files in the repo (`*.md` at root, `docs/`, `workspace/`,
   `app/`).
2. For every phase directory, determine its true status by checking:
   - `.done` / `.deferred` / `.blocked` / `.attempts` marker files,
   - the actual implementation in `git history` and the code tree (does the
     phase's promised feature actually exist and is it wired? not just a marker),
   - the phase's `REPORT.md`/`AUDIT_REPORT.md` if present.
   Assign one of: **DONE**, **DEFERRED**, **BLOCKED**, **PARTIAL** (some work
   shipped, some not), **NOT STARTED**, or **CANCELLED**.
3. Run **5 parallel subagents** (use the Task tool) to independently verify
   statuses — split the phase list across them so every phase is checked by at
   least one agent with `file:line` evidence. Cross-check their verdicts; where
   they disagree, resolve by direct inspection.
4. Beside each phase heading (in `workspace/phase-NN/PROMPT.md` line 1 and in
   `ROADMAP.md`/any plan doc), append the verified status marker, e.g.
   `# Phase 20: … [DONE]`, `# Phase 12: … [PARTIAL]`, `# Phase 17: … [CANCELLED]`.
   Do NOT remove or rewrite the original content — only add the marker + a short
   status line.
5. Write a single authoritative summary to `docs/phase-status.md`: a table of
   every phase → verified status → evidence (`file:line` or commit) → notes.
6. The reviewer (run a second pass yourself) must verify NO phase heading is
   left unmarked anywhere. If any unmarked phase heading exists, list it in a
   new file `docs/phase-status-gaps.md` AND fix the marker in the original file
   so everything is clean.

## Definition of done
- Every phase heading in `workspace/phase-*/PROMPT.md` and `ROADMAP.md` carries a
  verified status marker.
- `docs/phase-status.md` exists with the full verified table.
- `docs/phase-status-gaps.md` exists (empty if no gaps) and every gap found was
  also fixed in the source file.
- Subagent verification evidence documented (which agent checked which phase).
- `gradle assembleDebug` and `gradle testDebugUnitTest` are NOT required to pass
  (this is a docs phase) but must not be broken.

## Constraints
- Do NOT change production code, DB schema, or `.github/workflows/`.
- Do NOT add new dependencies.
- Be honest: status must match reality. A phase with only a `.done` marker but no
  real implementation must be marked PARTIAL/NOT-STARTED and reported — do NOT
  rubber-stamp markers.
- Only modify `PROMPT.md` headings, `ROADMAP.md`, and the two `docs/` files
  mentioned. No other doc rewrites.