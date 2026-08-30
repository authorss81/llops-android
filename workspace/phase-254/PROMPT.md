# Phase 254 — Comment trim: AnnotationCanvas + EditorScreen + HomeScreen (no security files)

## Goal
Reduce repo-wide comment+blank overhead from **33.1% → ~27%** by trimming pure-cosmetic comments in 3 UI files only. Target: **−3,000 to −4,000 lines** of non-essential comments. No security contracts, no WHY-comments, no KDoc touched. No behavior change. Tests must remain 100% green.

## Context — measured at HEAD (post phase-250 `87592ed`)

| File | Code (LOC) | Overhead | Trim potential | Risk |
|---|---:|---:|---|---|
| `ui/components/AnnotationCanvas.kt` | 6,678 | ~1,800 (27%) | ~1,000–1,400 | Low (pure UI) |
| `ui/screens/EditorScreen.kt` | 4,945 | ~2,400 (33%) | ~1,200–1,500 | Low |
| `ui/screens/HomeScreen.kt` | 2,912 | ~850 (23%) | ~500–800 | Low |
| **Total** | **14,535** | **~5,050** | **~2,700–3,700** | **Low** |

## Hard rule: what to KEEP

- **All KDoc** (`/** ... */`) on classes, methods, fields, properties
- **WHY-comments** — anything that explains a non-obvious decision:
  - `// fail-closed to avoid …`
  - `// phase-NNN provenance: …`
  - `// R2-b2b*-…` references (pentest-finding provenance)
  - `// the duplicate guard because …`
  - `// WHY: stabilizer EWMA causes …`
- **License header** at the top of each file
- **Test-side @Suppress explanations** (not in scope here anyway)
- **TODO/FIXME/XXX** markers (do not silently delete)
- **Comments INSIDE string literals** (KDoc doesn't count, but `"//"` inside `Text("…")` is data, never touch)

## Hard rule: what to TRIM

Single-line `//` comments that fall into any of these categories:

1. **Restate the next line of code** — `// increment counter` above `counter++`
2. **Obvious section dividers** — `// ====================`, `// --- foo ---`, `// end region X`, `// ~~~`
3. **WHAT-comments** — `// returns X` over a one-liner named `getX()`
4. **Empty separator blocks** — 3 or more consecutive blank lines reduced to 1
5. **Trailing-line `//` tags** — `val x = 5 // default size` where the value is self-evident
6. **Paraphrased KDoc** — if a `// returns X` comment sits below a method that already has `/** @return X */` KDoc, drop the `//`
7. **Phase banners without content** — `// Phase 35: ink bar` (keep only if the line below is NOT immediately obvious from the code)

## Files to change

### 1. `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
- Trim target: ~1,000–1,400 lines
- Approach: read file in 500-line chunks; for each `//` line, classify (WHY vs WHAT) and delete if WHAT; collapse 3+ blank lines to 1; remove all `// ===== ...` dividers
- Do NOT touch the dispose-flush section (phase-242) or the wet-mask section (phase-228/8a2032d) — they have WHY comments

### 2. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- Trim target: ~1,200–1,500 lines
- Approach: same
- Do NOT touch the `triggerAutoSave` / `handleStrokesChange` / `loadFailedDueToLock` section (phase-250) — WHY-heavy

### 3. `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt`
- Trim target: ~500–800 lines
- Approach: same
- Do NOT touch the passwordless backup dialog section (phase-252) or the quarantine flow (phase-09)

## Self-check before commit (mandatory per trim pass)

After each chunk, run a grep to confirm no KDoc, no WHY-comment, no security comment was removed:

```bash
# In the trimmed file, these must still be present (non-empty result):
git diff --stat path/to/AnnotationCanvas.kt
# Spot-check 10 specific known-WHY comments are still there
grep -c "fail-closed" AnnotationCanvas.kt   # must be > 0
grep -c "R2-b2b" AnnotationCanvas.kt        # must be > 0
grep -c "phase-" AnnotationCanvas.kt        # must be > 0
grep -c "phase-" EditorScreen.kt            # must be > 0
grep -c "phase-" HomeScreen.kt              # must be > 0
```

If any of these returns `0` and the file used to have them, RESTORE the deleted section from `git show HEAD:path`.

## New tests

### `app/src/test/java/com/authorss81/noteflow/Phase254CommentTrimTest.kt` (pure JVM, ~6 tests)
- Source-pin: each of the 3 files has FEWER raw lines than HEAD (`git show HEAD:...` line count), but the SAME or HIGHER `code lines` (no actual code lost).
- Source-pin: each of the 3 files still contains at least N of these markers (proves WHY-comments preserved):
  - `AnnotationCanvas.kt`: must still contain `// fail-closed`, `// R2-b2b`, `// phase-242`, `// phase-228`, `// phase-240`
  - `EditorScreen.kt`: must still contain `// fail-closed`, `// R2-b2b`, `// phase-250`, `// phase-238`, `// phase-242`
  - `HomeScreen.kt`: must still contain `// fail-closed`, `// phase-252`, `// phase-09`, `// phase-22`
- Source-pin: each file no longer contains `// ===========` or `// ---` or `// ~~~~~` divider banners.
- Source-pin: each file has at most 1 consecutive blank line (no 3+ blank blocks).
- Behavior regression: `gradle :app:assembleDebug` + `testDebugUnitTest` must remain green with the SAME test count (3556+) — no test should newly fail because a test-side comment was deleted.

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- No code change — comments only
- No KDoc removed
- No WHY-comment removed
- No `// TODO` / `// FIXME` / `// XXX` removed
- No string-literal content touched
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:testDebugUnitTest` 3556+ green, ZERO new failures
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- Total raw-line reduction across the 3 files: **≥ 2,700 lines** (target 3,000–4,000)
- Total code-line change across the 3 files: **|delta| ≤ 50 lines** (i.e. only re-flow from blank-line collapse, no code lost)
- Repo-wide comment+blank overhead drops from 33.1% to ≤ 28%
- All `Phase254CommentTrimTest` source-pins pass
- `workspace/phase-254/REPORT.md` with:
  - Per-file before/after raw + code line counts (table)
  - List of WHY-comment markers found post-trim (proves no WHY was deleted)
  - Total repo overhead before/after
