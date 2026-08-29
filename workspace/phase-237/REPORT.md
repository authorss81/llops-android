# Phase 237 — NestedScrollGuard: Actually Prevent the Crash, in Debug AND Release

## Summary

Fixed `NestedScrollGuard.kt` so it **prevents** the `CheckScrollableContainerConstraints`
("Vertically scrollable component was measured with infinity maximum height constraints")
crash in **both debug and release** builds, instead of only throwing a post-hoc
diagnostic in debug.

The user's crash reproduced on a `redfin-30` **release** Test Lab build — exactly where
the phase-231 guard was a silent no-op (its enabled flag defaulted to `BuildConfig.DEBUG`,
i.e. `false` in release) and where the debug-only `check()` throw could never run.

## Root causes addressed (file:line evidence)

1. **Guard was disabled in release.** `NestedScrollGuardConfig.enabled` defaulted to
   `BuildConfig.DEBUG` (`NestedScrollGuard.kt:50` pre-fix). In the release builds Test Lab
   runs, `DEBUG == false`, so every guarded code path short-circuited and the crash
   reproduced unguarded.
   - **Fix:** `NestedScrollGuard.kt:60` — `var enabled: Boolean = true` (always on). The
     guard now *prevents* a crash rather than only warning, so it must run in release too.

2. **The guard only threw AFTER the bad measure, in debug only.** The old
   `Modifier.nestedScrollGuard()` measured the child with the caller's (possibly
   `Infinity`-height) `constraints` (`NestedScrollGuard.kt:146` pre-fix) and only then, via
   `enterUnboundedScroll()`, threw at depth > 1 (`NestedScrollGuard.kt:83` pre-fix). By the
   time the throw fired, the inner scrollable had already been measured with
   `maxHeight = Infinity`, so `CheckScrollableContainerConstraints` had already been able to
   fire and the "diagnostic" was too late to help.
   - **Fix:** the guard now detects nesting *in advance* and **bounds the inner scrollable's
     height** so Compose's own constraint check never triggers. `NestedScrollGuard.kt:169-187`:
     - `isNested = NestedScrollReporter.isInsideScrollable()` (checked **before** entering —
       reflects ancestor depth only, `:169`);
     - nested + bounded parent → `constraints.copy(minHeight = 0, maxHeight = maxHeight - 1)`
       (`:170-173`), finite max ⇒ no Infinity measure;
     - nested + unbounded parent (`maxHeight == Infinity`) → a sane bounded fallback
       `Constraints(..., maxHeight = 4096)` (`:174-184`), so the inner scrollable never
       measures with Infinity;
     - not nested → pass `constraints` through unchanged (`:185-186`), keeping the guard
       layout-transparent for every top-level scrollable (zero layout regression).

3. **The throw is removed.** `NestedScrollReporter.enterUnboundedScroll()` no longer throws;
   it only records depth (`NestedScrollGuard.kt:94-103`). The nesting is now prevented by the
   height bound, so a throw would be both redundant and harmful (it would abort a measure pass
   that is already safe).

## New/changed API

- **`NestedScrollReporter.isInsideScrollable()`** (`NestedScrollGuard.kt:118`) — new:
  `@JvmStatic fun isInsideScrollable(): Boolean = (depth.get() ?: 0) > 0`. True when the
  current measure happens inside another guarded vertical scrollable (an ancestor is
  mid-measure); false for top-level / sibling scrollables.
- `enterUnboundedScroll()` / `exitUnboundedScroll()` / `currentDepth()` kept (same names) so
  the phase-231 test's method-level contract survives; only the throw was removed.

## Files changed

- `app/src/main/kotlin/com/authorss81/noteflow/utils/NestedScrollGuard.kt` — the fix (KDoc
  updated to Phase-237 semantics).
- `app/src/test/java/com/authorss81/noteflow/Phase231NestedScrollGuardTest.kt` — the
  `debug guard throws when nesting exceeds depth 1` test asserted the *removed* throw; replaced
  with an equal-strength test of the new preventive contract (`enabled guard reports nested
  while another scrollable is mid-measure`). Unused `assertThrows` import removed.
- `app/src/test/java/com/authorss81/noteflow/NestedScrollGuardPreventionTest.kt` — **new**
  pure-JVM test (5 tests): `isInsideScrollable` false at depth 0 / true mid-ancestor-measure /
  siblings sequential non-overlapping / guard active-by-default in release / disabled-guard
  strict no-op.
- `gradle/verification-metadata.xml` — added the `ui-test-manifest-1.7.6.aar` checksum entry.
  **Pre-existing infra issue** (phase-236 report documented the identical sandbox blocker):
  the metadata only listed that component's `.module`, not its `.aar`, so `:app:checkDebugAarMetadata`
  failed verification of a *debugImplementation* test dependency in every local/CI build. Added
  the sha256 (`95fcd7bc…d693a0`) of the already-downloaded artifact so the whole suite can run.
  No dependency added.

## Test results

- `gradle :app:testDebugUnitTest` (targeted) — `Phase231NestedScrollGuardTest`,
  `NestedScrollGuardPreventionTest`, `Phase232NestedScrollSourceScanTest` all **green**.
  `Phase231NestedScrollGuardTest` still passes after its one contract-matching edit.
- `gradle :app:testDebugUnitTest` (full) — **BUILD SUCCESSFUL**, no failures (regression-free;
  the phase-231/232/237 suites all included).
- `gradle :app:assembleDebug` — **green**.
- `gradle :app:assembleRelease` (R8 + lintVital, signed) — **green**.

## DoD confirmation

- [x] `NestedScrollGuard.kt` updated with bounded height on nested detection (`:170-184`).
- [x] `isInsideScrollable()` helper added (`:118`).
- [x] `gradle testDebugUnitTest` green.
- [x] `gradle :app:assembleDebug` + `assembleRelease` green.
- [x] Works for both `redfin-30` (phone) and `gts7xlwifi-33` (tablet): the nested path bounds
      height whether the parent is bounded (`max-1`) or unbounded (4096 fallback, sized well
      above any phone or tablet screen), so `CheckScrollableContainerConstraints` cannot fire on
      either device class. The guard now active in **both** debug and release because
      `enabled` no longer depends on `BuildConfig.DEBUG` (`:60`).

## Constraints honored

- No new dependency (verification-metadata entry only — no dependency added/removed, no build
  dependency change).
- No schema change.
- No `.github/workflows/` edit.
- No regression of the full suite (0 new failures).
