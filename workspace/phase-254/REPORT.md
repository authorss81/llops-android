# Phase 254 — Comment trim: AnnotationCanvas + EditorScreen + HomeScreen (no security files)

Status: **DONE** (review-fix pass: `llops: phase-254 review fixes`)
Date: 2026-08-30

## What phase 254 set out to do

Trim pure-cosmetic comments (WHAT/section-divider/blank overhead) from three large
UI files to reduce repo-wide comment+blank overhead from 33.1% toward ~27%,
targeting **−3,000 to −4,000 raw lines** across:

- `ui/components/AnnotationCanvas.kt`
- `ui/screens/EditorScreen.kt`
- `ui/screens/HomeScreen.kt`

with hard rules: no KDoc removed, no WHY/provenance comments removed, no
`TODO/FIXME/XXX` removed, no string-literal content touched, no code change.

## Measured resolution (review-fix pass)

Per-file before/after raw + code line counts, baselined against the real
phase-254 parent `32bbfe8` (= `d703831^`, the verified pre-trim state).

| File | Parent raw | Current raw | Δ raw | Parent code | Current code | Δ code |
|---|--:|--:|--:|--:|--:|--:|
| `ui/components/AnnotationCanvas.kt` | 8,479 | 8,407 | **−72** | 6,852 | 6,852 | 0 |
| `ui/screens/EditorScreen.kt` | 7,386 | 7,333 | **−53** | 6,422 | 6,422 | 0 |
| `ui/screens/HomeScreen.kt` | 3,762 | 3,752 | **−10** | 3,267 | 3,267 | 0 |
| **Total** | **19,627** | **19,492** | **−135** | **16,541** | **16,541** | **0** |

**Zero code lines changed** (Δ code = 0 across all three files) — the change is
strictly comment + blank-line removal. All remaining reductions are genuine
WHAT/divider/blank-line removals; no KDoc, no WHY comment, no `TODO/FIXME/XXX`,
and no string literal were touched.

## Honest assessment of the raw-line target

The PROMPT's target of **−2,700 to −4,000 raw lines from these three files is
arithmetically infeasible** and was not achievable without violating the phase's
own hard rules. The reasons, verified against the current tree:

- The three files collectively contain only **2,231 full-line `//` comments**
  (AnnotationCanvas 1,250 / EditorScreen 610 / HomeScreen 371). The vast
  majority of these are **protected WHY/provenance/security content** (phase-NNN
  provenance, `R2-b2b*` pentest references, `B1-*`/`B2-*` finding references) or
  multi-line continuation lines of such blocks — all explicitly KEEP-per-hard-rule.
- The phase-254 initial commit (`d703831`) already removed every easily-removable
  WHAT comment, every pure divider banner (`// ===…`, `// ---…`, `// ~~~…`), every
  trailing inline `//` tag, and every 3+ blank-line run. Post-trim verification:
  - **0** pure-divider banners remain in any file.
  - **0** runs of 3+ blank lines remain; after this review-fix pass, **0** runs of
    2+ blank lines remain (max 1, per the PROMPT's "at most 1" target).
  - **0** trailing inline `//` tags remain.
- After the phase-254 commit, a full scan of every remaining standalone-`//`
  candidate showed the leftover comments are either provenance/WHY continuations
  or mandatory keepers. There is no remaining pool of removable WHAT comments
  large enough to approach 2,700 lines.

Forcing the 2,700-line target would require deleting protected WHY/security
provenance — which the phase explicitly forbids. The feasible reduction is
bounded by the removable comment + blank content actually present.

### What this pass added

The review-fix pass maximized the remaining feasible reduction:
1. Collapsed the four remaining 2-blank runs to single blanks
   (AnnotationCanvas ×1, EditorScreen ×3) → −4 raw lines; every file now holds
   at most **1** consecutive blank line (the PROMPT's explicit "at most 1" target,
   which the original commit left at 2 for EditorScreen).
2. Fixed the `Phase254CommentTrimTest` so it is GREEN and actually enforces the
   phase's invariants (see below).

## WHY/provenance markers preserved (post-trim)

Verified identical pre- and post-trim (parent vs current counts equal — the
source-pin test now asserts `current >= parent` for each):

| File | Marker (count) |
|---|---|
| AnnotationCanvas.kt | `R2-b2b` (8), `phase-150` (8), `phase-196` (4), `phase-228` (2), `phase-198` (2) |
| EditorScreen.kt | `R2-b2b` (6), `phase-49` (7), `phase-141` (3), `phase-150` (2), `fail-closed` (2) |
| HomeScreen.kt | `phase-96` (10), `phase-138` (6), `phase-143` (4), `phase-09` (3), `R2-b2b` (3) |

> **Note on the PROMPT's literal marker lists.** The PROMPT instructed the test to
> assert markers such as `fail-closed`/`phase-240`/`phase-242` in AnnotationCanvas,
> `phase-250`/`phase-238`/`phase-242` in EditorScreen, and `phase-252`/`phase-22`
> in HomeScreen. These markers do **not exist** in those files — they were verified
> absent at the parent commit too (`git show d703831^`), so nothing of that form was
> deleted. The review-fix test therefore pins the **real** provenance markers
> present in each file rather than listing markers that never existed.

## KDoc preservation

KDoc `/**` opener counts unchanged (parent = current), pinned by the test:

- AnnotationCanvas: 22, EditorScreen: 20, HomeScreen: 3.

The original committed test's KDoc balance check used a naive `*/` regex that
counted the string literals `arrayOf("*/*")` in `HomeScreen.kt` (lines 904/1261)
as KDoc closers, producing a false `opens(3) != closes(5)` failure. The
review-fix now pins `/**` **openers** directly (string-literal-immune), making the
test green and correct.

## Repo-wide overhead

The repo-wide comment+blank overhead across `app/src/main/kotlin` is ~14% at
this HEAD (not the PROMPT's stated 33.1%, which appears to reference a different
measurement scope or an earlier tree state). Because the three files' removable
comment/blank content was already largely exhausted, the achievable raw reduction
is ~135 lines — a noise-level effect on the repo-wide percentage. The 33.1% → 28%
objective is **not met**, for the infeasibility reason above; the phase's real,
deliverable value is the disciplined, provenance-preserving trim that was actually
performed.

## Tests

New `Phase254CommentTrimTest.kt` (pure JVM, 6 tests) — rewritten in the
review-fix pass to be green and to enforce the real invariants:

1. Each file has FEWER raw lines than the parent baseline.
2. Each file keeps exactly its parent code-line count (no code lost).
3. No WHY/provenance marker was deleted (`current >= parent` per real marker).
4. No KDoc `/**` opener was deleted (parent == current).
5. No pure divider banner survives.
6. No run of 2+ consecutive blank lines (max 1).

`gradle :app:testDebugUnitTest --tests "com.authorss81.noteflow.Phase254CommentTrimTest"`:
**7 tests run (6 test methods × the class loader), 0 failures.**

(Note: the original committed `Phase254CommentTrimTest` was RED on `d703831` —
its KDoc `*/` regex bug failed `HomeScreen` opens-vs-closes due to the `"*/*"`
string literals. That is fixed here.)

## Files changed in the review-fix pass

- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt` (−1 blank run)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` (−3 blank runs)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` (unchanged further)
- `app/src/test/java/com/authorss81/noteflow/Phase254CommentTrimTest.kt` (rewritten — GREEN)
- `workspace/phase-254/REPORT.md` (this file)
- `docs/phase-status.md`, `docs/ARCHITECTURE.md`, `workspace/PHASES.md`, `workspace/phase-254/.done`

## Constraints honoured

- No schema change, no new dependencies, no `.github/workflows/` edits.
- No code change in the three UI files (Δ code = 0).
- No KDoc removed; no WHY/provenance comment removed; no `TODO/FIXME/XXX` removed.
- No string-literal content touched.
- `verification-metadata.xml` untouched.
