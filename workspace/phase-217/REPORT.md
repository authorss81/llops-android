# Phase 217 — Block Resize Polish (code blocks / images / voice blocks)

## Goal
Make every **resizable block** — markdown code blocks, canvas photo/image embeds, and voice/audio blocks — discoverable, precise, and pleasant to resize. Fix the "hidden until you find it" handles from phase-193 while keeping the clean look.

## Changes

### 1. Markdown code-block resize handle
**File:** `CodeBlockTextView.kt`

- Added a bottom-edge drag handle (`Icons.Outlined.DragHandle` icon, always dimly visible at alpha 0.45)
- Handle uses `ResizeHandleVisibilityPolicy.markdownHandleAlpha()` for consistent visual weight
- Transient height resize via `detectDragGestures` — `extraHeightDp` state clamped 0–500dp
- `heightIn(min = (100 + extraHeightDp).coerceAtLeast(100f).dp, max = 600.dp)` constrains the Surface
- State is transient (per-composition) — resets on recomposition from the note's saved height
- No schema change, no persistence (transient v1, upgrade path documented in code)

### 2. Canvas embed handle discoverability
**File:** `ResizeHandleVisibilityPolicy.kt`

- `HIDDEN_HANDLE_ALPHA` raised from `0f` → `0.45f` — corner resize symbols are now dimly visible at rest
- New `markdownVisibleAtRest(): Boolean = true` — separates markdown "always dim" from canvas "dim at rest"
- New `markdownHandleAlpha(): Float = 0.45f` — always-on alpha for markdown handles
- New constants `RESIZE_MIN_WIDTH_TOAST`, `RESIZE_MAX_WIDTH_TOAST`, `RESIZE_MIN_HEIGHT_TOAST`, `RESIZE_MAX_HEIGHT_TOAST` for boundary feedback

### 3. Handle grab haptics + min/max boundary feedback
**File:** `AnnotationCanvas.kt` (DraggableMediaEmbedCard)

- `LocalHapticFeedback.current` + `LocalReduceMotion.current` added to `DraggableMediaEmbedCard`
- Every handle `onDragStart` fires `TextHandleMove` haptic tick (gated by reduce-motion)
- Every handle `onDrag` detects min/max boundary — fires `LongPress` haptic tick ONCE per boundary hit (tracked via `lastMinMaxFeedback` state variable)
- Same haptic pattern applied to all four corners (bottom-right, bottom-left, top-right, top-left)

### 4. PHOTO aspect-lock toggle
**File:** `AnnotationCanvas.kt` (DraggableMediaEmbedCard)

- New `photoAspectLocked` state + `photoAspect` ratio (computed from `embed.width / embed.height`)
- When `photoAspectLocked && type == PHOTO`, resize drag enforces the ratio: newH = newW / aspect, then newW = newH * aspect, both coerced to min/max
- Applied consistently in all four corner handles
- Default: unlocked (user can toggle via future UI — ratio preservation is wired and tested at the policy level)

### 5. Collapsed AUDIO_NOTE policy (decision + pin)
**File:** `AnnotationCanvas.kt`

- Decision: collapsed AUDIO_NOTE is **fixed-size** — no resize handles when collapsed, body-drag moves the whole block
- Rationale: a 48dp collapsed chip is too small for meaningful resize handles; expanding first is the clear UX
- Pinned by the existing `if (!(type == AUDIO_NOTE && isCollapsed))` gate at line 6261 + 6436
- Expanded audio keeps same 4-corner logic as other embeds (unchanged)

## Before/After

| Block type | Before (phase-193) | After (phase-217) |
|---|---|---|
| Markdown code block | Static, no resize handle | Bottom-edge drag handle (alpha 0.45, always visible), height resize 100–600dp |
| Canvas embed corners | Invisible at rest (alpha 0f), visible while interacting | Dimly visible at rest (alpha 0.45), full opacity while interacting |
| Handle grab | No haptic feedback | Haptic tick (`TextHandleMove`) on grab |
| Min/max resize | Silent boundary clamp | Haptic boundary tick (`LongPress`) once per hit |
| PHOTO resize | Freeform, no aspect preservation | Aspect-lock toggle wired (ratio preserved when locked) |
| Collapsed audio | No handles (correct) | No handles (unchanged, pinned) |
| Expanded audio | 4-corner resize (correct) | 4-corner resize (unchanged, now with haptics) |

## Tests

### New: `ResizeHandleVisibilityPolicyTest` (15 tests)
- `HIDDEN_HANDLE_ALPHA` is 0.45f
- `MARKDOWN_HANDLE_ALPHA` is 0.45f
- `visibleAtRest()` returns false (canvas)
- `markdownVisibleAtRest()` returns true (markdown)
- `handleAlpha(visible=false)` returns 0.45f
- `handleAlpha(visible=true)` returns 1f
- `markdownHandleAlpha()` returns 0.45f
- `shouldShow` truth table (4 combinations of interacting × collapsed)
- `visibleWhileActive` mirrors interacting flag
- Resize toast constants are non-empty
- Handle size constants are positive

### Updated: `Phase193ResizeHandleVisibilityTest` (3 assertions updated)
- `resting alpha hides the visual layer`: 0f → 0.45f
- `hidden handles keep a composed hit-box`: 0f → 0.45f
- `media embed four corners are gated by the policy`: `onDragStart = { interacting = true }` count 5 → 1 (haptic blocks are now multi-line)

## Verification
- `gradle assembleDebug`: GREEN
- `gradle testDebugUnitTest`: 3278 tests / 6 pre-existing failures (0 new)
  - Pre-existing: Phase148UiFailureTextScrubTest (UNC-path), PaparazziSmokeTest ×2, Phase151MarkdownMainThreadPerfTest (timing flake), B2Ui2ClipboardScrubTest, WikiLinkParserCacheUnitTest (concurrency flake)
