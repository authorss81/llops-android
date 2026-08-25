# Phase 217 — Block Resize Polish (code blocks / images / voice blocks)

## Goal
Make every **resizable block** — markdown code blocks, canvas photo/image embeds, and voice/audio blocks — discoverable, precise, and pleasant to resize. Fix the "hidden until you find it" handles from phase-193 while keeping the clean look.

## Context — verified anchors
- **Two code-block surfaces:**
  - Markdown page (`sourceFileType=="text"`) `ui/components/markdown/CodeBlockTextView.kt:33` `Surface(surfaceVariant, RoundedCornerShape 8, fillMaxWidth)` + `Text(buildHighlightedCode)` — **static**, no drag/resize/handle.
  - Canvas embed `CanvasMediaEmbed(type=CODE_BLOCK)` `ui/components/MediaEmbedComponents.kt:279-395` `CodeBlockCard` inside `AnnotationCanvas.kt:5474-5995` `DraggableMediaEmbedCard` — **draggable + 4-corner resize + rotation**.
- **Markdown stack:** CommonMark `Parser.builder().extensions(TablesExtension)` `MarkdownRenderer.kt:102`, `HybridMarkdownEditor.kt:66` block split `MarkdownBlockTokenizer.tokenize` → `MarkdownRenderBlocks` vs `RawBlockEditor`, `MarkdownPreviewScreen.kt:335-946` reader/serif/outline modes, images via `MarkdownInlineImage` `MarkdownRenderer.kt:553-611`.
- **Existing drag/move:** `DraggableStickyNoteCard:5040-5445` / `DraggableMediaEmbedCard:5474-5995` body drag `dragAmount/zoomScale` clamped to page, 4 corners `minW/minH` per type (`AUDIO 220×100..420×280` else `120×80..2000×2000`), `RotationHandle:5930` via `CanvasItemRotationMath.rotationFromHandleDrag`. History: **phase-193 hid handles until dragging** — `services/ResizeHandleVisibilityPolicy.kt:14-49` `visibleAtRest()=false`, `HIDDEN_HANDLE_ALPHA=0f`, handles composed at 0 alpha `AnnotationCanvas.kt:5408,5438,5728-5776,5993`; media embed `cornerVisible=shouldShow(isInteracting,isCollapsedAudio)` `5733,5776...`; audio collapsed `48dp` chip never shows handles (`5912`).
- **Voice:** `services/VoiceNoteManager.kt:26-580` MediaRecorder `AAC 128k 44.1kHz` → `filesDir/voice_notes/*.enc` via `VoiceNoteCrypto`; embed `CanvasMediaEmbed(AUDIO_NOTE 320×135 x100 y+150)` `EditorScreen.kt:1041` → `AudioPlaybackCard.kt:30-347` waveform `WaveformPeakMath.downsample`, `detectTapGestures` seek. Always inside `DraggableMediaEmbedCard`.
- **Phase-193 tradeoff:** 0-alpha keeps hit-box but hurts discoverability. Images have no aspect-lock; no min/max feedback; collapsed voice can't resize by design.

## Tasks
1. **Markdown code blocks — first-time resize affordance:** add a bottom-edge drag handle to `CodeBlockTextView` / `HybridMarkdownEditor.RenderedBlockRow` code fence blocks: `heightIn(min=100.dp, max=600.dp)` surface with handle (`Surface` bottom 8dp strip, haptics on grab via `MotionPolicy.hapticsAllowed`). State in-memory per block hash (or `SettingsManager.codeBlockHeightFor(hash)` if persistence desired — keep transient v1, document upgrade path). Handle **always dimly visible** (override `visibleAtRest=true`, alpha 0.45), not 0. Reuse `ResizeHandleVisibilityPolicy.handleAlpha` but branch for markdown.

2. **Canvas embed handles — discoverability fix:**
   - Change `ResizeHandleVisibilityPolicy.HIDDEN_HANDLE_ALPHA` from `0f` → `0.45f` (or `handleAlpha` returns `0.45` when `!visibleAtRest && !visible`) so corners are dimly visible at rest (hit-box already exists, now you can see it). Keep fade to `1f` on `isInteracting`.
   - Add haptic tick on handle grab, reuse layer-limit `LayerRenderBudgetPolicy.layerLimitNotice` pattern for min/max toast: `Snackbar "Min width reached"` / `"Max width reached"` via `UiFailureTextPolicy`.
   - For `PHOTO` type add `aspectLock` toggle (preserve `rawW/rawH` from `PhotoEmbedCard` decode) — handle drag respects ratio when locked.
   - Collapsed `AUDIO_NOTE` (`48dp` chip `5912`): either allow resize even when collapsed by removing `!isCollapsedAudio` gate for that one handle, **or** document that collapsed is fixed-size and expansion is required to resize — pick one and PIN with test.

3. **Voice block polish:** audio embed keeps same 4-corner logic as other embeds when expanded; collapsed chip drag moves whole block (body drag already works) even if corners stay hidden. No extra `withFrameNanos` pump.

4. **Funnel:** all moves/resizes still go through `viewModel.flushEditorPageSave(page.id, strokes, stickyNotes, mediaEmbeds, layers)` / `onMediaEmbedsChanged` lock-safe debounce used for sticky/media (`EditorScreen.kt:1031-1035,1120-1124`) — keep `activeStrokeList` vs `currentStrokesProvider` split.

## Constraints
- No schema, no workflow edits, no heavy deps. Honor `DeviceTier.LOW_END` — handle overlay is cheap `DrawScope`/`Canvas`, no extra frame pump (already fixed phase-206). Markdown handle must not break wikilink/checkbox interaction.
- DoD: `assembleDebug` + `testDebugUnitTest` green; extend `ResizeHandleVisibilityPolicyTest` that `HIDDEN_HANDLE_ALPHA` is now `0.45` and selection/markdown handles use `visibleAtRest=true`; Paparazzi screenshot markdown code block handle + canvas embed handle states attached; REPORT.md documents each block type before/after handle visibility + resize limits.
