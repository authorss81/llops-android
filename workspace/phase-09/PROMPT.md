# Phase 9: Full regression + release readiness (THE CHECKPOINT) [PARTIAL]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. This is the FINAL phase of the pipeline. Its job is NOT to add new features —
it is to verify everything Phases 2–8 claimed to do, fix any remaining defects,
and produce a signed release artifact. Think of it as the "did we actually make
the app better, and can we ship it?" checkpoint.

## Your goals, in order

### 1. Full regression verification
Run the complete verification suite and fix anything that fails:
- `gradle assembleDebug` — must succeed.
- `gradle testDebugUnitTest` — all tests must pass (including every unit test
  added in Phases 4–7: stabilizer, pressure curves, symmetry math, color harmony).
- `gradle assembleRelease` — must succeed (this is what the release workflow
  builds).

For every failure, fix the root cause. Cite `file:line` in a summary of what was
fixed. Do NOT silence a failing test by deleting or weakening it unless the test
itself is genuinely wrong (then say so explicitly).

### 2. Cross-phase consistency audit
Verify the previous phases' claims against the actual code. For each, confirm
it is REAL (wired, called, works) — not just claimed:
- Phase 2: restore/sync data-loss paths fixed; search corpus cache present in
  `NoteRepository` and invalidated on mutation.
- Phase 3: all dead/fake features actually removed (no orphaned references,
  no leftover UI entries, no lingering imports of deleted classes).
- Phase 4: AGSL wet-mix shaders actually wired into the render path (not just
  defined); fallbacks for API <33 in place.
- Phase 5: UX/accessibility items (data-loss warnings, touch targets, feedback)
  present and reachable.
- Phase 6: WebDAV sync is real (no fake local-copy); encryption/decryption of
  synced payloads works; sync is honest about failures.
- Phase 7: stabilizer, pressure curves, symmetry, harmony (and any extras) are
  implemented with their unit tests.
- Phase 8: optimizations actually applied and measurable (no hot loops, no
  main-thread work added, no perf regressions from the optimization phase).

Where a claim is FALSE or a feature is still dead/stub, either fix it honestly
or remove the misleading claim (delete the code / update the roadmap). NEVER
leave a known false claim in place.

### 3. Security & honesty re-scan
- Confirm no secrets/keys/decrypted content are logged or committed.
- Confirm `allowBackup="false"` and `data_extraction_rules.xml` are intact.
- Confirm the app does not claim features it does not have (no fake UI entries).
- Confirm `ROADMAP.md` truth table and CHANGELOG accurately reflect reality.
  Update `CHANGELOG.md` with a clear, honest summary of Phases 2–9.

### 4. Produce the release artifact
- Ensure the release build is signed (the workflow uses AGP built-in debug
  signing when no keystore is present — that is fine for this pipeline).
- Verify `app/build/outputs` contains a release APK after `assembleRelease`.
- If anything blocks the release build, fix it.

### 5. Final report
Write `workspace/phase-09/REPORT.md` with:
- A table of every phase (2–9), its claim, and the verification verdict
  (PASS / FIXED / REMOVED) with `file:line` evidence.
- The list of defects found and fixed in this phase.
- The release artifact path and build results.

## Definition of done
- `gradle assembleDebug`, `gradle testDebugUnitTest`, and
  `gradle assembleRelease` ALL succeed.
- Every phase claim is verified with `file:line` evidence in `REPORT.md`.
- `CHANGELOG.md` updated honestly.
- `REPORT.md` committed alongside the phase.

## Constraints
- NO new third-party dependencies. NO new permissions. NO `INTERNET`.
- Do NOT change the DB schema unless required to fix a real Phase 2–8 defect
  (and then say so explicitly — schema changes need user approval, so prefer a
  fix that avoids one).
- Do NOT edit `.github/workflows/`.
- Do not weaken tests to make them pass. Fix the code, or justify removing a
  genuinely-broken test in `REPORT.md`.
- Be honest above all: if something cannot be verified or fixed in this phase,
  say so clearly in `REPORT.md` rather than claiming it works.