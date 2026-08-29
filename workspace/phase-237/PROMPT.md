# Phase 237 — Fix NestedScrollGuard to Actually Prevent Crash + Make It Work in Release

## Goal
Fix `NestedScrollGuard.kt` so it **actually prevents** the nested-scrollable crash in **both debug AND release builds**, not just throws after the fact in debug.

## Context — Verified Root Cause
The user got the exact same `NestedScrollGuard: ...` crash at `NestedScrollGuard.kt:83` in Test Lab (`redfin-30` release build). The bug:

1. **`NestedScrollGuardConfig.enabled = BuildConfig.DEBUG`** (`NestedScrollGuard.kt:50`) — Test Lab runs **release** builds where DEBUG is false, so the guard is a **no-op in release**
2. Even in debug, the guard **only throws AFTER the constraint violation is already propagated** — the `LayoutModifier.measure` at `NestedScrollGuard.kt:144` has already called `measurable.measure(constraints)` with `Infinity` height, then throws at line 83 — the inner scrollable has already been measured wrongly
3. **The fix needs to be:** when depth > 1, the guard should **constrain the inner scrollable** to `Constraints(maxHeight = parentHeight - 1)` so Compose's own `CheckScrollableContainerConstraints` never fires

## Files to Fix

### 1. `app/src/main/kotlin/com/authorss81/noteflow/utils/NestedScrollGuard.kt` (THE BUG)
**Current code (`NestedScrollGuard.kt:141-155`):**
```kotlin
fun Modifier.nestedScrollGuard(): Modifier = composed {
    if (LocalNestedScrollGuard.current) {
        layout { measurable, constraints ->
            NestedScrollReporter.enterUnboundedScroll()
            try {
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } finally {
                NestedScrollReporter.exitUnboundedScroll()
            }
        }
    } else {
        this
    }
}
```

**Fixed code:**
```kotlin
fun Modifier.nestedScrollGuard(): Modifier = composed {
    if (LocalNestedScrollGuard.current) {
        layout { measurable, constraints ->
            // Detect actual nesting (depth > 0 means inside another guarded scrollable)
            val isNested = NestedScrollReporter.isInsideScrollable()
            val adjustedConstraints = if (isNested && constraints.hasBoundedHeight) {
                // Inner scrollable gets bounded height (parent's max - 1) so
                // CheckScrollableContainerConstraints never fires.
                constraints.copy(minHeight = 0, maxHeight = (constraints.maxHeight - 1).coerceAtLeast(0))
            } else if (isNested && !constraints.hasBoundedHeight) {
                // Parent scrollable is unbounded (Infinity). Use a sane fallback
                // height (screen height) so inner scrollable doesn't crash.
                val fallbackHeight = 4096 // ~4x a tablet screen, safe for both phone/tablet
                Constraints(
                    minWidth = constraints.minWidth,
                    maxWidth = constraints.maxWidth,
                    minHeight = constraints.minHeight.coerceAtMost(fallbackHeight),
                    maxHeight = fallbackHeight
                )
            } else {
                constraints
            }
            NestedScrollReporter.enter()
            try {
                val placeable = measurable.measure(adjustedConstraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } finally {
                NestedScrollReporter.exit()
            }
        }
    } else {
        this
    }
}
```

### 2. Add `isInsideScrollable()` to `NestedScrollReporter` (new method)
```kotlin
@JvmStatic
fun isInsideScrollable(): Boolean = (depth.get() ?: 0) > 0
```

### 3. Keep the debug check (in debug builds) as a developer warning
The check in `enterUnboundedScroll` should remain but with relaxed semantics:
- Only warn (not throw) — because we've already constrained the height, so it's safe
- Or remove the throw entirely since we now prevent the issue

## Tests
- `app/src/test/java/com/authorss81/noteflow/Phase231NestedScrollGuardTest.kt` (existing) — verify it still passes
- Add new test: `NestedScrollGuardPreventionTest.kt` — verify `isInsideScrollable()` returns true when nested, false when sibling

## Constraints
- No new dependency
- No schema change
- No workflow edit
- Must not regress existing 3420/0 tests

## DoD
- `NestedScrollGuard.kt` updated with bounded height on nested detection
- `isInsideScrollable()` helper added
- `gradle testDebugUnitTest` green
- `gradle :app:assembleDebug` + `assembleRelease` green
- Report confirms fix works for both `redfin-30` and `gts7xlwifi-33` (tablet)
- `workspace/phase-237/REPORT.md` with file:line evidence

## Timeout
120 minutes (single file change + test)
