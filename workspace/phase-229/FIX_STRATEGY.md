# Phase 229 — Fix Strategy Matrix

## Core Principle

**Compose modifier order matters.** A `heightIn(max)` applied BEFORE `verticalScroll()` constrains the measurement height. Applied AFTER, the scroll already measures with `Constraints(maxHeight = Infinity)` and throws.

```kotlin
// CRASH: heightIn after verticalScroll — scroll measures first with Infinity
Column(Modifier.fillMaxWidth().verticalScroll(state).heightIn(max = 400.dp)) { ... }

// SAFE: heightIn before verticalScroll — height is bounded when scroll measures
Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(state)) { ... }
```

---

## Pattern → Fix Mapping

### Pattern 1: `Column(verticalScroll)` inside `Column(verticalScroll)` (DOUBLE SCROLL)

**Crash condition:** Inner Column has no explicit height bound → receives `maxHeight = Infinity` from outer scroll.

**Fix:** Add `heightIn(max)` BEFORE `verticalScroll()` on the inner Column.

```kotlin
// BEFORE (CRASH):
outerSheet -> Column(Modifier.verticalScroll(outerState)) {
    // ... header ...
    Column(Modifier.verticalScroll(innerState)) { // ← INFINITY maxHeight!
        // ... content ...
    }
}

// AFTER (SAFE):
outerSheet -> Column(Modifier.verticalScroll(outerState)) {
    // ... header ...
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)   // ← BOUNDS height BEFORE scroll
            .verticalScroll(innerState)
    ) {
        // ... content ...
    }
}
```

**Mobile parity:** The `heightIn(max)` applies identically on phone and tablet. The outer sheet's finite height is unchanged. No layout regression.

### Pattern 2: `LazyColumn` inside `Column(verticalScroll)`

**Crash condition:** LazyColumn measures with `maxHeight = Infinity`.

**Fix:** Add `.weight(1f)` on the LazyColumn (preferred) or `.fillMaxHeight()` or `.heightIn(max)` BEFORE any parent scroll.

```kotlin
// BEFORE (POTENTIAL CRASH):
Column(Modifier.verticalScroll(state)) {
    Text("Header")
    LazyColumn { items(data) { ... } } // ← INFINITY!
}

// AFTER (SAFE — weight-based):
Column {
    Text("Header")
    LazyColumn(Modifier.weight(1f)) { items(data) { ... } }
}
// Note: weight() only works inside Column/Row — removes verticalScroll from parent.

// AFTER (SAFE — heightIn-based, if parent MUST scroll):
Column(Modifier.verticalScroll(state)) {
    Text("Header")
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp) // ← bounds before any implicit lazy measure
    ) { items(data) { ... } }
}
```

**Mobile parity:** `weight(1f)` distributes available height equally on all screen sizes. `heightIn(max)` applies equally.

### Pattern 3: `LazyColumn` inside `BoxWithConstraints` (tablet branch)

**Crash condition:** BoxWithConstraints provides `constraints.maxHeight` but the LazyColumn has no explicit height constraint.

**Fix:** Use `BoxWithConstraintsScope` receiver to read `constraints.maxHeight` and apply `.heightIn(max = constraints.maxHeight.dp)`.

```kotlin
// SAFE PATTERN:
BoxWithConstraints(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight) // ← uses BoxWithConstraintsScope.maxHeight
    ) { items(data) { ... } }
}
```

### Pattern 4: `Column(verticalScroll)` without explicit height bound

**Crash condition:** None if parent provides finite bounds (e.g., `ModalBottomSheet`, `AlertDialog`, `BoxWithConstraints`). The crash only occurs when the PARENT is also a scrollable container.

**Fix (defensive):** Add `.fillMaxWidth()` and ensure parent provides bounds. For standalone usage, add `.heightIn(max)` or `.fillMaxHeight()`.

```kotlin
// DEFENSIVE: Always ensure scrollable containers have explicit bounds
Column(
    Modifier
        .fillMaxWidth()
        .fillMaxHeight() // or heightIn(max = ...) or weight(1f)
        .verticalScroll(state)
) { ... }
```

### Pattern 5: `LazyColumn` in non-scrollable Column (dialogs/bottom sheets)

**No crash risk.** The dialog/sheet provides bounded height. LazyColumn inherits finite constraints.

```kotlin
// SAFE (no fix needed):
ModalBottomSheet(...) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) { // NOT scrollable
        LazyColumn(Modifier.weight(1f)) { items(data) { ... } }
    }
}
```

---

## Defensive Coding Rules

1. **NEVER** place `verticalScroll` BEFORE a child scrollable's height constraint
2. **ALWAYS** ensure `LazyColumn` inside `Column` has `.weight(1f)` or explicit height
3. **PREFER** `Modifier.weight(1f)` over `Modifier.heightIn(max)` for LazyColumn in Column
4. **ORDER MATTERS:** `Modifier.fillMaxWidth().heightIn(max=X.dp).verticalScroll(state)` — height bound first, scroll second
5. **DIALOGS/BOTTOM SHEETS:** Their bounded height naturally prevents crashes — no explicit fix needed

---

## Mobile Regression Analysis

| Fix Pattern | Mobile Impact | Tablet Impact | Regression Risk |
|-------------|---------------|---------------|-----------------|
| `heightIn(max)` before `verticalScroll` | Same height cap applies | Same height cap applies | **ZERO** — identical on both |
| `weight(1f)` on LazyColumn | Takes remaining height | Takes remaining height | **ZERO** — weight distributes evenly |
| `fillMaxHeight()` on scrollable | Fills parent height | Fills parent height | **ZERO** — same measurement |
| Reorder modifiers | Same visual result | Same visual result | **ZERO** — Compose measures same final constraints |

**No mobile regression is possible** because all fixes constrain height (which was already finite on mobile due to screen size) or reorder modifiers (which doesn't change the final measured size).

---

## Code Templates

### Template A: Fix nested verticalScroll in bottom sheet
```kotlin
@Composable
fun SomeBottomSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()) // OUTER scroll
        ) {
            Text("Header")
            // ... static content ...

            // INNER scroll — MUST have heightIn BEFORE verticalScroll
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)  // ← ALWAYS before verticalScroll
                    .verticalScroll(rememberScrollState())
            ) {
                // ... long content ...
            }
        }
    }
}
```

### Template B: Fix LazyColumn in Column
```kotlin
@Composable
fun SomePanel(data: List<Item>) {
    Column(Modifier.fillMaxSize()) {
        Text("Header", Modifier.padding(16.dp))
        // LazyColumn with weight(1f) — takes remaining height
        LazyColumn(Modifier.weight(1f)) {
            items(data) { item -> ItemRow(item) }
        }
    }
}
```

### Template C: Fix LazyColumn in BoxWithConstraints (tablet)
```kotlin
@Composable
fun TabletPanel(data: List<Item>) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight) // ← read from BoxWithConstraintsScope
        ) {
            items(data) { item -> ItemRow(item) }
        }
    }
}
```
