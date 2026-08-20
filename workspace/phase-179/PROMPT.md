# Phase 179: Visual QA — Gemini vision review of saved screenshots → fix findings [NOT STARTED]

You are working on **InkFlow/Noteflow**. Phase-178 produced a screenshot suite
(`visual-qa/screenshots/` curated set + full set at
`app/build/outputs/paparazzi/` when run locally). This phase gets a FREE vision
model to review those PNGs and turn real visual defects into actionable fixes.

Read `docs/ARCHITECTURE.md`, `docs/phase-status.md`, and
`workspace/phase-178/REPORT.md` first.

## WORKFLOW RULE
Work in small steps; commit+push after EVERY step. Never sit on uncommitted work.

## Step 1 - Free Gemini review script (committed, reusable)
- Add a script `visual-qa/review_screenshots.py` that:
  - Reads a `GEMINI_API_KEY` from env (user supplies; never commit a key).
  - Sends the PNGs in `visual-qa/screenshots/` to the **free Gemini API**
    (use `generative-ai` client or plain REST to
    `https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent`
    or newer free flash model; batch 3-5 images per request to stay under limits).
  - Prompts: "Review each UI screenshot for visual defects: clipped/overflowing
    text or cards, low contrast, cramped spacing, misaligned elements, empty
    states, dark-theme issues. For each: file, exact issue, suggested fix (file/line
    where possible). Prioritize by severity. If a screenshot is fine, say so."
  - Writes `visual-qa/findings-<date>.md`.
- Run it ONCE on the curated set (only if `GEMINI_API_KEY` is available in env;
  otherwise leave the script + a `README` note and let the next step rely on the
  file if present).
- COMMIT this step.

## Step 2 - Record findings
- If the script ran, commit `visual-qa/findings-*.md`.
- If no key is available, write `visual-qa/findings.md` by ANALYZING the PNGs'
  pixel data heuristically (e.g. detect near-edge text/card overflow via
  bounding boxes, blank/uniform regions that indicate empty states) and list
  candidate issues — clearly marked "heuristic, verify visually".
- COMMIT this step.

## Step 3 - The review IS the deliverable (no code changes unless trivially safe)
- This phase only PRODUCES findings. Do NOT fix code here — the next phase
  (created by phase-167-style triage or a future phase) will fix them.
- In the REPORT, for each finding note whether it is fixable in-app (file/line
  suggestion) or needs design/asset work.

## Definition of done
- `visual-qa/review_screenshots.py` committed + documented in
  `visual-qa/README.md` (how to get a free Gemini key, how to run).
- `visual-qa/findings.md` (or `findings-<date>.md`) committed with severity-ordered
  findings referencing `visual-qa/screenshots/`.
- `workspace/phase-179/REPORT.md`: summary + list of findings + which need a follow-up fix phase.
- No `.github/workflows/` edits, no base-APK changes, no new runtime deps.
- Commit + push.

## Constraints
- NEVER commit an API key. Secrets are read from env only.
- Keep the script dependency-free or pip-installable with a `requirements.txt` if
  needed (no `generative-ai` SDK → pure `urllib` REST is fine).
- 2-core runner: keep batches small.