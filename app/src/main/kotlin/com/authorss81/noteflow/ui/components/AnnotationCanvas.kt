@file:android.annotation.SuppressLint("RestrictedApi", "NewApi")
package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStrokeStyle
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.CanvasMediaEmbed
import com.authorss81.noteflow.services.BrushTextureEngine
import com.authorss81.noteflow.services.FloatingWidgetDragPolicy
import com.authorss81.noteflow.services.MinimapGeometryPolicy
import com.authorss81.noteflow.services.PressureCurve
import com.authorss81.noteflow.services.PressureCurveHelper
import com.authorss81.noteflow.services.ProtobufBrushLoader
import com.authorss81.noteflow.services.BrushStrokeMath
import com.authorss81.noteflow.services.RawInputSample
import com.authorss81.noteflow.services.StrokeBatchPolicy
import com.authorss81.noteflow.services.StrokeInputBatcher
import com.authorss81.noteflow.services.StrokeSmoothingPolicy
import com.authorss81.noteflow.services.StrokeStabilizer
import com.authorss81.noteflow.services.SymmetryHelper
import com.authorss81.noteflow.services.SymmetryMode
import com.authorss81.noteflow.data.model.CanvasStickyNote
import com.authorss81.noteflow.data.model.CanvasTextStyle
import com.authorss81.noteflow.data.model.MediaEmbedType
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import androidx.ink.brush.Brush as InkBrush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke as InkStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Phase 124: one point of the accumulated eraser path — canvas (world)
 * coordinates plus the touch pressure sampled at capture time, so the PARTIAL
 * eraser stamps a pressure-aware round mask (see EraserGeometryPolicy).
 */
data class EraseSample(val pos: Offset, val pressure: Float)

@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AnnotationCanvas(
    modifier: Modifier = Modifier,
    strokes: List<Stroke>,
    stickyNotes: List<CanvasStickyNote> = emptyList(),
    mediaEmbeds: List<CanvasMediaEmbed> = emptyList(),
    currentTool: StrokeTool,
    currentColor: Color,
    currentWidth: Float,
    template: String = "blank",
    pageTags: String = "",
    paperColorHex: String = "#FFFFFF",
    divideIntoPages: Boolean = true,
    backgroundImage: ImageBitmap? = null,
    paperTexture: ImageBitmap? = null,
    pdfPageBitmaps: Map<Int, ImageBitmap> = emptyMap(),
    activeRawBitmapMap: Map<Int, android.graphics.Bitmap> = emptyMap(),
    pdfTotalPages: Int = 1,
    pdfPageFilter: Int = 0,
    isPdf: Boolean = false,
    isContinuousMode: Boolean = false,
    zoomScale: Float = 1f,
    panOffset: Offset = Offset.Zero,
    palmRejectionEnabled: Boolean = true,
    stylusPressureEnabled: Boolean = true,
    advancedBrushesEnabled: Boolean = false,
    showMinimap: Boolean = false,
    // Phase 129: the minimap is draggable only when the user opts in via the
    // canvas settings sheet (default OFF) — the drag offset is session-scoped.
    minimapDraggable: Boolean = false,
    isRecordingVoice: Boolean = false,
    recordingElapsedMsProvider: () -> Long = { 0L },
    activeVoicePlaybackFilePath: String? = null,
    layers: List<com.authorss81.noteflow.data.model.LayerEntity> = emptyList(),
    activeLayerId: String? = null,
    isPlayingVoice: Boolean = false,
    activeVoicePositionMsProvider: () -> Long = { 0L },
    activeVoiceSpeed: Float = 1.0f,
    onZoomScaleChanged: (Float) -> Unit = {},
    onPanOffsetChanged: (Offset) -> Unit = {},
    onVisiblePageWindowChanged: (Set<Int>) -> Unit = {},
    // Phase-150 review fix 4: raised ONCE per session when the note's own
    // stroke content extends past the CanvasPageBudgetPolicy.MAX_DYNAMIC_PAGES
    // world ceiling (the fold is silent otherwise). Default no-op so pre-existing
    // call sites (tests/previews) keep compiling unchanged.
    onDynamicPageCountCapped: (() -> Unit)? = null,
    onStrokesChanged: (List<Stroke>) -> Unit,
    // Phase 205: CURRENT-state provider for stroke-list emissions. The gesture
    // closures below can hold a FROZEN `strokes` parameter for a whole session
    // (pointerInput only restarts on its keys), so any full-list payload rebuilt
    // from that capture resurrected erased strokes / reordered rapid commits.
    // The provider is read at APPLY time instead; EditorScreen passes `{ strokes }`
    // whose delegated state always reflects the latest committed list.
    currentStrokesProvider: () -> List<Stroke> = { strokes },
    // Phase 205: EPHEMERAL channel for laser-fade cleanup. Expired trails are
    // not an edit — the parent must update its list + autosave WITHOUT pushing
    // undo entries or clearing redo (see handleLaserTrailsExpired).
    onLaserTrailsExpired: (List<Stroke>) -> Unit = {},
    onStickyNotesChanged: (List<CanvasStickyNote>) -> Unit = {},
    onMediaEmbedsChanged: (List<CanvasMediaEmbed>) -> Unit = {},
    onToggleVoicePlay: (String) -> Unit = {},
    onVoiceSeekTo: (Long) -> Unit = {},
    onVoiceSpeedChange: (Float) -> Unit = {},
    onColorSampled: (Color) -> Unit = {},
    onDrawingStart: () -> Unit = {},
    onDrawingEnd: () -> Unit = {},
    onCanvasTap: () -> Unit = {},
    onExtractOcr: ((String) -> Unit)? = null,
    gpuWetBrushesEnabled: Boolean = true,
    shapeAutoSnapEnabled: Boolean = false,
    // Phase 213: per-stroke soft drop shadows ("paper elevation"). Default ON;
    // BrushShadowPolicy still skips utility tools per stroke, and the low-end
    // auto-off (with an honest one-time message) lives in EditorScreen, so
    // pre-213 call sites that don't pass this keep compiling unchanged.
    paperElevationEnabled: Boolean = true,
    // Phase 227: deckled paper edge + tunable texture strength. Both default to
    // the exact pre-227 look — ROUNDED corners, 50-texture — so untouched call
    // sites (tests/previews) render byte-identical to before.
    paperEdgeKey: String = com.authorss81.noteflow.services.PaperEdgePolicy.DEFAULT_KEY,
    paperTextureStrength: Int = com.authorss81.noteflow.services.PaperTextureStrengthPolicy.DEFAULT,
    hapticsEnabled: Boolean = true,
    stabilizerEnabled: Boolean = false,
    // Phase 197 (PERF 1.2): user strength trim 0–100 over the per-brush
    // smoothing baseline. 100 (= default) is neutral, preserving the pre-197
    // window-8 behavior for stylus + no-preset sessions exactly.
    stabilizerStrengthPercent: Int = com.authorss81.noteflow.services.StrokeSmoothingPolicy.DEFAULT_SLIDER_PERCENT,
    // Phase 214: lag-compensation dial 0..35 (%) mapped onto the retune
    // prediction fraction (15 = the legacy 0.15 constant), and the smoothing
    // model selection ("ewma" | "one_euro"). Both apply at the NEXT stroke
    // start via rememberUpdatedState reads — never mid-stroke.
    stabilizerPredictionPercent: Int = com.authorss81.noteflow.services.StrokeSmoothingPolicy.DEFAULT_PREDICTION_PERCENT,
    stabilizerModelKey: String = com.authorss81.noteflow.services.StrokeSmoothingPolicy.MODEL_EWMA,
    pressureCurve: PressureCurve = PressureCurve.LINEAR,
    symmetryMode: SymmetryMode = SymmetryMode.OFF,
    // Phase 13: rich canvas content.
    selectedStickerId: String? = null,
    onPlaceSticker: (Offset, Int) -> Unit = { _, _ -> },
    activeBrushPresetId: String? = null,
    // Phase 220: pro brush controls — blender strength (SMUDGE) and texture
    // scatter. Both override ToolPreset defaults; 0 = legacy behaviour.
    blenderStrengthPercent: Int = 85,
    scatterAmountPercent: Int = 0,
    // Phase 19: dual erasers (STROKE = classic whole-stroke, PARTIAL = trim to
    // surviving segments) + render-time vibrancy/saturation boost (0 = off).
    eraserMode: com.authorss81.noteflow.services.EraserMode = com.authorss81.noteflow.services.EraserMode.STROKE,
    vibrancyEnabled: Boolean = false,
    vibrancyBoostLevel: Float = 0f,
    // Phase 27: multi-color brush effects. The CURRENT mode/seed/end-color flow
    // into new strokes (preview + commit); committed strokes re-derive their
    // color from the mode + seed stored ON the stroke itself.
    currentColorMode: com.authorss81.noteflow.data.model.StrokeColorMode = com.authorss81.noteflow.data.model.StrokeColorMode.SOLID,
    currentColorSeed: Int = 0,
    currentGradientToColor: Color = Color(0xFF1B365D),
    // Phase 155: canvas workshop — two-finger undo/redo + quick-color ring.
    // Both are strictly opt-in via settings (default OFF so classic rendering and
    // the single-finger stroke path stay byte-for-byte unchanged); default no-op
    // callbacks keep pre-existing call sites compiling.
    twoFingerGesturesEnabled: Boolean = false,
    onTwoFingerUndo: () -> Unit = {},
    onTwoFingerRedo: () -> Unit = {},
    quickColorRingEnabled: Boolean = false,
    quickColorSwatches: List<Color> = emptyList(),
    onQuickColorPicked: (Color) -> Unit = {},
    // Phase 155 review fixes: the user's imported `.inkbrush` presets are exposed
    // here so the active-preset resolver can also resolve imported ids (they are
    // NOT members of BrushPresetPack). Default empty — pre-existing call sites
    // compile unchanged.
    importedBrushPresets: List<com.authorss81.noteflow.services.BrushPreset> = emptyList(),
    // Phase 178: per-page reference-image underlay (ROADMAP Phase-07 encouraged
    // item). A dimmed bitmap rendered as the BOTTOM layer (paper → template →
    // reference image → background bitmaps → strokes/layers), so strokes draw
    // OVER it and never modify it. Geometry is stored with the page (world
    // coordinates matching the stroke space); opacity is range-gated by
    // ReferenceImagePolicy. All defaults keep pre-existing call sites unchanged.
    referenceImage: ImageBitmap? = null,
    // Phase 225: the CONFINED on-disk path of the reference photo (already
    // resolved through InlineImagePathPolicy by the caller). The eyedropper
    // samples a 1:1 REGION of this file directly so the picked color is the RAW
    // (undimmed) source pixel — never the alpha-clamped underlay frame. Default
    // null keeps pre-existing call sites compiling; without a path the eyedropper
    // falls back to the layer/paper sampling path.
    referenceImagePath: String? = null,
    // Phase 225 (review fix): the reference FILE's real pixel dimensions,
    // resolved ONCE by the caller when the path is read (the eye-dropper samples
    // against these, not the on-screen dims). Threading them in avoids re-opening
    // the file with a bounds-only decode on every eye-dropper preview move, which
    // would add synchronous disk I/O to the UI thread while dragging. Zero/0
    // means "unknown" → the sample falls through to the layer/paper path.
    referenceImageFileWidth: Int = 0,
    referenceImageFileHeight: Int = 0,
    referenceImageOpacity: Float = com.authorss81.noteflow.services.ReferenceImagePolicy.DEFAULT_OPACITY,
    referenceImageX: Float = 0f,
    referenceImageY: Float = 0f,
    referenceImageWidth: Float = 0f,
    referenceImageHeight: Float = 0f,
    referenceImagePage: Int = 0,
    // Phase 215: real lasso/box-marquee stroke selection on the SELECT tool.
    // The selection itself is TRANSIENT state owned by EditorScreen (survives
    // rotation via rememberSaveable; cleared on page switch/tool change); this
    // canvas only renders it and reports completed lasso/marquee gestures
    // through [onSelectionChanged] (null = clear). Defaults keep pre-existing
    // call sites compiling unchanged.
    strokeSelection: com.authorss81.noteflow.data.model.StrokeSelection = com.authorss81.noteflow.data.model.StrokeSelection.EMPTY,
    onSelectionChanged: (com.authorss81.noteflow.data.model.StrokeSelection?) -> Unit = {},
    // Phase 216: translate (move) selected strokes by a world-coordinate delta.
    // Called once per drag-end from the selection drag surface.
    onSelectionTranslate: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    // Phase 226: scale + rotate selected strokes. Called once per gesture end
    // (the gesture accumulates in the selection overlay, previewing via a
    // dashed outline only; the single commit call feeds ONE undo entry in
    // EditorScreen). centre is the selection centre in world coords.
    onSelectionScale: (scaleX: Float, scaleY: Float, centerX: Float, centerY: Float) -> Unit = { _, _, _, _ -> },
    onSelectionRotate: (degrees: Float, centerX: Float, centerY: Float) -> Unit = { _, _, _ -> },
    // Phase 226: when true a corner scale-drag keeps the selection's aspect
    // ratio (uniform scale); when false X/Y scale independently.
    selectionTransformLocked: Boolean = true,
    // Phase 222: tilt shading (stylus angle → width/alpha modulation).
    tiltShadingEnabled: Boolean = false,
    // Phase 222: per-layer alpha-lock + clipping-mask sets.
    alphaLockLayerIds: Set<String> = emptySet(),
    clippingMaskLayerIds: Set<String> = emptySet(),
    // Phase 223: ruler/straight-line snap. When enabled the live preview snaps a
    // rough freehand drag to an exact straight LINE (bypassing the shape-snap
    // perpendicularDeviation gate) and draws a live start→current guide.
    rulerEnabled: Boolean = false
) {
    val vibrancyBoost = if (vibrancyEnabled) vibrancyBoostLevel.coerceIn(0f, 1f) else 0f
    var internalZoomScale by remember { mutableFloatStateOf(zoomScale) }
    var internalPanOffset by remember { mutableStateOf(panOffset) }

    // 22.9: light tick when a stroke is committed (skipped under reduce-motion).
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current

    var layoutZoomScale by remember { mutableFloatStateOf(zoomScale) }
    var layoutPanOffset by remember { mutableStateOf(panOffset) }

    LaunchedEffect(internalZoomScale, internalPanOffset) {
        // Debounce updating the layout pan/zoom by 100ms to avoid full recomposition during active gestures
        kotlinx.coroutines.delay(100)
        layoutZoomScale = internalZoomScale
        layoutPanOffset = internalPanOffset
    }

    val currentOnZoomScaleChangedState by rememberUpdatedState(onZoomScaleChanged)
    val currentOnPanOffsetChangedState by rememberUpdatedState(onPanOffsetChanged)

    LaunchedEffect(zoomScale, panOffset) {
        internalZoomScale = zoomScale
        internalPanOffset = panOffset
    }

    val coroutineScope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    fun updateZoomAndPan(newScale: Float, newOffset: Offset) {
        internalZoomScale = newScale
        internalPanOffset = newOffset
        debounceJob?.cancel()
        debounceJob = coroutineScope.launch {
            delay(100)
            currentOnZoomScaleChangedState(newScale)
            currentOnPanOffsetChangedState(newOffset)
        }
    }

    // Phase 172: minimap quick-view navigation (zoom-to-fit / jump-home). The
    // TARGET transform is computed by the pure-JVM CanvasNavigationPolicy; this
    // wrapper drives it through the SAME transform pipeline updateZoomAndPan
    // exposes (debounced onZoomScaleChanged/onPanOffsetChanged → EditorScreen),
    // animating with a SpringCanvasPan spring when motion is allowed and SNAPPING
    // under reduce-motion (the existing minimap zoom buttons stay jump-style).
    val navScaleAnim = remember { Animatable(1f) }
    val navPanAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var navRequestSeq by remember { mutableIntStateOf(0) }
    var navRequest by remember { mutableStateOf<Pair<Float, Offset>?>(null) }

    fun navigateCanvasTo(targetScale: Float, targetPan: Offset) {
        if (!com.authorss81.noteflow.services.CanvasNavigationPolicy.shouldAnimate(reduceMotion)) {
            updateZoomAndPan(targetScale, targetPan)
            return
        }
        navRequestSeq++
        navRequest = Pair(targetScale, targetPan)
    }

    LaunchedEffect(navRequestSeq) {
        val request = navRequest ?: return@LaunchedEffect
        navRequest = null
        navScaleAnim.snapTo(internalZoomScale)
        navPanAnim.snapTo(internalPanOffset)
        val panTuning = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.CANVAS_PAN
        )
        val scaleSpec: AnimationSpec<Float> = com.authorss81.noteflow.theme.MotionSystem.SpringCanvasPan
        val panSpec: AnimationSpec<Offset> = spring(
            dampingRatio = panTuning.dampingRatio,
            stiffness = panTuning.stiffness
        )
        // Drive scale + pan in lockstep (both animators tick on the same frame
        // clock), writing the CONVERGED pair through updateZoomAndPan each frame
        // so the external onZoomScaleChanged/onPanOffsetChanged settle on a
        // consistent transform, not a flicker between two sampled axes.
        val scaleJob = launch { navScaleAnim.animateTo(request.first, scaleSpec) }
        val panJob = launch { navPanAnim.animateTo(request.second, panSpec) }
        launch {
            while (scaleJob.isActive || panJob.isActive) {
                updateZoomAndPan(navScaleAnim.value, navPanAnim.value)
                withFrameNanos { }
            }
            updateZoomAndPan(request.first, request.second)
        }
    }

    val showPageIndicatorState = remember { mutableStateOf(false) }
    var showPageIndicator by showPageIndicatorState
    LaunchedEffect(internalPanOffset, internalZoomScale) {
        showPageIndicator = true
        kotlinx.coroutines.delay(2000)
        showPageIndicator = false
    }

    // Phase 205: LASER trails are EPHEMERAL. The pre-205 expiry path was a
    // `while(true) { …; delay(40) }` poll (25 Hz) that emitted one FULL-list
    // onStrokesChanged per expired stroke — every tick copied the whole list
    // into EditorScreen's undo stack, cleared redo and armed a Room autosave.
    // Post-205:
    //  1. The fade itself is RENDER-SIDE ONLY. While any trail exists this
    //     effect runs ONE frame clock that bumps [laserFadeTick]; the main draw
    //     pass reads it (draw-phase subscription) so the alpha ramp in
    //     drawSingleStroke animates without recomposing or touching state.
    //  2. Removal is ONE batched LaserTrailPolicy.stripExpired call per fade
    //     wave, delivered through onLaserTrailsExpired (ephemeral: no undo
    //     push, no redo clear) with exactly one autosave arm.
    val hasLaserStrokes = strokes.any { it.tool == StrokeTool.LASER }
    val laserFadeTickState = remember { mutableLongStateOf(0L) }
    val currentOnLaserTrailsExpiredState by rememberUpdatedState(onLaserTrailsExpired)
    LaunchedEffect(hasLaserStrokes) {
        if (!hasLaserStrokes) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val now = System.currentTimeMillis()
            laserFadeTickState.longValue = now
            val wave = com.authorss81.noteflow.services.LaserTrailPolicy.stripExpired(
                currentStrokesProvider(),
                now
            )
            if (wave != null) {
                currentOnLaserTrailsExpiredState(wave.remaining)
            }
            if (currentStrokesProvider().none { it.tool == StrokeTool.LASER }) break
        }
    }

    val inkRenderer = remember { CanvasStrokeRenderer.create(false) }

    val activePoints = remember { mutableStateListOf<PointF>() }
    var activeStart by remember { mutableStateOf<PointF?>(null) }
    var activeEnd by remember { mutableStateOf<PointF?>(null) }
    var lastPressure by remember { mutableFloatStateOf(1f) }
    var lastTilt by remember { mutableFloatStateOf(0f) }
    var lastTimestampMs by remember { mutableStateOf<Long?>(null) }
    var activeTargetPage by remember { mutableIntStateOf(pdfPageFilter) }
    var dynamicPageCount by remember { mutableIntStateOf(1) }
    var isPanningBlackSpace by remember { mutableStateOf(false) }

    // Phase 19: sampled erase path (canvas coords) accumulated for the whole
    // duration of one eraser drag, so the PARTIAL eraser splits each stroke by
    // the FULL erase path rather than only the latest sample.
    // Phase 124: each sample also carries the touch pressure at capture time, so
    // the round mask is pressure-aware (heavier press = wider smooth swath).
    val eraseSamples = remember { mutableStateListOf<EraseSample>() }

    // Phase 124: current eraser pointer position in canvas (world) coords, used
    // to draw the eraser cursor preview (round mask for PARTIAL, matched-stroke
    // highlight for STROKE). Updated by a non-consuming pointer tracker; cleared
    // when no pointer is pressed.
    var eraserCursorCanvas by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    // Phase 215: lasso path for the SELECT tool, in canvas (world) coords.
    // Mutated per drag sample; ONLY the dedicated selection-overlay node reads
    // it in its draw scope (phase-198 discipline: per-sample mutations must not
    // invalidate the main canvas pass). Cleared on gesture end/cancel — the
    // committed selection lives in [strokeSelection] (owned by EditorScreen).
    val lassoPathPoints = remember { mutableStateListOf<PointF>() }
    var lassoActive by remember { mutableStateOf(false) }
    // Phase 216: translate-drag state for moving selected strokes.
    var isTranslatingSelection by remember { mutableStateOf(false) }
    var selectionTranslateAccX by remember { mutableFloatStateOf(0f) }
    var selectionTranslateAccY by remember { mutableFloatStateOf(0f) }
    val currentOnSelectionChanged by rememberUpdatedState(onSelectionChanged)
    val currentOnSelectionTranslate by rememberUpdatedState(onSelectionTranslate)
    // Phase 226: scale + rotate commit callbacks read through updated-state so a
    // mid-gesture recomposition never restarts (cancels) the handle drag.
    val currentOnSelectionScale by rememberUpdatedState(onSelectionScale)
    val currentOnSelectionRotate by rememberUpdatedState(onSelectionRotate)

    /**
     * Phase 215: finalize a SELECT drag. Classifies the accumulated path via
     * [LassoPolicy.classifyDrag] — tap → clear, straight drag → box marquee,
     * loop → winding-number lasso — and reports the resulting selection once.
     * Selection changes are deliberately NOT undoable and NEVER touch
     * onStrokesChanged / autosave: only future stroke MUTATIONS (phase 216)
     * go through handleStrokesChange's single undo push.
     */
    fun finishLassoSelection(allStrokes: List<Stroke> = emptyList()): com.authorss81.noteflow.data.model.StrokeSelection {
        val path = lassoPathPoints.toList()
        lassoActive = false
        lassoPathPoints.clear()
        return when (com.authorss81.noteflow.services.LassoPolicy.classifyDrag(path)) {
            com.authorss81.noteflow.services.LassoPolicy.DragKind.TAP -> null
            com.authorss81.noteflow.services.LassoPolicy.DragKind.MARQUEE_BOX ->
                com.authorss81.noteflow.services.StrokeHitPolicy.selectFromRect(
                    allStrokes = allStrokes,
                    rect = com.authorss81.noteflow.services.LassoPolicy.boundsOf(path),
                    layers = layers,
                    activeLayerId = activeLayerId
                )
            com.authorss81.noteflow.services.LassoPolicy.DragKind.LASSO ->
                com.authorss81.noteflow.services.StrokeHitPolicy.selectFromPolygon(
                    allStrokes = allStrokes,
                    polygon = path,
                    layers = layers,
                    activeLayerId = activeLayerId
                )
        }?.also { currentOnSelectionChanged(it) }
            ?: com.authorss81.noteflow.data.model.StrokeSelection.EMPTY
    }

    // Phase 07: stroke stabilizer (one filter instance per continuous stroke).
    // Phase 197: the instance is RE-TUNED at each stroke start (see onDragStart)
    // with the per-brush / per-input window from StrokeSmoothingPolicy — the
    // create() call itself keeps the legacy defaults so an un-tuned session is
    // byte-identical to pre-197.
    val stabilizerFilter = remember { StrokeStabilizer.create() }

    // Phase 197: input source of the LAST raw MotionEvent (UI-thread-only, same
    // passive bridge as pressure/tilt/timestamp below). STYLUS/ERASER → stylus
    // tuning baseline; FINGER (or unknown) → +2 extra smoothing windows. Starts
    // true so a first stroke that somehow skips ACTION_DOWN keeps the exact
    // legacy stylus behavior; every real touch reports a tool type anyway.
    var lastInputIsStylus by remember { mutableStateOf(true) }

    // Phase 214: coalesced-history ingestion. The passive interop bridge pushes
    // every getHistorical* sample of each ACTION_MOVE into this lock-free ring;
    // the drag handler drains it BEFORE the EWMA runs, so batching digitizers
    // contribute 2-3x the temporal resolution instead of one sample per event.
    // Plain objects (not snapshot state): feeding/draining must never
    // invalidate composition.
    val strokeInputBatcher = remember { com.authorss81.noteflow.services.StrokeInputBatcher() }
    val batchDrainScratch = remember {
        ArrayList<com.authorss81.noteflow.services.RawInputSample>(StrokeInputBatcher.DEFAULT_CAPACITY)
    }
    // Monotonic gate stamp: last timestamp actually ingested into the stroke.
    var lastIngestedInputTimestampMs by remember { mutableStateOf<Long?>(null) }

    // Phase 214: model + tension reach the gesture closures without restarting
    // pointerInput (a restart would cancel an in-flight drag). Both apply at
    // the NEXT stroke start — same contract as the strength slider.
    val stabilizerModelKeyState = rememberUpdatedState(stabilizerModelKey)
    val stabilizerPredictionPercentState = rememberUpdatedState(stabilizerPredictionPercent)

    var sampledColorPreview by remember { mutableStateOf<Color?>(null) }
    var eyedropperPosition by remember { mutableStateOf<Offset?>(null) }

    // Phase 221: gradient drag state — start/end in canvas coords.
    var gradientDragStart by remember { mutableStateOf<Offset?>(null) }
    var gradientDragCurrent by remember { mutableStateOf<Offset?>(null) }

    // Phase 155: two-finger undo/redo classifier (pure JVM) + quick-color ring.
    // The classifier lives inside the canvas because it must observe the SAME
    // pointer frames the pinch-zoom handler sees; it never consumes, so the zoom
    // and single-finger stroke paths below are untouched.
    val gestureClassifier = remember { com.authorss81.noteflow.services.GestureRedoUndoClassifier() }
    // Selected index while the ring is open (-1 = none, -2 = center "keep color"
    // per QuickColorRingMath), plus the anchor the ring is centred on.
    var quickColorRingOpen by remember { mutableStateOf(false) }
    var quickColorRingAnchor by remember { mutableStateOf(Offset.Zero) }
    var quickColorRingSelection by remember { mutableIntStateOf(com.authorss81.noteflow.services.QuickColorRingMath.NOTHING_HIT) }

    // Phase 155 (reduce-motion): the ring and its feedback are INSTANT — a
    // long-press attention shiver, no animation tween. Documented here so no
    // later refactor wraps the ring in an Animatable without consulting the
    // reduce-motion policy in MotionPolicy.
    fun openQuickColorRing(anchor: Offset) {
        quickColorRingAnchor = anchor
        quickColorRingSelection = com.authorss81.noteflow.services.QuickColorRingMath.CENTER_SLOT
        quickColorRingOpen = true
    }
    fun closeQuickColorRing() {
        quickColorRingOpen = false
        quickColorRingSelection = com.authorss81.noteflow.services.QuickColorRingMath.NOTHING_HIT
    }

    // Phase 155 review fix: view-config values are read in COMPOSABLE context
    // and captured, because `LocalViewConfiguration.current` cannot be read
    // inside a non-@Composable pointerInput lambda (compile error).
    val quickColorRingLongPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis

    var showTextInputDialog by remember { mutableStateOf(false) }
    var textInputOffset by remember { mutableStateOf<Offset?>(null) }
    var textValue by remember { mutableStateOf("") }
    var textFontStyle by remember { mutableStateOf("SANS") }
    var textFontSizeSp by remember { mutableFloatStateOf(20f) }
    var textBgHex by remember { mutableStateOf<String?>(null) }
    var textAlign by remember { mutableStateOf("LEFT") }
    var textSelectedColorInt by remember(currentColor) { mutableIntStateOf(currentColor.toArgb()) }

    var showStickyNoteDialog by remember { mutableStateOf(false) }
    var stickyNoteOffset by remember { mutableStateOf<Offset?>(null) }
    var stickyNoteText by remember { mutableStateOf("") }
    var stickyNoteColorHex by remember { mutableStateOf("#FEF08A") } // Default Yellow

    var minimapExpanded by remember { mutableStateOf(true) }

    // Phase 129: session-scoped drag offset for the minimap (null = default
    // bottom-right anchor). Survives header collapse/re-expand.
    var minimapDragOffset by remember { mutableStateOf<Offset?>(null) }

    val isLandscape = remember(pageTags, backgroundImage) {
        pageTags.contains("orientation_landscape") || (backgroundImage != null && backgroundImage.width > backgroundImage.height)
    }
    val pageHeightPx = if (isLandscape) 1080f else 1528f
    val pageWidthPx = if (isLandscape) 1528f else 1080f
    val pageGapPx = 64f

    val isLayerLocked = remember(layers, activeLayerId) {
        layers.find { it.id == activeLayerId }?.locked == true
    }

    val filteredStrokes = remember(strokes, pdfPageFilter, isContinuousMode) {
        if (isContinuousMode) strokes else strokes.filter { it.pdfPage == pdfPageFilter }
    }
    val activeStrokeList = remember { mutableStateListOf<Stroke>() }
    LaunchedEffect(filteredStrokes) {
        activeStrokeList.clear()
        activeStrokeList.addAll(filteredStrokes)
    }

    // 23.4: single source of truth for the canvas world size (renderer +
    // minimap must agree, otherwise the minimap maps pan taps to the wrong
    // coordinates in seamless/infinite mode).
    fun computeCanvasWorld(screenW: Float): Pair<Float, Float> {
        val worldW = if (!divideIntoPages) max(screenW, pageWidthPx) else pageWidthPx
        val worldH = when {
            !divideIntoPages -> {
                var maxStrokeY = 0f
                for (stroke in activeStrokeList) {
                    for (pt in stroke.points) {
                        if (pt.y > maxStrokeY) {
                            maxStrokeY = pt.y
                        }
                    }
                }
                max(pageHeightPx * 3f, maxStrokeY + pageHeightPx)
            }
            isContinuousMode -> (pageHeightPx + pageGapPx) * max(1, dynamicPageCount)
            else -> pageHeightPx
        }
        return worldW to worldH
    }

    val filteredStickyNotes = remember(stickyNotes, pdfPageFilter, isContinuousMode) {
        if (isContinuousMode) stickyNotes else stickyNotes.filter { it.pdfPage == pdfPageFilter }
    }
    val activeStickyNoteList = remember { mutableStateListOf<CanvasStickyNote>() }
    LaunchedEffect(filteredStickyNotes) {
        activeStickyNoteList.clear()
        activeStickyNoteList.addAll(filteredStickyNotes)
    }

    val filteredMediaEmbeds = remember(mediaEmbeds, pdfPageFilter, isContinuousMode) {
        if (isContinuousMode) mediaEmbeds else mediaEmbeds.filter { it.pdfPage == pdfPageFilter }
    }
    val activeMediaEmbedList = remember { mutableStateListOf<CanvasMediaEmbed>() }
    LaunchedEffect(filteredMediaEmbeds) {
        activeMediaEmbedList.clear()
        activeMediaEmbedList.addAll(filteredMediaEmbeds)
    }

    fun calculatePageYOffset(pageIndex: Int): Float {
        return if (isContinuousMode) pageIndex * (pageHeightPx + pageGapPx) else 0f
    }

    fun isHittingCard(canvasOffset: Offset): Boolean {
        // Phase 13: rotated-rect hit test so dragging starts precisely on
        // rotated sticky notes / stickers / images as well as axis-aligned ones.
        val hitNote = activeStickyNoteList.any { note ->
            val w = if (note.isCollapsed) 48f else (if (note.width > 0) note.width else 220f)
            val h = if (note.isCollapsed) 38f else (if (note.height > 0) note.height else 180f)
            com.authorss81.noteflow.services.CanvasItemRotationMath.containsInRotatedRect(
                canvasOffset.x, canvasOffset.y, note.x, note.y, w, h, note.rotationDegrees
            )
        }
        if (hitNote) return true

        val hitEmbed = activeMediaEmbedList.any { embed ->
            val w = if (embed.width > 0) embed.width else 340f
            val h = if (embed.height > 0) embed.height else 240f
            com.authorss81.noteflow.services.CanvasItemRotationMath.containsInRotatedRect(
                canvasOffset.x, canvasOffset.y, embed.x, embed.y, w, h, embed.rotationDegrees
            )
        }
        return hitEmbed
    }

    var isDraggingCard by remember { mutableStateOf(false) }
    fun getPageFromCanvasY(canvasY: Float): Int {
        if (!isContinuousMode || dynamicPageCount <= 1) return pdfPageFilter
        val index = (canvasY / (pageHeightPx + pageGapPx)).toInt()
        return index.coerceIn(0, dynamicPageCount - 1)
    }

    // Phase 07: single source of truth for the symmetry mirror axis. Input
    // (eraser hit-testing) and every render branch must use the SAME center, so
    // the mirrored copy can be erased in place and the axis stays on the page
    // grid instead of the raw canvas area. Strokes are stored in world
    // coordinates, [screenW] is the drawable canvas width and [worldY] anchors
    // the per-page axis in divided/infinite mode.
    fun symmetryCenterFor(screenW: Float, worldY: Float): Offset {
        return when {
            !isContinuousMode -> Offset(pageWidthPx / 2f, pageHeightPx / 2f)
            !divideIntoPages -> {
                val world = computeCanvasWorld(screenW)
                Offset(world.first / 2f, world.second / 2f)
            }
            else -> {
                val page = getPageFromCanvasY(worldY)
                val top = calculatePageYOffset(page)
                Offset(max(screenW, pageWidthPx) / 2f, top + pageHeightPx / 2f)
            }
        }
    }

    // Phase 196: OS-level stylus motion prediction (PERF 1.1).
    //
    // The ink path only draws when a real input event lands, so on devices
    // whose digitizer reports slower than the display refreshes (60 Hz finger
    // on a 120 Hz panel; BT styluses that batch samples) the live stroke
    // freezes for a frame — perceived pen lag. `MotionEventPredictor`
    // extrapolates the next sample from the recorded raw MotionEvents; the
    // extrapolated point is drawn as a temporary TAIL of `activePoints` so the
    // EXISTING preview path renders it (no new render pipeline), and is
    // stripped again before each real sample lands / before commit, so stored
    // stroke geometry never contains predicted points.
    //
    // Wiring:
    //   record() — inside the passive pointerInteropFilter below, for EVERY
    //              event (documented usage); it consumes nothing and the
    //              pressure/tilt/timestamp bridge above it is untouched.
    //   predict() — once per rendered frame while a freehand stroke is live
    //               (Compose frame clock loop below), which is exactly the
    //               empty-frame case prediction exists to fill.
    //   reconcile — PredictedTailTracker.stripFrom() at the top of onDrag,
    //               before the commit in onDragEnd, and alongside every
    //               activePoints.clear().
    //
    // Gate: API >= 29 AND newInstance success; otherwise predictor == null and
    // behavior is byte-identical to pre-196 (stabilizer-only). Prediction is an
    // additive preview enhancement, not a degraded feature, so no settings
    // surface or nag message is warranted for older devices.
    val hostView = LocalView.current
    val motionPredictor = remember(hostView) {
        if (!com.authorss81.noteflow.services.MotionPredictionPolicy.isSupported(
                android.os.Build.VERSION.SDK_INT
            )
        ) {
            null
        } else {
            try {
                androidx.input.motionprediction.MotionEventPredictor.newInstance(hostView)
            } catch (t: Throwable) {
                null
            }
        }
    }
    // Plain (non-snapshot) bookkeeping: marking/stripping must never invalidate
    // composition — only the draw reads the preview list.
    val predictedTailTracker = remember {
        com.authorss81.noteflow.services.MotionPredictionPolicy.PredictedTailTracker()
    }
    // Pointer count of the LAST recorded MotionEvent (UI-thread-only access):
    // two pointers means pinch/undo territory — never predict then.
    val predictionPointerCount = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    // Phase 240 (Bug 2): `canvasBoxWindowOffset` was DELETED. Compose's
    // pointerInteropFilter bridge delivers MotionEvents already offset into
    // this box's node-local space (`toMotionEventScope` applies
    // offsetLocation(-rootOffset)), so all ingestion paths share ONE coordinate
    // frame (the live drag handlers' `change.position`, the coalesced batch
    // drain, and the phase-196 predictor's recorded/extrapolated samples) with
    // NO window-offset subtraction anywhere.

    fun dropPredictedTail() {
        predictedTailTracker.stripFrom(activePoints)
    }

    // Per-frame predict loop (API 29+ only). Phase-206 review-fix: EVENT-SCOPED
    // (see the parking comment inside) — it runs frame-clock iterations only
    // while a stroke is in progress; an open idle editor costs zero wakes.
    // Re-keyed on the tool/curve AND on the page geometry (review-fix:
    // pageWidthPx/pageHeightPx/isContinuousMode are plain per-composition vals,
    // so without keying them a mid-session orientation/continuous-mode change
    // would leave stale bounds captured in the loop closure; the
    // predictor/tracker objects survive).
    LaunchedEffect(motionPredictor, currentTool, pressureCurve, pageWidthPx, pageHeightPx, isContinuousMode) {
        val predictor = motionPredictor ?: return@LaunchedEffect
        // Phase-206 review-fix (PERF/BATTERY): PARK until ink actually flows.
        // Pre-fix this effect ran an UNCONDITIONAL frame loop (`withFrameNanos`
        // forever) — one guard pass per frame (60-120 Hz) for the whole editor
        // lifetime even on a static note (the last perpetual frame waker after
        // the pump fix above). Gesture end/cancel paths already strip the tail,
        // so idle frames did no useful work; a mid-stroke effect relaunch
        // resumes immediately because snapshotFlow re-emits the current state.
        while (isActive) {
            snapshotFlow { activePoints.isNotEmpty() }.first { it }
            while (isActive) {
                withFrameNanos { }
                val extend = com.authorss81.noteflow.services.MotionPredictionPolicy.shouldExtendPreview(
                    predictorAvailable = true,
                    freehandTool = currentTool.isFreehandTool,
                    strokeInProgress = activePoints.isNotEmpty(),
                    singlePointerStream = predictionPointerCount.get() == 1,
                    panningWhiteSpace = isPanningBlackSpace
                )
                if (!extend) {
                    dropPredictedTail()
                    // Stroke over (activePoints cleared by the end/cancel path):
                    // leave the frame loop and go back to parking — zero wakes
                    // until the next stroke starts.
                    if (activePoints.isEmpty()) break
                    continue
                }
                val predicted = try {
                    predictor.predict()
                } catch (t: Throwable) {
                    null
                }
                dropPredictedTail()
                if (predicted != null) {
                    val pageTopY = calculatePageYOffset(activeTargetPage)
                    val remappedPressure = PressureCurveHelper.remapPressure(
                        if (predicted.pressure > 0f) predicted.pressure else lastPressure,
                        pressureCurve
                    )
                    val point = com.authorss81.noteflow.services.MotionPredictionPolicy.predictedWorldPoint(
                        predictedViewX = predicted.x,
                        predictedViewY = predicted.y,
                        // Phase 240 fix (Bug 2): the motionPredictor records the SAME
                        // node-local MotionEvent the passive bridge delivers, so its
                        // extrapolated output is node-local too — subtracting the box
                        // WINDOW offset here again displaced the whole predicted tail
                        // away from the pen (same double-offset as the batch drain).
                        // The pure-JVM mapping keeps its canvasWindowX/Y parameters for
                        // unit tests, but the LIVE channel passes the neutral frame.
                        canvasWindowX = 0f,
                        canvasWindowY = 0f,
                        zoomScale = internalZoomScale,
                        panX = internalPanOffset.x,
                        panY = internalPanOffset.y,
                        pageWidthPx = pageWidthPx,
                        pageTopY = pageTopY,
                        pageBottomY = pageTopY + pageHeightPx,
                        pressure = remappedPressure,
                        tilt = lastTilt,
                        timestampMs = predicted.eventTime
                    )
                    if (point != null) {
                        activePoints.add(point)
                        predictedTailTracker.mark()
                    }
                }
            }
        }
    }

    // Color sampling helper for Eyedropper tool
    // Phase 27: samples the ACTUAL rendered pixel (stroked ink composited over the
    // page background) instead of guessing via a loose point-in-+18px radius. The
    // inverse screen->canvas transform (divide by zoom) lives in
    // EyedropperSamplingMath so tests can prove it round-trips exactly.
    // Phase 225: a tap inside the per-page reference-underlay bounds wins FIRST
    // (photoreal palette building); it decodes a 1:1 REGION of the RAW file so
    // the picked color is the undimmed source pixel, then falls through to the
    // layer/paper path when the reference is absent or the decode fails.

    /**
     * Phase 225: decodes the raw reference file's 1:1 integer region around the
     * sampled canvas tap and reads its ARGB. The FILE's own dimensions resolve the
     * pixel (the rendered underlay bitmap is downscaled for memory, but the
     * decode is full-fidelity), so mapping is self-consistent file-to-file. The
     * decode is transient and recycled on the spot (never pooled — the eyedropper
     * decodes are rare and unbounded in the bytes we should NOT hold). Returns
     * null on any failure so the caller falls through to the layer/paper path.
     */
    fun sampleReferenceRegion(canvasX: Float, canvasY: Float, pageTopY: Float): Color? {
        val path = referenceImagePath ?: return null
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return null
            // Phase 225 (review fix): the file's real pixel dims are resolved ONCE
            // by the caller and threaded in, so the per-move eye-dropper preview
            // does NOT re-open the file with a bounds-only decode (which added
            // synchronous disk I/O to the UI thread on every drag). Zero dims mean
            // "unknown" → fall back to the classic bounds-only decode, then bail
            // out of the reference branch so a rare uncached call still can't
            // block on a big photo (the layer/paper sampler takes over).
            var fileW = referenceImageFileWidth
            var fileH = referenceImageFileHeight
            if (fileW <= 0 || fileH <= 0) {
                if (file.length() > MAX_REFERENCE_SAMPLING_FILE_BYTES) return null
                val boundsOptions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, boundsOptions) ?: return null
                fileW = boundsOptions.outWidth
                fileH = boundsOptions.outHeight
            }
            if (fileW <= 0 || fileH <= 0) return null
            val pixel = com.authorss81.noteflow.services.EyedropperSamplingMath.referencePixel(
                canvasX = canvasX,
                canvasY = canvasY,
                pageTopY = pageTopY,
                refX = referenceImageX,
                refY = referenceImageY,
                refWidth = referenceImageWidth,
                refHeight = referenceImageHeight,
                bitmapWidth = fileW,
                bitmapHeight = fileH
            ) ?: return null
            val rect = com.authorss81.noteflow.services.EyedropperSamplingMath.referenceSamplingRect(
                pixel.first, pixel.second, fileW, fileH, margin = 1
            ) ?: return null
            val regionBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                val regionDecoder = android.graphics.BitmapRegionDecoder.newInstance(file.absolutePath, false)
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
                    regionDecoder.decodeRegion(
                        android.graphics.Rect(rect.left, rect.top, rect.right, rect.bottom), opts
                    )
                } finally {
                    regionDecoder.recycle()
                }
            } else {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
            }
            val pixelX = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                pixel.first - rect.left
            } else {
                pixel.first
            }
            val pixelY = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                pixel.second - rect.top
            } else {
                pixel.second
            }
            val argb = regionBitmap.getPixel(pixelX, pixelY)
            regionBitmap.recycle()
            Color(argb)
        } catch (e: Throwable) {
            null
        }
    }

    fun sampleColorAt(canvasOffset: Offset, targetPage: Int): Color {
        val pageTopY = calculatePageYOffset(targetPage)

        if (referenceImagePage == targetPage && referenceImage != null &&
            referenceImageWidth > 0f && referenceImageHeight > 0f
        ) {
            val refPx = com.authorss81.noteflow.services.EyedropperSamplingMath.referencePixel(
                canvasX = canvasOffset.x,
                canvasY = canvasOffset.y,
                pageTopY = pageTopY,
                refX = referenceImageX,
                refY = referenceImageY,
                refWidth = referenceImageWidth,
                refHeight = referenceImageHeight,
                bitmapWidth = referenceImage.width,
                bitmapHeight = referenceImage.height
            )
            if (refPx != null) {
                val sampled = sampleReferenceRegion(canvasOffset.x, canvasOffset.y, pageTopY)
                if (sampled != null) return sampled
            }
        }

        val rawBmp = activeRawBitmapMap[targetPage]

        var baseArgb: Int? = null
        if (rawBmp != null && !rawBmp.isRecycled) {
            val px = com.authorss81.noteflow.services.EyedropperSamplingMath.canvasToPagePixel(
                canvasX = canvasOffset.x,
                canvasY = canvasOffset.y,
                pageTopY = pageTopY,
                pageWidthPx = pageWidthPx,
                pageHeightPx = pageHeightPx,
                bitmapWidth = rawBmp.width,
                bitmapHeight = rawBmp.height
            )
            if (px != null) {
                try {
                    baseArgb = rawBmp.getPixel(px.first, px.second)
                } catch (e: Exception) {
                }
            }
        }
        if (baseArgb == null) {
            baseArgb = try {
                android.graphics.Color.parseColor(paperColorHex)
            } catch (e: Exception) {
                0xFF1B365D.toInt()
            }
        }

        val threshold = 6f
        val hit = activeStrokeList.lastOrNull { stroke ->
            if (stroke.pdfPage != targetPage) return@lastOrNull false
            val samplePoints = stroke.points + listOfNotNull(stroke.start, stroke.end)
            com.authorss81.noteflow.services.EyedropperSamplingMath.distanceToPolyline(
                samplePoints, canvasOffset.x, canvasOffset.y
            ) <= (stroke.width / 2f + threshold)
        }

        if (hit != null && hit.points.isNotEmpty()) {
            val idx = com.authorss81.noteflow.services.EyedropperSamplingMath.nearestIndex(
                hit.points, canvasOffset.x, canvasOffset.y
            )
            val progress = com.authorss81.noteflow.services.BrushColorModeMath.strokeProgress(hit.points, idx)
            val derivedArgb = com.authorss81.noteflow.services.BrushColorModeMath.colorForProgress(
                hit.colorMode,
                hit.colorInt,
                progress,
                hit.colorSeed,
                hit.gradientToColorInt ?: com.authorss81.noteflow.services.BrushColorModeMath.complementaryArgb(hit.colorInt)
            )
            val effAlpha = com.authorss81.noteflow.services.EyedropperSamplingMath.approximateStrokeAlpha(hit.tool.name, 1f)
            val overWithAlpha = (effAlpha * 255).toInt().coerceIn(0, 255) shl 24 or (derivedArgb and 0xFFFFFF)
            return Color(com.authorss81.noteflow.services.EyedropperSamplingMath.composite(baseArgb, overWithAlpha))
        }
        return Color(baseArgb)
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDarkTheme = remember(surfaceColor) {
        (0.299f * surfaceColor.red + 0.587f * surfaceColor.green + 0.114f * surfaceColor.blue) < 0.5f
    }
    val parsedPaperColor = remember(paperColorHex, isDarkTheme) {
        try {
            Color(android.graphics.Color.parseColor(paperColorHex))
        } catch (e: Exception) {
            if (isDarkTheme) Color(0xFF1E293B) else Color.White
        }
    }
    val isDarkPaper = remember(parsedPaperColor) {
        (0.299f * parsedPaperColor.red + 0.587f * parsedPaperColor.green + 0.114f * parsedPaperColor.blue) < 0.5f
    }

    // Phase 200 (PERF 3.3): premium paper feel. A tileable noise tile is
    // generated once per process per paper family and drawn as a single
    // REPEAT-tiled BitmapShader round-rect over the flat paper fill (strictly
    // UNDER the ink pass). Low-end devices skip the grain entirely — it is a
    // cosmetic overlay, and skipping it keeps their draw path unchanged.
    val grainContext = LocalContext.current
    val paperGrainEnabled = remember(grainContext) {
        com.authorss81.noteflow.services.PaperGrainPolicy.enabled(
            com.authorss81.noteflow.utils.DeviceCompatibilityManager.detectDeviceTier(grainContext) ==
                com.authorss81.noteflow.utils.DeviceTier.LOW_END
        )
    }
    val paperGrainBrush = remember(paperGrainEnabled, isDarkPaper) {
        PaperGrainTileCache.brushFor(isDarkPaper, paperGrainEnabled)
    }

    // Phase 227: the user's texture-strength dial dips/gains the CACHED grain
    // tile BEFORE it is drawn. Anchored at 1.0 for the default 50, so a stock
    // install draws the exact pre-227 tile; cache hits remain valid across
    // every strength (no per-strength tile explosion).
    val grainScale = remember(paperTextureStrength) {
        com.authorss81.noteflow.services.PaperTextureStrengthPolicy.grainScale(paperTextureStrength)
    }

    // Phase 227: deckled PaperEdgePolicy applied at drawPaperCard call time
    // (mesh clipping); the page path is memoized per style + paper family so
    // resizing/zooming between frames does not re-derive the cubic waves.
    val paperEdge = remember(paperEdgeKey) {
        com.authorss81.noteflow.services.PaperEdgePolicy.fromKey(paperEdgeKey)
    }

    // Phase 213: the shadow gate is the USER SETTING alone. The low-end-device
    // policy lives UPSTREAM in EditorScreen's device-tier effect, which flips the
    // SETTING itself off ONCE with a non-alarming message (re-enable honored) —
    // NOT here: a second tier gate inside the renderer would make a deliberate
    // settings re-enable silently ineffective on low-end hardware, violating
    // the "never silent degradation" rule. Old strokes are unchanged whenever
    // the flag is false (BrushShadowPolicy.plan returns null → zero draw work).
    val strokeShadowEnabled = paperElevationEnabled

    // R2-b2b4-DOS-02 (phase-150): the reusable layer rasters live in a BOUNDED
    // LRU (was an unbounded `mutableMapOf`), so the native resident bytes stay at
    // or under LayerRenderBudgetPolicy.MAX_RESIDENT_BITMAP_BYTES regardless of
    // how many layers × pages the viewport touches. Evicted entries go back to
    // BitmapPool, never orphaned.
    val layerBitmapCache = remember { com.authorss81.noteflow.ui.components.LayerBitmapLruCache() }
    // Phase 198 (PERF 2.1): the layer rasters invalidate INCREMENTALLY now.
    // The pre-198 blanket clear-on-commit effect wiped EVERY page×layer raster
    // on every stroke commit (and on every partial-eraser sample, which
    // rewrites the stroke list per move), forcing a full re-rasterization of
    // all visible pages on the next frame. That blanket clear is redundant:
    // each cache entry is already keyed by
    // `pageIdx_layerId_symmetryMode_vibrancy` and gated by a CONTENT hash
    // (`cache.hash != strokes.hashCode()`), so only entries whose page+layer
    // content actually changed re-rasterize — lazily, at next draw. Vibrancy
    // and symmetry are key components, so changing them naturally orphans old
    // entries (the LRU releases them to BitmapPool); Stroke/PointF/List are
    // structural data classes, so equal content keeps its raster even when the
    // parent hands down a fresh List instance. The DisposableEffect below
    // still clears everything on unmount; LayerRenderBudgetPolicy bounds the
    // resident bytes in between.
    val wetMixingEffect = remember {
        if (ShaderCapabilityHelper.isAgslSupported) AgslShaders.WetMixingEffect() else null
    }
    val wetCanvasEngine = remember { com.authorss81.noteflow.services.WetCanvasEngine() }
    val wetBrushEngine = remember { com.authorss81.noteflow.services.WetBrushEngine() }
    val graphicsLayer = rememberGraphicsLayer()

    // Phase 13: when a ready-made brush preset is active, pre-fill the wet
    // engine's BrushStudio params. With no preset (null) the engine keeps its
    // default/manual params, so classic brush rendering is unchanged.
    LaunchedEffect(activeBrushPresetId, importedBrushPresets) {
        val preset = activeBrushPresetId?.let { id ->
            com.authorss81.noteflow.services.BrushPresetPack.byId(id)
                ?: importedBrushPresets.firstOrNull { it.id == id }
        }
        if (preset != null) {
            wetCanvasEngine.brushParams = preset.brushParams
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    // Phase 18: brush-physics render settings — velocity width modulation, nib angle.
    // Persisted via the existing SettingsManager (SharedPreferences) path; NO DB schema change.
    val brushRenderSettings = remember(context) { com.authorss81.noteflow.services.SettingsManager(context) }
    // Phase 219: read per-template-type visual overrides once per recomposition.
    fun templateOverridesFor(tpl: String): TemplateOverrides {
        val spacing = brushRenderSettings.templatePref(tpl, "spacing", "").ifEmpty { null }?.toFloatOrNull()
        val opacity = brushRenderSettings.templatePref(tpl, "opacity", "").ifEmpty { null }?.toFloatOrNull()
        val dotR = brushRenderSettings.templatePref(tpl, "dotRadius", "").ifEmpty { null }?.toFloatOrNull()
        val color = brushRenderSettings.templatePref(tpl, "color", "").ifEmpty { null }
        return TemplateOverrides(
            lineSpacingDp = spacing,
            gridOpacity = opacity,
            dotRadiusPx = dotR,
            accentColorHex = color
        )
    }
    var velocityModulated by remember { mutableStateOf(brushRenderSettings.velocityModulationEnabled) }
    var velocityIntensity by remember { mutableFloatStateOf(brushRenderSettings.velocityModulationIntensity) }
    var nibAngleDeg by remember { mutableFloatStateOf(brushRenderSettings.calligraphicNibAngleDeg) }
    var chiselNibAngleDeg by remember { mutableFloatStateOf(brushRenderSettings.chiselNibAngleDeg) }
    val strokeRenderOpts = com.authorss81.noteflow.ui.components.StrokeRenderOpts(
        velocityModulated = velocityModulated,
        velocityIntensity = velocityIntensity,
        nibAngleDeg = nibAngleDeg,
        chiselNibAngleDeg = chiselNibAngleDeg,
        tiltShadingEnabled = tiltShadingEnabled
    )
    // Phase 222: resolve per-layer alpha-lock + clipping-mask from SettingsManager.
    val resolvedAlphaLockIds = remember(layers) {
        val settings = com.authorss81.noteflow.services.SettingsManager(context)
        layers.filter { settings.isLayerAlphaLockEnabled(it.id) }.map { it.id }.toSet()
    }
    val resolvedClippingMaskIds = remember(layers) {
        val settings = com.authorss81.noteflow.services.SettingsManager(context)
        layers.filter { settings.isLayerClippingMaskEnabled(it.id) }.map { it.id }.toSet()
    }
    // Per-stroke texture seed — refreshed on drag start so each live stroke owns a
    // fresh grain/bristle phase (the committed copy gets one from its stroke id).
    var currentStrokeSeed by remember { mutableFloatStateOf(0f) }
    // Phase 206 (PERF/BATTERY): the wet-engine Choreographer pump is EVENT-DRIVEN.
    // Pre-206 a `LaunchedEffect(Unit)` posted a SELF-REPOSTING FrameCallback that
    // was never unregistered (zero removeFrameCallback call-sites repo-wide), so
    // every editor visit STACKED another immortal 60-120 Hz loop doing frame-time
    // sampling + a thermal-status service call + tier re-evaluation PER FRAME even
    // on a static untouched note. Now the callback is owned by [WetBrushFramePump]:
    // it re-posts only while a stroke is actively being drawn (start()/stop() from
    // the drag handlers below), samples thermal status at ≤1 Hz, and the
    // DisposableEffect's onDispose UNREGISTERS it so teardown can never leak it.
    // Phase-206 review-fix: also keyed on gpuWetBrushesEnabled so a live
    // settings toggle REBUILDS the pump (DisposableEffect below stops the old
    // one) instead of manualOverrideProvider serving a value captured at the
    // first composition forever.
    val wetFramePump = remember(wetBrushEngine, gpuWetBrushesEnabled) {
        WetBrushFramePump(
            wetBrushEngine = wetBrushEngine,
            isAgslSupported = ShaderCapabilityHelper.isAgslSupported,
            manualOverrideProvider = { gpuWetBrushesEnabled },
            thermalStatusProvider = { ThermalSanityHelper.getCurrentThermalStatus(context) }
        )
    }
    DisposableEffect(wetFramePump) {
        onDispose {
            // Phase 206: unregister the frame callback — no more immortal pump.
            wetFramePump.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // R2-b2b4-DOS-02 (phase-150): the LRU's clear() releases every
            // cached bitmap back to the pool on unmount.
            layerBitmapCache.clear()
        }
    }

    // Phase 242: commit an IN-PROGRESS stroke if the canvas leaves composition
    // mid-gesture (navigate away / close the page while the pointer is still
    // down, before `detectDragGestures.onDragEnd` ever runs — e.g. a swipe that
    // is classified as a navigation and triggers pointer-cancel). Without this
    // the partial ink lives only in the ephemeral `activePoints` list and is
    // dropped: it never reaches `strokes`, so the editor's dispose flush —
    // which persists the COMMITTED list — has nothing to save, and on reopen
    // the page the dots the user watched themselves draw are gone.
    //
    // Committing here reproduces exactly what was on screen: `activePoints` are
    // already the smoothed live samples in world coordinates (Z-order = draw
    // order), so no re-simplify/snap pass is applied. All brush parameters are
    // read through [rememberUpdatedState] wrappers so the dispose reads the
    // CURRENT tool/colour/width — a plain parameter capture here would hold the
    // FIRST-composition value after a mid-session tool change. Ordering is safe
    // because Compose disposes CHILD effects before the parent's, so this runs
    // BEFORE the editor's `DisposableEffect(page.id)` flush — the emitted list
    // reaches `onStrokesChanged` → `handleStrokesChange` → `strokes = newStrokes`
    // and the editor's owning dispose-flush then persists the updated list.
    // `ink.isNotEmpty()` doubles as the duplicate guard: `onDragEnd` clears
    // `activePoints`, so a gesture that already committed its stroke leaves an
    // empty list and no second stroke is emitted here.
    val disposeToolState = androidx.compose.runtime.rememberUpdatedState(currentTool)
    val disposeColorState = androidx.compose.runtime.rememberUpdatedState(currentColor.toArgb())
    val disposeWidthState = androidx.compose.runtime.rememberUpdatedState(currentWidth)
    val disposeColorModeState = androidx.compose.runtime.rememberUpdatedState(currentColorMode)
    val disposeColorSeedState = androidx.compose.runtime.rememberUpdatedState(currentColorSeed)
    val disposeGradientToState = androidx.compose.runtime.rememberUpdatedState(currentGradientToColor.toArgb())
    val disposeLayerIdState = androidx.compose.runtime.rememberUpdatedState(activeLayerId)
    val disposeAdvBrushesState = androidx.compose.runtime.rememberUpdatedState(advancedBrushesEnabled)
    val disposeSymmetryModeState = androidx.compose.runtime.rememberUpdatedState(symmetryMode)
    val disposeContinuousState = androidx.compose.runtime.rememberUpdatedState(isContinuousMode)
    val disposePdfPageFilterState = androidx.compose.runtime.rememberUpdatedState(pdfPageFilter)
    DisposableEffect(Unit) {
        onDispose {
            val tool = disposeToolState.value
            val ink = activePoints.toList()
            if (ink.isNotEmpty() && activeTargetPage >= 0 &&
                tool.isFreehandTool && tool != StrokeTool.LASER
            ) {
                val startPoint = activeStart
                val endPoint = activeEnd
                val bakeMirrorTwin = com.authorss81.noteflow.services.SymmetryCommitPolicy.shouldBakeMirror(disposeSymmetryModeState.value, tool)
                val newStroke = Stroke(
                    id = java.util.UUID.randomUUID().toString(),
                    tool = tool,
                    colorInt = disposeColorState.value,
                    width = disposeWidthState.value,
                    points = ink,
                    start = startPoint,
                    end = endPoint ?: ink.lastOrNull(),
                    pdfPage = activeTargetPage,
                    timestampMs = null,
                    isAdvanced = disposeAdvBrushesState.value,
                    layerId = disposeLayerIdState.value ?: "layer_default",
                    colorMode = disposeColorModeState.value,
                    colorSeed = disposeColorSeedState.value,
                    gradientToColorInt = disposeGradientToState.value
                )
                val twin = if (bakeMirrorTwin) {
                    // screen width approximated by the page design width — the
                    // twin's axis is secondary; the PRIMARY ink is never lost.
                    val c = symmetryCenterFor(pageWidthPx, calculatePageYOffset(activeTargetPage))
                    com.authorss81.noteflow.services.SymmetryCommitPolicy.bakedTwin(
                        stroke = newStroke,
                        mode = disposeSymmetryModeState.value,
                        centerX = c.x,
                        centerY = c.y
                    )
                } else null
                // Avoid duplicating the stroke if onDragEnd already committed it
                // during this same teardown (activeStrokeList would contain it).
                if (activeStrokeList.none { it.id == newStroke.id }) {
                    activeStrokeList.add(newStroke)
                    if (twin != null && activeStrokeList.none { it.id == twin.id }) {
                        activeStrokeList.add(twin)
                    }
                    onStrokesChanged(
                        com.authorss81.noteflow.services.CanvasCommitListPolicy.emittedList(
                            currentAll = currentStrokesProvider(),
                            isContinuousMode = disposeContinuousState.value,
                            pageOf = { it.pdfPage },
                            pdfPageFilter = disposePdfPageFilterState.value,
                            scopedReplacement = activeStrokeList
                        )
                    )
                }
            }
        }
    }
    var showBrushStudio by remember { mutableStateOf(false) }
    val canvasDrawScope = remember { androidx.compose.ui.graphics.drawscope.CanvasDrawScope() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Phase 240 (Bug 2): the canvas needs NO window-origin capture. The
            // pointerInteropFilter bridge, the Compose drag handlers' `change.position`
            // and the phase-196 predictor all work in this box's node-local space.
            .background(
                if (divideIntoPages) {
                    if (isDarkTheme) Color(0xFF0F172A) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                } else {
                    parsedPaperColor
                }
            )
            // Passive pointer info tracker for Pressure, Tilt & Timestamp.
            // Uses the official raw-MotionEvent bridge (no reflection).
            .pointerInteropFilter { motionEvent ->
                lastPressure = motionEvent.pressure
                val tiltRad = motionEvent.getAxisValue(android.view.MotionEvent.AXIS_TILT)
                lastTilt = if (tiltRad != 0f) Math.toDegrees(tiltRad.toDouble()).toFloat() else 0f
                lastTimestampMs = motionEvent.eventTime
                // Phase 197: passive input-source capture (same bridge, still
                // consumes nothing) — finger vs stylus drives the stabilizer's
                // per-input smoothing adjustment at the next stroke start.
                when (motionEvent.getToolType(0)) {
                    android.view.MotionEvent.TOOL_TYPE_STYLUS,
                    android.view.MotionEvent.TOOL_TYPE_ERASER -> lastInputIsStylus = true
                    else -> lastInputIsStylus = false
                }
                // Phase 214: coalesced-history ingestion. Each ACTION_MOVE
                // pushes ALL getHistorical* samples FIRST, then the current
                // sample — FIFO order preserved for the drag handler that
                // drains before smoothing. historySize == 0 (non-batching
                // devices) offers exactly ONE sample per event: pre-214
                // behaviour, pinned by HistoryBatchTest. The bridge delivers
                // (and the predictor records) MotionEvents already offset into
                // this box's NODE-LOCAL space (Compose's toMotionEventScope
                // applies offsetLocation(-localToRoot)), so these samples are
                // node-local — NOT raw window coords. World mapping (pan/zoom)
                // happens at drain time via ingestPointerSample; no window-offset
                // subtraction is applied anywhere (Phase 240 Bug 2). This is only
                // valid because the pointerInteropFilter and the drag handlers sit
                // on the SAME canvas Box (same localToRoot) — if they ever move to
                // different nodes with different root offsets, this parity breaks.
                if (motionEvent.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                    val historySize = StrokeBatchPolicy.historicalCount(motionEvent.historySize)
                    for (h in 0 until historySize) {
                        strokeInputBatcher.offer(
                            RawInputSample(
                                x = motionEvent.getHistoricalX(0, h),
                                y = motionEvent.getHistoricalY(0, h),
                                pressure = motionEvent.getHistoricalPressure(0, h),
                                tiltRad = motionEvent.getHistoricalAxisValue(
                                    android.view.MotionEvent.AXIS_TILT, 0, h
                                ),
                                timestampMs = motionEvent.getHistoricalEventTime(h).toLong()
                            )
                        )
                    }
                    strokeInputBatcher.offer(
                        RawInputSample(
                            x = motionEvent.x,
                            y = motionEvent.y,
                            pressure = motionEvent.pressure,
                            tiltRad = tiltRad,
                            timestampMs = motionEvent.eventTime
                        )
                    )
                } else if (motionEvent.actionMasked == android.view.MotionEvent.ACTION_UP ||
                    motionEvent.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                ) {
                    // Gesture over: nothing may leak into the next stroke.
                    strokeInputBatcher.clear()
                }
                // Phase 196: record the REAL event with the OS motion predictor
                // before anything else reacts to it (documented usage). This
                // stays strictly passive — it consumes nothing, and the frame
                // loop above does all predicting; pressure/tilt/timestamp
                // bridging for pointerInteropFilter consumers is unchanged.
                predictionPointerCount.set(motionEvent.pointerCount)
                motionPredictor?.record(motionEvent)
                false
            }
            // Phase 155: two-finger undo/redo gestures (pure-JVM classifier).
            // MUST sit BEFORE the pinch-zoom handler so it observes every frame the
            // zoom handler later consumes; it consumes nothing itself, so pinch-zoom
            // and single-finger drawing are byte-for-byte unaffected. The classifier
            // enforces two layers of pinch separation: the separation-ratio band
            // (fingers spread/closing = pinch, never undo/redo) AND the horizontal
            // centroid rule (a vertical pan is not a swipe). Wired to the existing
            // undo/redo stack via EditorScreen callbacks.
            .pointerInput(twoFingerGesturesEnabled) {
                if (!twoFingerGesturesEnabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val coords = if (pressed >= 2 && event.changes.size >= 2) {
                            // Fewer churn for the classifier: it only needs the
                            // FIRST TWO pressed pointers' positions per frame.
                            val c = event.changes.filter { it.pressed }.take(2)
                            if (c.size >= 2) {
                                floatArrayOf(
                                    c[0].position.x, c[0].position.y,
                                    c[1].position.x, c[1].position.y
                                )
                            } else null
                        } else null
                        when (gestureClassifier.onFrame(pressed, coords, event.changes.firstOrNull()?.uptimeMillis ?: 0L)) {
                            com.authorss81.noteflow.services.GestureRedoUndoClassifier.Action.UNDO -> onTwoFingerUndo()
                            com.authorss81.noteflow.services.GestureRedoUndoClassifier.Action.REDO -> onTwoFingerRedo()
                            else -> Unit
                        }
                    }
                }
            }
            // 1. Two-finger Zoom and Pan Gestures. A single finger still draws, exactly
            // as before — zoom/pan only ever apply while two pointers are down.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val newScale = (internalZoomScale * zoomChange).coerceIn(0.5f, 4.0f)
                                val actualZoomChange = newScale / internalZoomScale

                                val centroid = event.changes.fold(Offset.Zero) { acc, change ->
                                    acc + change.position
                                } / event.changes.size.toFloat()

                                val newPanOffset = centroid - (centroid - internalPanOffset) * actualZoomChange + panChange

                                updateZoomAndPan(newScale, newPanOffset)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            // Phase 124: non-consuming eraser-cursor tracker. While the ERASER
            // tool is active this mirrors the pointer into canvas (world) coords
            // for the cursor preview (round mask / stroke highlight). It never
            // consumes, so the two-finger zoom, tap and drag detectors above and
            // below are unaffected; the cursor is cleared as soon as no pointer
            // is pressed (a tap-to-erase still flashes the preview, the drag
            // keeps it live, and it disappears on lift).
            .pointerInput(currentTool) {
                if (currentTool != StrokeTool.ERASER) {
                    eraserCursorCanvas = null
                    return@pointerInput
                }
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.firstOrNull { it.pressed }?.position
                        eraserCursorCanvas = pressed?.let { screenPos ->
                            Offset(
                                x = (screenPos.x - internalPanOffset.x) / internalZoomScale,
                                y = (screenPos.y - internalPanOffset.y) / internalZoomScale
                            )
                        }
                    }
                }
            }
            .pointerInput(currentTool, pdfPageFilter, isContinuousMode, activeRawBitmapMap) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val canvasOffset = Offset(
                            x = (offset.x - internalPanOffset.x) / internalZoomScale,
                            y = (offset.y - internalPanOffset.y) / internalZoomScale
                        )
                        stickyNoteOffset = canvasOffset
                        stickyNoteText = ""
                        showStickyNoteDialog = true
                    },
                    onTap = { offset ->
                        onCanvasTap()
                        val canvasOffset = Offset(
                            x = (offset.x - internalPanOffset.x) / internalZoomScale,
                            y = (offset.y - internalPanOffset.y) / internalZoomScale
                        )
                        val targetPage = getPageFromCanvasY(canvasOffset.y)

                        val tappedStroke = activeStrokeList.firstOrNull { stroke ->
                            stroke.timestampMs != null && strokeContainsPoint(stroke, canvasOffset)
                        }
                        if (tappedStroke?.timestampMs != null) {
                            onVoiceSeekTo(tappedStroke.timestampMs)
                        }

                        if (currentTool == StrokeTool.TEXT) {
                            if (!isLayerLocked) {
                                textInputOffset = canvasOffset
                                activeTargetPage = targetPage
                                textValue = ""
                                showTextInputDialog = true
                            }
                        } else if (currentTool == StrokeTool.EYEDROPPER) {
                            val sampled = sampleColorAt(canvasOffset, targetPage)
                            onColorSampled(sampled)
                        } else if (currentTool == StrokeTool.STICKER) {
                            // Phase 13: sticker tool — a tap places the selected
                            // sticker at the tapped point on the active page.
                            if (selectedStickerId != null) {
                                onPlaceSticker(canvasOffset, targetPage)
                            }
                        } else if (currentTool == StrokeTool.SELECT) {
                            // Phase 215: a quick tap (no drag slop) with the SELECT
                            // tool clears the selection — the same affordance as
                            // Escape, and never a degenerate empty marquee.
                            currentOnSelectionChanged(null)
                        } else if (currentTool == StrokeTool.FILL) {
                            // Phase 221: flood fill — tap fills the contiguous region
                            // at the tap point on the active layer. Samples from the
                            // PDF/image background when available; otherwise renders
                            // active-layer strokes to a temp bitmap for sampling.
                            if (!isLayerLocked) {
                                var sourceBmp: android.graphics.Bitmap? = activeRawBitmapMap[targetPage]
                                var needsRecycle = false
                                if (sourceBmp == null || sourceBmp.isRecycled) {
                                    val lw = pageWidthPx.toInt().coerceAtLeast(1)
                                    val lh = pageHeightPx.toInt().coerceAtLeast(1)
                                    val layerBmp = android.graphics.Bitmap.createBitmap(lw, lh, android.graphics.Bitmap.Config.ARGB_8888)
                                    val cvs = android.graphics.Canvas(layerBmp)
                                    val layerStrokes = activeStrokeList.filter {
                                        it.pdfPage == targetPage &&
                                        (activeLayerId == null || it.layerId == activeLayerId)
                                    }
                                    for (s in layerStrokes) {
                                        drawSingleStrokeToCanvas(cvs, s, null)
                                    }
                                    sourceBmp = layerBmp
                                    needsRecycle = true
                                }
                                if (sourceBmp != null && !sourceBmp.isRecycled) {
                                    try {
                                        val pageTopY = calculatePageYOffset(targetPage)
                                        val px = com.authorss81.noteflow.services.EyedropperSamplingMath.canvasToPagePixel(
                                            canvasX = canvasOffset.x,
                                            canvasY = canvasOffset.y,
                                            pageTopY = pageTopY,
                                            pageWidthPx = pageWidthPx,
                                            pageHeightPx = pageHeightPx,
                                            bitmapWidth = sourceBmp.width,
                                            bitmapHeight = sourceBmp.height
                                        )
                                        if (px != null) {
                                            val fillColor = currentColor.toArgb()
                                            val filledPoints = com.authorss81.noteflow.services.FloodFillEngine.floodFillBitmap(
                                                source = sourceBmp,
                                                seedX = px.first,
                                                seedY = px.second,
                                                fillColorArgb = fillColor,
                                                tolerancePercent = com.authorss81.noteflow.services.FloodFillEngine.DEFAULT_TOLERANCE_PERCENT
                                            )
                                            if (filledPoints.isNotEmpty()) {
                                                val minX = filledPoints.minOf { it.x } - 0.5f
                                                val maxX = filledPoints.maxOf { it.x } - 0.5f
                                                val minY = filledPoints.minOf { it.y } - 0.5f
                                                val maxY = filledPoints.maxOf { it.y } - 0.5f
                                                val fillStroke = Stroke(
                                                    id = java.util.UUID.randomUUID().toString(),
                                                    tool = StrokeTool.FILL,
                                                    colorInt = fillColor,
                                                    width = 1f,
                                                    points = filledPoints,
                                                    start = PointF(minX, minY),
                                                    end = PointF(maxX, maxY),
                                                    pdfPage = targetPage,
                                                    layerId = activeLayerId ?: "layer_default",
                                                    colorMode = currentColorMode,
                                                    colorSeed = currentColorSeed,
                                                    gradientToColorInt = currentGradientToColor.toArgb()
                                                )
                                                activeStrokeList.add(fillStroke)
                                                onStrokesChanged(
                                                    com.authorss81.noteflow.services.CanvasCommitListPolicy.emittedList(
                                                        currentAll = currentStrokesProvider(),
                                                        isContinuousMode = isContinuousMode,
                                                        pageOf = { it.pdfPage },
                                                        pdfPageFilter = pdfPageFilter,
                                                        scopedReplacement = activeStrokeList
                                                    )
                                                )
                                            }
                                        }
                                    } finally {
                                        if (needsRecycle) sourceBmp?.recycle()
                                    }
                                }
                            }
                        } else if (currentTool == StrokeTool.GRADIENT) {
                            // Phase 221: gradient fill is drag-only; tap is a no-op.
                        }
                    }
                )
            }

            // Phase 155: QUICK-COLOR RING — long-press the canvas to pop the
            // radial mini-palette seeded from the active DesignerPalette. This
            // block sits BETWEEN the tap handler and the drawing drag handler so
            // it can claim a long-press (with local consumption) BEFORE the
            // stroke drag path ever sees slop; a quick tap or quick drag falls
            // through untouched and draws exactly as before. Only freehand tools
            // can hold a color, so TEXT/SELECT/PAN/EYEDROPPER/sticker/shape tools
            // never trigger it. Reduce-motion is respected: the ring opens and
            // closes instantly (no animation).
            .pointerInput(quickColorRingEnabled, currentTool, quickColorSwatches, quickColorRingLongPressMillis) {
                if (!quickColorRingEnabled || !currentTool.isFreehandTool || quickColorSwatches.isEmpty()) {
                    return@pointerInput
                }
                val layout = com.authorss81.noteflow.services.QuickColorRingMath.ringLayout(
                    swatchCount = quickColorSwatches.size,
                    centerX = 0f,
                    centerY = 0f
                )
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Long-press = the pointer stays DOWN beyond the long-press window.
                    // NOTE: use the scope's OWN withTimeoutOrNull member (restricted
                    // suspending scope — kotlinx.coroutines.withTimeoutOrNull is NOT
                    // callable inside awaitEachGesture).
                    //
                    // Phase 245 ("weird shape when I draw", donut/dot on canvas): a
                    // long-press must YIELD to a moving pointer. A user who rests the
                    // finger/stylus still for >= longPressTimeout while AIMING the
                    // first mark (or drawing slow, deliberate ink) used to let the ring
                    // pop open over the canvas and consume the press — the intended
                    // stroke never deposited ink and the user saw the ring donut (and
                    // its center dot) instead of their stroke. The wait now aborts the
                    // moment the primary pointer moves beyond the touch slop, so a
                    // stroke that crosses the drag slop is never pre-empted: the ring
                    // opens ONLY for a genuinely STILL hold of the full timeout, and a
                    // yielding stroke records its first point at the exact down
                    // position (no lost start, no stray dot at a ring anchor).
                    val timedOut = withTimeoutOrNull(quickColorRingLongPressMillis) {
                        waitForUpOrSlopMove(down, viewConfiguration.touchSlop)
                    }
                    if (timedOut != null) {
                        // Released (or cancelled) before the timeout, OR the pointer
                        // moved beyond the touch slop (a stroke is starting) — either
                        // way this is not a long-press; the stroke/tap path handles it.
                        return@awaitEachGesture
                    }
                    // Long-press confirmed: take exclusive ownership of this finger.
                    down.consume()
                    openQuickColorRing(down.position)
                    // Drag to a swatch; release applies. Selection tracks the first
                    // (long-pressed) pointer.
                    val swatchCenters = layout.map { (x, y) ->
                        ((x + down.position.x) to (y + down.position.y))
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        val stillPressed = change?.pressed == true
                        if (!stillPressed) break
                        quickColorRingSelection =
                            com.authorss81.noteflow.services.QuickColorRingMath.hitIndex(
                                pointerX = change.position.x,
                                pointerY = change.position.y,
                                centerX = down.position.x,
                                centerY = down.position.y,
                                swatchCenters = swatchCenters,
                                touchSlopPx = 12f
                            )
                        change.consume()
                    }
                    val chosen = when {
                        quickColorRingSelection in quickColorSwatches.indices -> quickColorSwatches[quickColorRingSelection]
                        quickColorRingSelection == com.authorss81.noteflow.services.QuickColorRingMath.CENTER_SLOT -> null
                        else -> null
                    }
                    closeQuickColorRing()
                    if (chosen != null) onQuickColorPicked(chosen)
                }
            }

            // 3. Drawing / Eyedropper / Single-Finger Pan Gestures.
            // Phase 123: `activeLayerId` + `layers` are keys too, otherwise a
            // layer switch (unlocked -> unlocked) never restarted this block and
            // the stroke-commit closure below kept capturing the PREVIOUS layer
            // until some other key (tool/colour/width) forced a restart — i.e.
            // the "new layer only takes effect after switching pens" bug.
            // Phase 197: stabilizerStrengthPercent / activeBrushPresetId /
            // importedBrushPresets join the keys so the drag handler always
            // captures the CURRENT smoothing inputs (same pattern as
            // stabilizerEnabled).
            .pointerInput(currentTool, currentColor, currentWidth, pdfPageFilter, isContinuousMode, activeRawBitmapMap, isLayerLocked, symmetryMode, stabilizerEnabled, eraserMode, activeLayerId, layers, stabilizerStrengthPercent, activeBrushPresetId, importedBrushPresets, rulerEnabled) {
                // Phase 249 (Bug 4): per-gesture eraser state — the spatial
                // bucket (rebuilt lazily at drag start, re-tiled incrementally
                // as strokes are carved) and the sample-window pointer (only
                // samples accumulated SINCE the last applyEraser pass are
                // processed). Also the previous-ACCEPTED-raw-sample tracker for
                // the wet throttle (Bug 1). Plain block vars: only the gesture
                // pipeline reads/writes them, so snapshot state (which would
                // recompose per stamp) is deliberately avoided.
                var eraseHitBucket: com.authorss81.noteflow.services.EraseHitBucketPolicy? = null
                var lastProcessedEraseSampleIndex = 0
                var lastRawWetX: Float? = null
                var lastRawWetY: Float? = null
                var lastRawWetTimeMs: Long? = null
                // Phase 203: plain per-stroke hit-testing covers both symmetry
                // copies — a stroke drawn while a mode was active persisted TWO
                // independent rows (original + baked twin, see
                // SymmetryCommitPolicy), so erasing either copy deletes exactly
                // that row and LEAVES the other (the old view-time mirror hit-test
                // special-case is gone with it).
                val erasesStroke: (Stroke, Offset) -> Boolean = { stroke, offset ->
                    strokeContainsPoint(stroke, offset)
                }
                // Phase 19: shared eraser handler. STROKE mode keeps the classic
                // remove-whole-stroke behaviour. PARTIAL mode segments every hit
                // polyline (freehand tools with >= 2 points) into surviving runs
                // using the full accumulated erase path; non-polyline strokes
                // (text, shapes) fall back to whole-stroke removal — an honest
                // gate, matching the classic eraser, not a fake "partial".
                // Phase 124: each erase sample carries the touch pressure captured
                // at that instant, so PARTIAL stamps a pressure-aware round mask
                // (heavier press = wider circle), and the sample radius drives the
                // split geometry via StrokeSegmenter.
                // Phase 249 (Bug 4): applyEraser is no longer O(strokes × points ×
                // samples) per drag sample. Each pass processes ONLY the samples
                // accumulated since the last pass (capped at
                // MAX_ERASE_SAMPLES_PER_APPLY — the coalesced-history burst size;
                // anything older was already carved into the surviving strokes'
                // geometry / eraseMasks), and iterates ONLY strokes whose world
                // bounding box intersects the eraser cursor circle (spatial
                // bucket above). The single O(strokes) full-list pass happens
                // only when a stroke actually changed, to preserve z-order.
                fun applyEraser(canvasOffset: Offset) {
                    val partial = eraserMode == com.authorss81.noteflow.services.EraserMode.PARTIAL
                    val startIdx = lastProcessedEraseSampleIndex.coerceAtMost(eraseSamples.size)
                    val samples = eraseSamples
                        .subList(startIdx, eraseSamples.size)
                        .takeLast(com.authorss81.noteflow.services.EraseHitBucketPolicy.MAX_ERASE_SAMPLES_PER_APPLY)
                        .map {
                            com.authorss81.noteflow.services.StrokeSegmenter.ErasePoint(
                                x = it.pos.x,
                                y = it.pos.y,
                                radius = com.authorss81.noteflow.services.EraserGeometryPolicy.stampRadius(currentWidth, it.pressure)
                            )
                        }
                    lastProcessedEraseSampleIndex = eraseSamples.size
                    if (samples.isEmpty()) return

                    // Phase 249 (Bug 4): limit the scan to strokes whose world
                    // bounding box touches the eraser circle. Built lazily at the
                    // first sample of the drag (seeded with the full current list)
                    // so a long drag never pays a full-list pass per sample.
                    val bucket = eraseHitBucket
                        ?: com.authorss81.noteflow.services.EraseHitBucketPolicy.build(
                            activeStrokeList.toList(),
                            maxStampRadiusPx = com.authorss81.noteflow.services.EraserGeometryPolicy.stampRadius(currentWidth, 1f)
                        ).also { eraseHitBucket = it }
                    val candidates = bucket.candidatesWithinCircle(
                        cx = canvasOffset.x,
                        cy = canvasOffset.y,
                        radiusFor = { stroke ->
                            val stamp = com.authorss81.noteflow.services.EraserGeometryPolicy.stampRadius(currentWidth, 1f)
                            max(
                                com.authorss81.noteflow.services.EraserGeometryPolicy.coverageRadius(stamp, stroke.width),
                                com.authorss81.noteflow.services.EraserGeometryPolicy.legacyRadius(
                                    stroke.width,
                                    com.authorss81.noteflow.services.StrokeSegmenter.DEFAULT_EXTRA_RADIUS
                                )
                            )
                        }
                    )
                    val removed = mutableListOf<Stroke>()
                    val added = mutableListOf<Stroke>()
                    val replacedBy = java.util.HashMap<String, List<Stroke>>()
                    var changed = false
                    for (stroke in candidates) {
                        if (erasesStroke(stroke, canvasOffset)) {
                            changed = true
                            removed.add(stroke)
                            val canPartial = partial &&
                                stroke.tool.isFreehandTool &&
                                stroke.points.size > 1 &&
                                stroke.tool != StrokeTool.LASER
                            if (canPartial) {
                                val result = com.authorss81.noteflow.services.StrokeSegmenter.segment(
                                    stroke = stroke,
                                    eraseSamples = samples,
                                    extraRadius = com.authorss81.noteflow.services.StrokeSegmenter.DEFAULT_EXTRA_RADIUS
                                )
                                replacedBy[stroke.id] = result.surviving
                                added.addAll(result.surviving)
                            } else {
                                // Whole-stroke removal (classic eraser behaviour);
                                // non-polyline strokes (text, shapes) also fall back
                                // to this — an honest gate, matching the classic
                                // eraser, not a fake "partial".
                                replacedBy[stroke.id] = emptyList()
                            }
                        }
                    }
                    if (changed) {
                        // One O(strokes) pass to substitute the carved strokes in
                        // place (z-order preserved); everything else was bucketed.
                        val newList = mutableListOf<Stroke>()
                        for (stroke in activeStrokeList) {
                            val replacement = replacedBy[stroke.id]
                            if (replacement != null) newList.addAll(replacement) else newList.add(stroke)
                        }
                        activeStrokeList.clear()
                        activeStrokeList.addAll(newList)
                        // Phase 249: re-tile only the strokes that moved so the
                        // bucket stays in sync without a full rebuild.
                        bucket.replaceStrokes(removed, added)
                        // Phase 205: derive "other pages" from CURRENT state at
                        // apply time — the captured `strokes` parameter here is a
                        // frozen pointerInput snapshot that resurrected erased
                        // strokes when re-emitted wholesale.
                        onStrokesChanged(
                            com.authorss81.noteflow.services.CanvasCommitListPolicy.emittedList(
                                currentAll = currentStrokesProvider(),
                                isContinuousMode = isContinuousMode,
                                pageOf = { it.pdfPage },
                                pdfPageFilter = pdfPageFilter,
                                scopedReplacement = newList
                            )
                        )
                    }
                }
                // Phase 13: the STICKER tool places stickers via tap only; it is
                // excluded (like TEXT) so a stray drag can never create a stroke.
                if (currentTool != StrokeTool.TEXT && currentTool != StrokeTool.STICKER) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (isLayerLocked && currentTool != StrokeTool.SELECT && currentTool != StrokeTool.PAN && currentTool != StrokeTool.EYEDROPPER) {
                                return@detectDragGestures
                            }
                            onDrawingStart()
                            // Phase 18: fresh per-stroke texture seed (replaces the old page-fixed seed).
                            currentStrokeSeed = ((Math.random() * 1000f).toFloat())
                            val canvasOffset = Offset(
                                x = (offset.x - internalPanOffset.x) / internalZoomScale,
                                y = (offset.y - internalPanOffset.y) / internalZoomScale
                            )
                            if (isHittingCard(canvasOffset)) {
                                // Phase 249 (Bug 3): reconcile the predicted tail
                                // BEFORE the card drag claims this gesture — a tail
                                // left over from a prior freehand stroke would
                                // otherwise render as a ghost segment ahead of the
                                // next stroke's first real sample.
                                dropPredictedTail()
                                isDraggingCard = true
                                return@detectDragGestures
                            }
                            isDraggingCard = false

                            if (currentTool == StrokeTool.SELECT) {
                                // Phase 216: when there's an existing selection and
                                // the touch lands inside the selection bounds, start
                                // a TRANSLATE gesture instead of a new lasso. Outside
                                // the bounds, start a fresh lasso (clears old selection
                                // on release via the tap/empty-lasso path).
                                val sel = strokeSelection
                                if (sel.ids.isNotEmpty() && !lassoActive) {
                                    val padded = sel.bounds.inflate(
                                        com.authorss81.noteflow.services.StrokeHitPolicy.SELECTION_BOUNDS_PADDING_PX
                                    )
                                    if (canvasOffset.x >= padded.left && canvasOffset.x <= padded.right &&
                                        canvasOffset.y >= padded.top && canvasOffset.y <= padded.bottom
                                    ) {
                                        isTranslatingSelection = true
                                        selectionTranslateAccX = 0f
                                        selectionTranslateAccY = 0f
                                        return@detectDragGestures
                                    }
                                }
                                // Phase 215: SELECT is now a REAL selection tool.
                                // One finger starts a lasso/marquee path in world
                                // coords (two-finger pan/zoom above still works);
                                // the committed selection is computed once at drag
                                // end via LassoPolicy + StrokeHitPolicy.
                                isTranslatingSelection = false
                                lassoActive = true
                                lassoPathPoints.clear()
                                lassoPathPoints.add(PointF(canvasOffset.x, canvasOffset.y))
                                return@detectDragGestures
                            }
                            if (currentTool == StrokeTool.PAN) {
                                // PAN remains the dedicated single-finger pan/hand tool.
                                return@detectDragGestures
                            }
                            if (currentTool == StrokeTool.GRADIENT) {
                                // Phase 221: gradient drag — record start point.
                                gradientDragStart = canvasOffset
                                gradientDragCurrent = canvasOffset
                                activeTargetPage = getPageFromCanvasY(canvasOffset.y)
                                return@detectDragGestures
                            }
                            val targetPage = getPageFromCanvasY(canvasOffset.y)
                            val targetPageYStart = calculatePageYOffset(targetPage)
                            val targetPageYEnd = targetPageYStart + pageHeightPx

                            val isStartOutsidePage = canvasOffset.x < 0f || canvasOffset.x > pageWidthPx ||
                                    canvasOffset.y < targetPageYStart || canvasOffset.y > targetPageYEnd

                            if (isStartOutsidePage && currentTool != StrokeTool.EYEDROPPER && currentTool != StrokeTool.ERASER) {
                                isPanningBlackSpace = true
                                activeStart = null
                                activeEnd = null
                                activePoints.clear()
                                predictedTailTracker.clear()
                                return@detectDragGestures
                            }
                            isPanningBlackSpace = false

                            activeTargetPage = targetPage
                            // Phase 07: fresh smoothing window + remapped pressure per stroke.
                            // Reset unconditionally so stale smoothing state can never leak into a
                            // new stroke if the toggle changed mid-stroke.
                            stabilizerFilter.reset()
                            // Phase 214: no coalesced sample from a PREVIOUS gesture may leak into
                            // this one; the monotonic gate restarts with it.
                            strokeInputBatcher.clear()
                            lastIngestedInputTimestampMs = null
                            // Phase 249 (Bug 1): a fresh stroke must not inherit the
                            // previous stroke's ACCEPTED wet raw sample — a stale
                            // reference would make the first few samples look like a
                            // huge jump and throttle real ink at stroke start.
                            lastRawWetX = null
                            lastRawWetY = null
                            lastRawWetTimeMs = null
                            // Phase 214: model selection applies here (stroke start), reading the
                            // rememberUpdatedState holder so a settings change never needs to
                            // restart pointerInput. Unknown keys fail safe to EWMA inside
                            // selectModel; re-selecting the current key is a no-op.
                            stabilizerFilter.selectModel(stabilizerModelKeyState.value)
                            // Phase 197: re-tune the EWMA window for THIS stroke from
                            // the active brush preset's smoothing, the user strength
                            // slider, and the pointer input source (finger gets extra
                            // smoothing; stylus is the design baseline). With no preset,
                            // slider at 100% and a stylus this resolves to the legacy
                            // window 8 — pre-197 parity. Runs BEFORE the first point so
                            // the very first filtered sample already uses the new tuning.
                            if (stabilizerEnabled) {
                                val activePreset = activeBrushPresetId?.let { id ->
                                    com.authorss81.noteflow.services.BrushPresetPack.byId(id)
                                        ?: importedBrushPresets.firstOrNull { it.id == id }
                                }
                                stabilizerFilter.retune(
                                    windowSize = com.authorss81.noteflow.services.StrokeSmoothingPolicy.effectiveWindowSize(
                                        presetSmoothing = activePreset?.smoothing,
                                        sliderPercent = stabilizerStrengthPercent,
                                        isStylus = lastInputIsStylus
                                    ),
                                    // Phase 214: lag compensation ("tension") dial, sanitized in
                                    // SettingsManager; 15% reproduces the legacy PREDICTION constant.
                                    prediction = StrokeSmoothingPolicy.predictionFromPercent(
                                        stabilizerPredictionPercentState.value
                                    )
                                )
                            }
                            val startPressure = PressureCurveHelper.remapPressure(lastPressure, pressureCurve)
                            val startPoint = PointF(
                                x = canvasOffset.x.coerceIn(0f, pageWidthPx),
                                y = canvasOffset.y.coerceIn(targetPageYStart, targetPageYEnd),
                                pressure = startPressure,
                                tilt = lastTilt,
                                timestampMs = lastTimestampMs
                            )

                            if (currentTool == StrokeTool.EYEDROPPER) {
                                eyedropperPosition = offset
                                sampledColorPreview = sampleColorAt(canvasOffset, targetPage)
                            } else if (currentTool == StrokeTool.ERASER) {
                                eraseSamples.clear()
                                eraseSamples.add(EraseSample(canvasOffset, lastPressure))
                                // Phase 249 (Bug 4): fresh sample window and spatial
                                // bucket per gesture — the strokes may have changed
                                // since the previous eraser drag, and only NEW
                                // samples must feed the next applyEraser pass.
                                lastProcessedEraseSampleIndex = 0
                                eraseHitBucket = null
                                applyEraser(canvasOffset)
                            } else {
                                // Phase 206: arm the wet-engine frame pump ONLY while
                                // ink is actually flowing (idle editors cost zero wakes).
                                wetFramePump.start()
                                activeStart = startPoint
                                activeEnd = startPoint
                                activePoints.clear()
                                predictedTailTracker.clear()
                                activePoints.add(startPoint)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            // Phase 196: reconcile — the real sample supersedes
                            // any predicted preview tail the frame loop drew.
                            // Runs BEFORE every early-return (review-fix: was
                            // previously after the isDraggingCard check) so a
                            // stale tail can never outlive the real event that
                            // replaces it.
                            dropPredictedTail()
                            if (isDraggingCard) return@detectDragGestures
                            change.consume()

                            if (currentTool == StrokeTool.SELECT) {
                                if (isTranslatingSelection) {
                                    // Phase 216: accumulate translate delta in world coords.
                                    selectionTranslateAccX += dragAmount.x / internalZoomScale
                                    selectionTranslateAccY += dragAmount.y / internalZoomScale
                                    return@detectDragGestures
                                }
                                // Phase 215: extend the lasso path (world coords).
                                // Only the selection-overlay node reads this list,
                                // so per-sample appends never invalidate the main
                                // canvas pass.
                                lassoPathPoints.add(
                                    PointF(
                                        x = (change.position.x - internalPanOffset.x) / internalZoomScale,
                                        y = (change.position.y - internalPanOffset.y) / internalZoomScale
                                    )
                                )
                                return@detectDragGestures
                            }

                            if (currentTool == StrokeTool.GRADIENT) {
                                // Phase 221: update gradient drag endpoint.
                                gradientDragCurrent = Offset(
                                    x = (change.position.x - internalPanOffset.x) / internalZoomScale,
                                    y = (change.position.y - internalPanOffset.y) / internalZoomScale
                                )
                                return@detectDragGestures
                            }

                            if (isPanningBlackSpace || currentTool == StrokeTool.PAN) {
                                updateZoomAndPan(internalZoomScale, internalPanOffset + dragAmount)
                                return@detectDragGestures
                            }

                            // Phase 240 fix (Bug 2): ONE ingestion path for EVERY pointer sample,
                            // live or coalesced-historical. The coordinates are already
                            // CANVAS-BOX-LOCAL (both the passive `pointerInteropFilter`
                            // bridge and the live `change.position` fallback deliver
                            // node-local coords — Compose offsets the dispatch MotionEvent
                            // by the filter node's root offset). NO window-offset subtraction
                            // happens anywhere in this path — the phase-196 predicted tail
                            // (which records the same node-local events) passes the neutral
                            // frame as well. Only the pan/zoom transform below remains.
                            // Returns TRUE iff the sample was actually ingested; the
                            // page-bounds rejection returns FALSE so the monotonic
                            // batch-gate stamp never advances past a REJECTED sample
                            // (review-fix: "last ACCEPTED" must mean accepted).
                            fun ingestPointerSample(
                                boxLocalX: Float,
                                boxLocalY: Float,
                                rawPressure: Float,
                                tiltDegrees: Float,
                                sampleTimestampMs: Long?
                            ): Boolean {
                                val rawCanvasX = (boxLocalX - internalPanOffset.x) / internalZoomScale
                                val rawCanvasY = (boxLocalY - internalPanOffset.y) / internalZoomScale

                                val targetPageYStart = calculatePageYOffset(activeTargetPage)
                                val targetPageYEnd = targetPageYStart + pageHeightPx

                                // Prevent drawing across page boundaries:
                                // If the pointer coordinates move outside the active target page, do not add points!
                                val isOutsidePage = rawCanvasX < 0f || rawCanvasX > pageWidthPx ||
                                        rawCanvasY < targetPageYStart || rawCanvasY > targetPageYEnd

                                if (isOutsidePage && currentTool != StrokeTool.EYEDROPPER && currentTool != StrokeTool.ERASER) {
                                    return false
                                }

                                val currentPressure = PressureCurveHelper.remapPressure(rawPressure, pressureCurve)
                                val currentPoint = PointF(
                                    x = rawCanvasX.coerceIn(0f, pageWidthPx),
                                    y = rawCanvasY.coerceIn(targetPageYStart, targetPageYEnd),
                                    pressure = currentPressure,
                                    tilt = tiltDegrees,
                                    timestampMs = sampleTimestampMs
                                )

                                if (currentTool == StrokeTool.EYEDROPPER) {
                                    val canvasPosition = Offset(rawCanvasX, rawCanvasY)
                                    eyedropperPosition = Offset(boxLocalX, boxLocalY)
                                    sampledColorPreview = sampleColorAt(canvasPosition, activeTargetPage)
                                } else if (currentTool == StrokeTool.ERASER) {
                                    val canvasPosition = Offset(rawCanvasX, rawCanvasY)
                                    eraseSamples.add(EraseSample(canvasPosition, rawPressure))
                                    applyEraser(canvasPosition)
                                } else if (currentTool.isFreehandTool) {
                                    // Phase 07: stabilizer smooths touch jitter while staying
                                    // responsive; disabled => identical behaviour.
                                    // Phase 214: pressure (+tilt) ride through the SAME adaptive
                                    // low-pass as x/y, and the curve remap happens AFTER smoothing
                                    // so gamma curves cannot amplify un-smoothed digitizer jitter.
                                    // Velocity vs the previous ACCEPTED point adapts alpha:
                                    // slow writing damps harder, fast strokes stay responsive;
                                    // no timing pair yet => static base alpha (pre-214 parity).
                                    var drawPoint = currentPoint
                                    if (stabilizerEnabled) {
                                        val prevAccepted = activePoints.lastOrNull()
                                        // Scalar overload: same math as the PointF pair version,
                                        // without the two throwaway allocations per sample.
                                        val velocity = if (
                                            prevAccepted != null &&
                                            prevAccepted.timestampMs != null &&
                                            sampleTimestampMs != null &&
                                            sampleTimestampMs > prevAccepted.timestampMs!!
                                        ) {
                                            BrushStrokeMath.segmentVelocity(
                                                prevAccepted.x,
                                                prevAccepted.y,
                                                prevAccepted.timestampMs,
                                                currentPoint.x,
                                                currentPoint.y,
                                                sampleTimestampMs
                                            )
                                        } else {
                                            null
                                        }
                                        val s = stabilizerFilter.next(
                                            x = currentPoint.x,
                                            y = currentPoint.y,
                                            pressure = rawPressure,
                                            tiltDeg = tiltDegrees,
                                            velocityPxPerMs = velocity,
                                            timestampMs = sampleTimestampMs
                                        )
                                        drawPoint = PointF(
                                            s.x,
                                            s.y,
                                            PressureCurveHelper.remapPressure(s.pressure ?: rawPressure, pressureCurve),
                                            s.tilt ?: tiltDegrees,
                                            sampleTimestampMs
                                        )
                                    }
                                    // Vector Stroke Smoothing & Touch jitter filtering —
                                    // the sample gate is WET-ONLY (phase-228): wet tools
                                    // throttle via WetThrottlePolicy.shouldProcess
                                    // (phase-249: ≥1.5px RAW digitizer movement OR ≥16ms
                                    // REAL MotionEvent uptime since the last accepted
                                    // sample) so translucent layers deposit full stamps,
                                    // then interpolate the gap; NON-wet tools
                                    // (pen/pencil/…) add EVERY live sample with no distance
                                    // gate, so nothing is dropped (the old ">1.5px" gate and
                                    // the 6px/16ms freehand throttle both dropped points and
                                    // were removed 2026-08-27 — see workspace/phase-228/REPORT.md).
                                    val isWet = BrushStrokeMath.isWetRenderedTool(currentTool)
                                    if (isWet) {
                                        // Phase 249 (Bug 1): the wet throttle runs on
                                        // the REAL sample timeline and the RAW
                                        // digitizer delta. Pre-249 the gate fabricated
                                        // `lastTime = now()-16L` / `curTime = now()`
                                        // wall-clock stamps unrelated to the MotionEvent
                                        // uptime clock, and the `dist >= 6f` floor
                                        // measured the STABILIZER-CURBED point — a fast
                                        // stroke's EWMA-attenuated delta fell under the
                                        // floor and dropped real ink ("dots far from
                                        // touch"). `sampleTimestampMs` is the exact
                                        // MotionEvent eventTime threaded through the
                                        // passive pointerInteropFilter bridge →
                                        // StrokeInputBatcher → drain; lastRawWet* is the
                                        // previous ACCEPTED RAW sample (pre-smoothing,
                                        // pre-clamping world-space digitizer position).
                                        // NEVER feed this gate the smoothed `drawPoint`.
                                        // Comment: `lastRawWetX/Y/TimeMs` are always set and
                                        // cleared as ONE unit (acceptance below + per-stroke
                                        // reset at drag start), so they can never be in a mixed
                                        // state — when the timestamp ref is null the position
                                        // refs are null too and `shouldProcess` fails open (a
                                        // fresh stroke's first sample is never throttled by an
                                        // absent reference). The old
                                        // `?: activePoints.lastOrNull()?.timestampMs` arm was
                                        // unreachable and implied a raw/smoothed mixed state.
                                        val curTime = sampleTimestampMs
                                        val lastTime = lastRawWetTimeMs
                                        if (
                                            !com.authorss81.noteflow.services.WetThrottlePolicy.shouldProcess(
                                                lastRawX = lastRawWetX,
                                                lastRawY = lastRawWetY,
                                                lastSampleTimeMs = lastTime,
                                                rawX = rawCanvasX,
                                                rawY = rawCanvasY,
                                                sampleTimeMs = curTime
                                            )
                                        ) {
                                            // Phase 255 (Bug dots): return FALSE so the
                                            // outer batcher does NOT advance
                                            // lastIngestedInputTimestampMs past a
                                            // rejected sample. Returning TRUE made the
                                            // gate skip every subsequent sample whose
                                            // timestamp was <= this one, dropping whole
                                            // segments of a fast wet stroke and leaving
                                            // only the first accepted point behind.
                                            return false
                                        }
                                        lastRawWetX = rawCanvasX
                                        lastRawWetY = rawCanvasY
                                        lastRawWetTimeMs = curTime
                                    }
                                    val last = activePoints.lastOrNull()
                                    if (isWet && last != null) {
                                            val interpolated = wetBrushEngine.interpolateSegment(
                                                prev = Offset(last.x, last.y),
                                                cur = Offset(drawPoint.x, drawPoint.y),
                                                radius = currentWidth * 1.5f
                                            )
                                            for (interp in interpolated) {
                                                val interpPt = PointF(
                                                    x = interp.x,
                                                    y = interp.y,
                                                    pressure = drawPoint.pressure,
                                                    tilt = drawPoint.tilt,
                                                    timestampMs = drawPoint.timestampMs
                                                )
                                                activePoints.add(interpPt)

                                                wetCanvasEngine.markPaintDeposited(currentTool)
                                            }
                                            activeEnd = activePoints.lastOrNull() ?: drawPoint
                                        } else {
                                            activePoints.add(drawPoint)
                                            activeEnd = drawPoint
                                        }
                                } else {
                                    activeEnd = currentPoint
                                }
                                return true
                            }

                            // Phase 214: consume coalesced history FIRST so every
                            // getHistorical* sample flows through the SAME pipeline
                            // (bounds gate → smooth → wet gate) as live ones. Freehand
                            // drawing ingests the WHOLE batch (2-3× temporal resolution);
                            // eraser/eyedropper/shape paths stay on the NEWEST sample only
                            // (applyEraser/sampleColorAt rebuild per call — cost containment,
                            // documented in workspace/phase-214/REPORT.md).
                            val drainedCount = strokeInputBatcher.drainInto(batchDrainScratch)
                            // Pen (non-wet freehand) bypasses batcher staleness — directly
                            // append the live position so historical isStale never drops
                            // the middle of the stroke (dots → continuous).
                            val isWetForBypass = com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(currentTool)
                            if (!isWetForBypass && currentTool.isFreehandTool) {
                                if (!StrokeBatchPolicy.isStale(lastTimestampMs ?: Long.MIN_VALUE, lastIngestedInputTimestampMs)) {
                                    val accepted = ingestPointerSample(change.position.x, change.position.y, lastPressure, lastTilt, lastTimestampMs)
                                    if (accepted && lastTimestampMs != null) lastIngestedInputTimestampMs = lastTimestampMs
                                }
                            } else if (drainedCount > 1 && currentTool.isFreehandTool) {
                                for (sample in batchDrainScratch) {
                                    if (StrokeBatchPolicy.isStale(sample.timestampMs, lastIngestedInputTimestampMs)) continue
                                    // Phase 240 fix (Bug 2): pointerInteropFilter already
                                    // delivers node-LOCAL coordinates (Compose offsets the
                                    // dispatch MotionEvent by the filter node's root offset —
                                    // see PointerInteropFilter.toMotionEventScope), so the old
                                    // window-offset SUBTRACTION here was a DOUBLE offset that
                                    // shifted registered dots far from the actual touch.
                                    // boxLocal == sample (the same space the live
                                    // `change.position` fallback below and the phase-196
                                    // predicted-tail path use — that path now passes the
                                    // neutral frame too, since the predictor extrapolates
                                    // in the same recorded node-local space).
                                    val accepted = ingestPointerSample(
                                        boxLocalX = sample.x,
                                        boxLocalY = sample.y,
                                        rawPressure = sample.pressure,
                                        tiltDegrees = if (sample.tiltRad != 0f) Math.toDegrees(sample.tiltRad.toDouble()).toFloat() else 0f,
                                        sampleTimestampMs = sample.timestampMs
                                    )
                                    if (accepted) lastIngestedInputTimestampMs = sample.timestampMs
                                }
                            } else if (drainedCount > 0) {
                                val newest = batchDrainScratch.last()
                                if (!StrokeBatchPolicy.isStale(newest.timestampMs, lastIngestedInputTimestampMs)) {
                                    val accepted = ingestPointerSample(
                                        boxLocalX = newest.x,
                                        boxLocalY = newest.y,
                                        rawPressure = newest.pressure,
                                        tiltDegrees = if (newest.tiltRad != 0f) Math.toDegrees(newest.tiltRad.toDouble()).toFloat() else 0f,
                                        sampleTimestampMs = newest.timestampMs
                                    )
                                    if (accepted) lastIngestedInputTimestampMs = newest.timestampMs
                                }
                            } else {
                                // Defensive fallback: an event that somehow bypassed the passive
                                // bridge (queue empty) still processes exactly ONE sample, using the
                                // pre-214 state values — behaviour identical to the old single-sample path.
                                if (!StrokeBatchPolicy.isStale(lastTimestampMs ?: Long.MIN_VALUE, lastIngestedInputTimestampMs)) {
                                    val accepted = ingestPointerSample(change.position.x, change.position.y, lastPressure, lastTilt, lastTimestampMs)
                                    if (accepted && lastTimestampMs != null) lastIngestedInputTimestampMs = lastTimestampMs
                                }
                            }
                        },
                        onDragEnd = {
                            // Phase 206: the stroke is over — disarm the frame pump on
                            // EVERY end path (idempotent; early returns included).
                            wetFramePump.stop()
                            // Phase 196: reconcile BEFORE every early-return
                            // (review-fix) so no predicted tail can outlive this
                            // gesture — and so COMMITTED geometry below contains
                            // only real samples.
                            dropPredictedTail()
                            if (isDraggingCard) {
                                isDraggingCard = false
                                return@detectDragGestures
                            }
                            if (currentTool == StrokeTool.SELECT) {
                                if (isTranslatingSelection) {
                                    // Phase 216: commit the accumulated translate delta.
                                    val dx = selectionTranslateAccX
                                    val dy = selectionTranslateAccY
                                    isTranslatingSelection = false
                                    selectionTranslateAccX = 0f
                                    selectionTranslateAccY = 0f
                                    if (dx != 0f || dy != 0f) {
                                        currentOnSelectionTranslate(dx, dy)
                                        if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)) {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                    return@detectDragGestures
                                }
                                // Phase 215: commit the lasso/marquee selection
                                // (tap-classified paths clear via the tap handler).
                                // A light tick confirms a non-empty capture.
                                val selection = finishLassoSelection(activeStrokeList.toList())
                                if (!selection.isEmpty &&
                                    com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)
                                ) {
                                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                }
                            } else if (currentTool == StrokeTool.EYEDROPPER) {
                                sampledColorPreview?.let { onColorSampled(it) }
                                eyedropperPosition = null
                                sampledColorPreview = null
                            } else if (currentTool == StrokeTool.GRADIENT) {
                                // Phase 221: commit gradient fill — drag vector defines the
                                // gradient direction; the fill covers the entire active page.
                                val start = gradientDragStart
                                val end = gradientDragCurrent
                                if (start != null && end != null) {
                                    val targetPage = activeTargetPage
                                    val pageTopY = calculatePageYOffset(targetPage)
                                    val gradientStroke = Stroke(
                                        id = java.util.UUID.randomUUID().toString(),
                                        tool = StrokeTool.GRADIENT,
                                        colorInt = currentColor.toArgb(),
                                        width = 1f,
                                        points = listOf(PointF(start.x, start.y), PointF(end.x, end.y)),
                                        start = PointF(0f, pageTopY),
                                        end = PointF(pageWidthPx, pageTopY + pageHeightPx),
                                        pdfPage = targetPage,
                                        layerId = activeLayerId ?: "layer_default",
                                        colorMode = com.authorss81.noteflow.data.model.StrokeColorMode.GRADIENT,
                                        colorSeed = currentColorSeed,
                                        gradientToColorInt = currentGradientToColor.toArgb()
                                    )
                                    activeStrokeList.add(gradientStroke)
                                    onStrokesChanged(
                                        com.authorss81.noteflow.services.CanvasCommitListPolicy.emittedList(
                                            currentAll = currentStrokesProvider(),
                                            isContinuousMode = isContinuousMode,
                                            pageOf = { it.pdfPage },
                                            pdfPageFilter = pdfPageFilter,
                                            scopedReplacement = activeStrokeList
                                        )
                                    )
                                    if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)) {
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                gradientDragStart = null
                                gradientDragCurrent = null
                            } else if (currentTool != StrokeTool.ERASER) {
                                if (activePoints.isNotEmpty() || (activeStart != null && activeEnd != null)) {
                                    val pointsToSimplify = activePoints.toList()
                                    val startPoint = activeStart
                                    val endPoint = activeEnd
                                    val targetPage = activeTargetPage
                                    val isVoiceRec = isRecordingVoice
                                    val elapsedMs = recordingElapsedMsProvider()
                                    val advBrushes = advancedBrushesEnabled
                                    val actLayerId = activeLayerId
                                    val tool = currentTool
                                    val colorInt = currentColor.toArgb()
                                    val width = currentWidth
                                    // Phase 27: capture the current color mode/seed/end so the
                                    // committed stroke carries them (render-time re-derivation).
                                    val commitColorMode = currentColorMode
                                    val commitColorSeed = currentColorSeed
                                    val commitGradientTo = currentGradientToColor.toArgb()
                                    // Phase 203: symmetry is a CAPTURE-TIME decision. Freeze the
                                    // mode AND the exact axis center this gesture's live preview
                                    // used (symmetryCenterResolver(size.width, pageTopY of the
                                    // active page)) so the baked twin lands precisely where the
                                    // user saw the mirrored ink while drawing — world space, same
                                    // coordinate space as the stroke points. Toggling symmetry
                                    // later never rewrites history; only strokes drawn while a
                                    // mode is active gain a twin.
                                    val bakeMirrorTwin = com.authorss81.noteflow.services.SymmetryCommitPolicy.shouldBakeMirror(symmetryMode, tool)
                                    val symmetryAxisCenter = if (bakeMirrorTwin) {
                                        val c = symmetryCenterFor(size.width.toFloat(), calculatePageYOffset(targetPage))
                                        c.x to c.y
                                    } else {
                                        null
                                    }
                                    val commitSymmetryMode = symmetryMode

                                    // Phase 205: the commit is SYNCHRONOUS on Main.
                                    // Pre-205 this block launched on Dispatchers.Default and
                                    // hopped back withContext(Main) inside rememberCoroutineScope,
                                    // which opened three races: (a) editor disposal before the
                                    // hop CANCELLED the coroutine and silently dropped the
                                    // finished stroke; (b) two fast strokes could compute out
                                    // of order and land out of order (z-order + undo churn);
                                    // (c) the emit rebuilt the payload from the `strokes`
                                    // captured at drag-end, resurrecting strokes erased in
                                    // between. RDP/snap are cheap geometry passes, so the
                                    // whole pipeline now runs inline before the gesture
                                    // handler returns: nothing is pending, ordering IS call
                                    // order, and "other pages" comes from
                                    // currentStrokesProvider() read AT APPLY TIME via
                                    // CanvasCommitListPolicy — never from capture time.
                                    val candidateStroke = Stroke(
                                        id = java.util.UUID.randomUUID().toString(),
                                        tool = tool,
                                        colorInt = colorInt,
                                        width = width,
                                        points = pointsToSimplify,
                                        start = startPoint,
                                        end = endPoint,
                                        pdfPage = targetPage,
                                        timestampMs = if (tool == StrokeTool.LASER) System.currentTimeMillis() else if (isVoiceRec) elapsedMs else null,
                                        isAdvanced = advBrushes,
                                        layerId = actLayerId ?: "layer_default",
                                        colorMode = commitColorMode,
                                        colorSeed = commitColorSeed,
                                        gradientToColorInt = commitGradientTo
                                    )
                                    val isWetOrFleeting = tool == StrokeTool.LASER || com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(tool)
                                    val stylePreservingTool = tool == StrokeTool.DOTTED || tool == StrokeTool.NEON ||
                                        tool == StrokeTool.CHARCOAL || tool == StrokeTool.OIL_PASTEL ||
                                        tool == StrokeTool.DRY_BRUSH || tool == StrokeTool.PALETTE_KNIFE
                                    // Phase 223: the RULER forces an exact start→end LINE
                                    // for ANY freehand drag (bypassing trySnapShape's
                                    // perpendicularDeviation/direct-distance gate), so the
                                    // committed geometry always matches the ruler guide the
                                    // live preview showed. It applies even when
                                    // shapeAutoSnapEnabled is OFF and takes precedence over
                                    // the optional auto-snap. Wet/fleeting and
                                    // style-preserving tools are excluded as in auto-snap.
                                    // Phase 223 review fix: the ruler also skips
                                    // degenerate (tap / hairline) drags via
                                    // rulerLineEligible so no zero-length LINE
                                    // stroke is ever committed.
                                    val rulerForcingLine = rulerEnabled && tool.isFreehandTool && !isWetOrFleeting && !stylePreservingTool &&
                                        com.authorss81.noteflow.services.ShapeRecognitionHelper.rulerLineEligible(candidateStroke)
                                    val snappedShape = if (rulerForcingLine) {
                                        com.authorss81.noteflow.services.ShapeRecognitionHelper.SnappedShape(
                                            com.authorss81.noteflow.services.ShapeRecognitionHelper.ShapeType.LINE,
                                            com.authorss81.noteflow.services.ShapeRecognitionHelper.forceLineSnap(candidateStroke)
                                        )
                                    } else if (shapeAutoSnapEnabled && tool.isFreehandTool && !isWetOrFleeting && !stylePreservingTool) {
                                        com.authorss81.noteflow.services.ShapeRecognitionHelper.trySnapShape(candidateStroke)
                                    } else {
                                        null
                                    }
                                    val newStroke = if (snappedShape != null) {
                                        snappedShape.snappedStroke
                                    } else if (pointsToSimplify.size > 2) {
                                        // Phase 201 (PERF 1.4): per-brush epsilon —
                                        // hairline nibs keep their fine inflections
                                        // (0.6-0.8 px), everything else stays at the
                                        // legacy 1.3 px. Runs ONLY here, on pointer-up;
                                        // the live preview never simplifies mid-stroke.
                                        val simplifiedPoints = com.authorss81.noteflow.utils.RamerDouglasPeucker.simplify(
                                            pointsToSimplify,
                                            epsilon = com.authorss81.noteflow.services.StrokeSimplifyPolicy.epsilonFor(tool, width)
                                        )
                                        // Phase 214: ONE Chaikin fairing pass on hairline ink
                                        // only (fine-tip brush, narrow width, hairline-band
                                        // epsilon, > 8 surviving points). Converts the residual
                                        // EWMA kinks into a visually fair curve; shape-snapped
                                        // strokes never reach this branch. The geometry cap is
                                        // re-enforced AFTER fairing (chaikinOnce itself refuses
                                        // to run when the doubled result would exceed it).
                                        val fairedPoints = if (
                                            com.authorss81.noteflow.services.StrokeFairingPolicy.shouldFair(
                                                survivingPointCount = simplifiedPoints.size,
                                                tool = tool,
                                                strokeWidthPx = width,
                                                appliedEpsilon = com.authorss81.noteflow.services.StrokeSimplifyPolicy.epsilonFor(tool, width)
                                            )
                                        ) {
                                            com.authorss81.noteflow.services.StrokeFairingPolicy.chaikinOnce(simplifiedPoints)
                                        } else {
                                            simplifiedPoints
                                        }
                                        candidateStroke.copy(
                                            points = com.authorss81.noteflow.services.StrokeGeometryPolicy.capLoadedPoints(fairedPoints)
                                        )
                                    } else {
                                        candidateStroke
                                    }
                                    // Phase 203: ORIGINAL + mirrored TWIN are added together
                                    // in ONE onStrokesChanged update, so undo removes both at
                                    // once and the autosave snapshot persists both rows.
                                    val commitBatch = if (bakeMirrorTwin && symmetryAxisCenter != null) {
                                        listOf(
                                            newStroke,
                                            com.authorss81.noteflow.services.SymmetryCommitPolicy.bakedTwin(
                                                stroke = newStroke,
                                                mode = commitSymmetryMode,
                                                centerX = symmetryAxisCenter.first,
                                                centerY = symmetryAxisCenter.second
                                            )
                                        )
                                    } else {
                                        listOf(newStroke)
                                    }
                                    activeStrokeList.addAll(commitBatch)
                                    onStrokesChanged(
                                        com.authorss81.noteflow.services.CanvasCommitListPolicy.emittedList(
                                            currentAll = currentStrokesProvider(),
                                            isContinuousMode = isContinuousMode,
                                            pageOf = { it.pdfPage },
                                            pdfPageFilter = pdfPageFilter,
                                            scopedReplacement = activeStrokeList
                                        )
                                    )
                                    // 36.0: stroke-commit tick + distinct shape-snap tick,
                                    // both gated by the haptics setting AND reduce-motion.
                                    val hapticGate = com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)
                                    if (hapticGate) {
                                        if (snappedShape != null) {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        } else {
                                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                }
                            }
                            activePoints.clear()
                            predictedTailTracker.clear()
                            // Phase 214: gesture over — no coalesced sample may leak into
                            // the next stroke; the monotonic gate restarts with it.
                            strokeInputBatcher.clear()
                            lastIngestedInputTimestampMs = null
                            activeStart = null
                            activeEnd = null
                            onDrawingEnd()
                        },
                        onDragCancel = {
                            wetFramePump.stop()
                            isDraggingCard = false
                            isTranslatingSelection = false
                            selectionTranslateAccX = 0f
                            selectionTranslateAccY = 0f
                            eyedropperPosition = null
                            sampledColorPreview = null
                            gradientDragStart = null
                            gradientDragCurrent = null
                            // Phase 215: an interrupted lasso leaves NO selection
                            // behind (the pre-cancel path is preserved untouched).
                            lassoActive = false
                            lassoPathPoints.clear()
                            activePoints.clear()
                            predictedTailTracker.clear()
                            // Phase 214: same boundary hygiene as onDragEnd.
                            strokeInputBatcher.clear()
                            lastIngestedInputTimestampMs = null
                            activeStart = null
                            activeEnd = null
                            onDrawingEnd()
                        }
                    )
                }
            }
    ) {
        val viewHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        // Phase 244 (Bug 1): the canvas box's real pixel bounds, captured here
        // (in the BoxWithConstraints promoting receiver) so nested regions like
        // the minimap HUD can anchor to the ACTUAL canvas area instead of the
        // device-wide LocalConfiguration.density/screen dims.
        val canvasBoxW = with(LocalDensity.current) { maxWidth.toPx() }
        val canvasBoxH = viewHeightPx

        // Phase-150 review fix 4: the derived page count carries a flag when the
        // note's OWN content (not deep panning) extends past the world ceiling —
        // that case fires the one-time non-alarming notice via
        // [onDynamicPageCountCapped] instead of silently dropping the tail ink.
        val calculatedPageCountState = remember(isPdf, pdfTotalPages, isContinuousMode, activeStrokeList.size, layoutPanOffset, layoutZoomScale, viewHeightPx) {
            if (isPdf) {
                kotlin.math.max(1, pdfTotalPages) to false
            } else if (isContinuousMode) {
                var maxStrokeY = 0f
                for (stroke in activeStrokeList) {
                    for (pt in stroke.points) {
                        if (pt.y > maxStrokeY) {
                            maxStrokeY = pt.y
                        }
                    }
                }
                val visibleBottomY = -layoutPanOffset.y / layoutZoomScale + (viewHeightPx / layoutZoomScale)
                // R2-b2b5-FEA-04 (phase-150): the DAO/restore guards cap strokes
                // by LENGTH only, never by coordinate VALUE — a crafted backup can
                // carry `{"y":1e9}` in a short stroke and previously derived
                // ~628k pages here. Clamp the end-of-stroke Y to the world ceiling
                // (CanvasPageBudgetPolicy) BEFORE the count math, then clamp the
                // derived count itself, so the render loop stays bounded.
                val pageStride = pageHeightPx + pageGapPx
                val maxY = kotlin.math.max(
                    com.authorss81.noteflow.services.CanvasPageBudgetPolicy.clampMaxStrokeY(maxStrokeY, pageStride),
                    visibleBottomY
                )
                // Content BEYOND the ceiling only when the stroke geometry itself
                // reaches past it — a huge visibleBottomY from panning must not
                // trip the notice.
                val ownContentBeyondCeiling = maxStrokeY.isFinite() &&
                    maxStrokeY > com.authorss81.noteflow.services.CanvasPageBudgetPolicy.maxStrokeYCeiling(pageStride)
                val calculatedPages = com.authorss81.noteflow.services.CanvasPageBudgetPolicy.calculatedPagesFor(maxY, pageStride)
                com.authorss81.noteflow.services.CanvasPageBudgetPolicy.clampCalculatedPages(calculatedPages) to ownContentBeyondCeiling
            } else {
                1 to false
            }
        }
        val calculatedPageCount = calculatedPageCountState.first
        val canvasContentBeyondCeiling = calculatedPageCountState.second
        // One-time per session per canvas (like the layer-cap notice): the very
        // first time the note's own strokes are found past the ceiling, tell the
        // user once instead of silently hiding the tail ink.
        var pageCountCappedNotified by remember { mutableStateOf(false) }
        SideEffect {
            if (dynamicPageCount != calculatedPageCount) {
                dynamicPageCount = calculatedPageCount
            }
            if (canvasContentBeyondCeiling && !pageCountCappedNotified) {
                pageCountCappedNotified = true
                onDynamicPageCountCapped?.invoke()
            }
        }

        LaunchedEffect(layoutPanOffset, layoutZoomScale, isContinuousMode, pdfTotalPages, viewHeightPx) {
            if (isContinuousMode && pdfTotalPages > 1) {
                val visibleTopCanvasY = -layoutPanOffset.y / layoutZoomScale
                val visibleBottomCanvasY = visibleTopCanvasY + (viewHeightPx / layoutZoomScale)
                val pageStride = pageHeightPx + pageGapPx

                val firstVisible = max(0, floor(visibleTopCanvasY / pageStride).toInt() - 1)
                val lastVisible = min(pdfTotalPages - 1, ceil(visibleBottomCanvasY / pageStride).toInt() + 1)

                val requiredWindow = (firstVisible..lastVisible).toSet()
                onVisiblePageWindowChanged(requiredWindow)
            } else {
                onVisiblePageWindowChanged(setOf(pdfPageFilter))
            }
        }

        if (showTextInputDialog && textInputOffset != null) {
            AlertDialog(
                onDismissRequest = { showTextInputDialog = false },
                title = { Text("Canvas Text & Style Options") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = textValue,
                            onValueChange = { textValue = it },
                            label = { Text("Enter text content") },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("Font Family & Style", style = MaterialTheme.typography.labelMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            val fontStyles = listOf(
                                "SANS" to "Sans",
                                "SERIF" to "Serif",
                                "MONO" to "Mono",
                                "BOLD" to "Bold",
                                "ITALIC" to "Italic",
                                "SCRIPT" to "Script"
                            )
                            fontStyles.forEach { (styleKey, styleLabel) ->
                                FilterChip(
                                    selected = textFontStyle == styleKey,
                                    onClick = { textFontStyle = styleKey },
                                    label = { Text(styleLabel, fontSize = 11.sp) },
                                    modifier = Modifier.height(32.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Size: ${textFontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = textFontSizeSp,
                                    onValueChange = { textFontSizeSp = it },
                                    valueRange = 12f..48f,
                                    steps = 18
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Align", style = MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { textAlign = "LEFT" },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.Outlined.FormatAlignLeft,
                                            contentDescription = "Left",
                                            tint = if (textAlign == "LEFT") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    IconButton(
                                        onClick = { textAlign = "CENTER" },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.Outlined.FormatAlignCenter,
                                            contentDescription = "Center",
                                            tint = if (textAlign == "CENTER") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    IconButton(
                                        onClick = { textAlign = "RIGHT" },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.Outlined.FormatAlignRight,
                                            contentDescription = "Right",
                                            tint = if (textAlign == "RIGHT") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }

                        Text("Background Badge / Box", style = MaterialTheme.typography.labelMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            val bgColors = listOf(
                                null to "None",
                                "#FEF08A" to "Yellow",
                                "#A7F3D0" to "Mint",
                                "#E0E7FF" to "Lavender",
                                "#FBCFE8" to "Pink",
                                "#334155" to "Slate"
                            )
                            bgColors.forEach { (hex, name) ->
                                val isSelected = textBgHex == hex
                                Surface(
                                    onClick = { textBgHex = hex },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (hex != null) Color(android.graphics.Color.parseColor(hex)) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                    modifier = Modifier.size(36.dp, 28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (hex == null) Text("✕", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        Text("Text Color", style = MaterialTheme.typography.labelMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            val palette = listOf(
                                Color(0xFF1E293B), // Dark Ink
                                Color(0xFFFFFFFF), // White
                                Color(0xFFDC2626), // Red
                                Color(0xFF2563EB), // Blue
                                Color(0xFF16A34A), // Green
                                Color(0xFF9333EA)  // Purple
                            )
                            palette.forEach { color ->
                                val isSelected = textSelectedColorInt == color.toArgb()
                                Surface(
                                    onClick = { textSelectedColorInt = color.toArgb() },
                                    shape = CircleShape,
                                    color = color,
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary) else androidx.compose.foundation.BorderStroke(1.dp, Color.Gray),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {}
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val offset = textInputOffset!!
                            val style = CanvasTextStyle(
                                fontStyle = textFontStyle,
                                fontSizeSp = textFontSizeSp,
                                bgHex = textBgHex,
                                align = textAlign
                            )
                            val encodedText = style.encodeToString(textValue)
                            val newStroke = Stroke(
                                id = java.util.UUID.randomUUID().toString(),
                                tool = StrokeTool.TEXT,
                                colorInt = textSelectedColorInt,
                                width = textFontSizeSp,
                                points = listOf(PointF.fromOffset(offset)),
                                start = PointF.fromOffset(offset),
                                end = PointF.fromOffset(offset),
                                text = encodedText,
                                pdfPage = activeTargetPage,
                                layerId = activeLayerId ?: "layer_default"
                            )
                            activeStrokeList.add(newStroke)
                            // Phase 205: apply-time derivation (see CanvasCommitListPolicy).
                            onStrokesChanged(
                                com.authorss81.noteflow.services.CanvasCommitListPolicy.emittedList(
                                    currentAll = currentStrokesProvider(),
                                    isContinuousMode = isContinuousMode,
                                    pageOf = { it.pdfPage },
                                    pdfPageFilter = pdfPageFilter,
                                    scopedReplacement = activeStrokeList
                                )
                            )
                            showTextInputDialog = false
                        }
                    ) {
                        Text("Add Text")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTextInputDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showStickyNoteDialog && stickyNoteOffset != null) {
            AlertDialog(
                onDismissRequest = { showStickyNoteDialog = false },
                title = { Text("New Sticky Note") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = stickyNoteText,
                            onValueChange = { stickyNoteText = it },
                            label = { Text("Note content") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                        Text("Sticky Note Color", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val stickyColors = listOf(
                                "#FEF08A" to "Yellow",
                                "#A7F3D0" to "Mint",
                                "#DDD6FE" to "Lavender",
                                "#FFEDD5" to "Peach",
                                "#BAE6FD" to "Sky Blue"
                            )
                            for ((hex, label) in stickyColors) {
                                val c = Color(android.graphics.Color.parseColor(hex))
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(c)
                                        .border(
                                            width = if (stickyNoteColorHex == hex) 3.dp else 1.dp,
                                            color = if (stickyNoteColorHex == hex) MaterialTheme.colorScheme.primary else Color.Gray,
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                        .clickable { stickyNoteColorHex = hex }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val offset = stickyNoteOffset!!
                            val targetPage = getPageFromCanvasY(offset.y)
                            val newNote = CanvasStickyNote(
                                x = offset.x,
                                y = offset.y,
                                text = stickyNoteText,
                                colorHex = stickyNoteColorHex,
                                pdfPage = targetPage
                            )
                            activeStickyNoteList.add(newNote)
                            val otherNotes = if (isContinuousMode) emptyList() else stickyNotes.filter { it.pdfPage != pdfPageFilter }
                            onStickyNotesChanged(otherNotes + activeStickyNoteList)
                            showStickyNoteDialog = false
                        }
                    ) {
                        Text("Place Note")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStickyNoteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("annotation_canvas")
                    // Phase 225: surface the reference underlay to accessibility.
                    // Only when a reference image is present AND the eyedropper is
                    // active (the tool that can sample it), so an ordinary edit
                    // session is never labelled "reference image".
                    .then(
                        if (referenceImage != null && currentTool == StrokeTool.EYEDROPPER) {
                            Modifier.semantics {
                                contentDescription = "Reference image, tap eyedropper to sample"
                            }
                        } else {
                            Modifier
                        }
                    )
                    .graphicsLayer {
                        scaleX = internalZoomScale
                        scaleY = internalZoomScale
                        translationX = internalPanOffset.x
                        translationY = internalPanOffset.y
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    }
            ) {
                val activeInkRenderer = if (advancedBrushesEnabled) inkRenderer else null

                // Phase 205: LASER fade clock — draw-phase subscription. Reading
                // the tick here (only while trails exist) re-runs THIS draw pass
                // each frame so the alpha ramp in drawSingleStroke animates; no
                // snapshot write reaches composition, so nothing recomposes, and
                // fade frames never touch undo stacks or persistence.
                if (hasLaserStrokes) {
                    laserFadeTickState.longValue
                }

                // Phase 198 (PERF 2.1): the LIVE ink preview no longer renders in this
                // draw block — every pen sample mutated `activePoints`, and any read of
                // that list here re-invalidated THIS whole canvas node per sample
                // (paper, templates, page bitmaps, groupBy, layer blits all re-ran).
                // The classic live preview moved to the isolated `LiveStrokePreview`
                // overlay sibling below (same world transform), which is the ONLY node
                // subscribed to the per-sample state.
                //
                // The ONE deliberate exception is the AGSL wet-mixing pass: its shader
                // mixes the committed strokes of the active layer WITH the live preview
                // inside a single saveLayer, so for wet tools the preview must stay in
                // THIS draw scope or the shader's mix input changes (visual regression).
                // Wet-tool strokes therefore still invalidate this block per sample
                // (their pass is already dirty-rect scoped); every other tool isolates.
                val liveWetPreviewStroke = if (
                    com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(currentTool) &&
                    (activePoints.isNotEmpty() || (activeStart != null && activeEnd != null))
                ) {
                    Stroke(
                        id = "preview",
                        tool = currentTool,
                        colorInt = currentColor.toArgb(),
                        width = currentWidth,
                        points = activePoints,
                        start = activeStart,
                        end = activeEnd,
                        pdfPage = activeTargetPage,
                        isAdvanced = advancedBrushesEnabled,
                        colorMode = currentColorMode,
                        colorSeed = currentColorSeed,
                        gradientToColorInt = currentGradientToColor.toArgb()
                    )
                } else null

                // Phase 27: the AGSL wet-mixing shader takes a single color uniform, so
                // for multi-color modes we feed it the color derived at the CURRENT brush
                // position — the live preview sweeps the rainbow/gradient as it draws,
                // and the committed stroke re-derives its own per-point colors.
                // Phase 198: computed only while a WET preview is actually live, so
                // multi-color dry tools no longer read `activePoints` here either.
                val wetEffectColor = if (liveWetPreviewStroke != null && currentColorMode.isMultiColor && activePoints.isNotEmpty()) {
                    val progress = com.authorss81.noteflow.services.BrushColorModeMath.strokeProgress(activePoints, activePoints.size - 1)
                    Color(
                        com.authorss81.noteflow.services.BrushColorModeMath.colorForProgress(
                            currentColorMode,
                            currentColor.toArgb(),
                            progress,
                            currentColorSeed,
                            currentGradientToColor.toArgb()
                        )
                    )
                } else currentColor

                if (!isContinuousMode) {
                    drawPaperCard(0f, 0f, size.width, size.height, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper, grainBrush = paperGrainBrush, paperEdge = paperEdge, grainScale = grainScale)
                    drawPaperTemplate(template, 0f, 0f, size.width, size.height, isDarkPaper = isDarkPaper, paperTexture = paperTexture, templateOverrides = templateOverridesFor(template))

                    val bg = pdfPageBitmaps[pdfPageFilter] ?: backgroundImage
                    bg?.let { bitmap ->
                        val canvasWidth = size.width
                        val imgWidth = bitmap.width.toFloat()
                        val imgHeight = bitmap.height.toFloat()
                        if (imgWidth > 0f) {
                            val scale = canvasWidth / imgWidth
                            val dstWidth = canvasWidth
                            val dstHeight = imgHeight * scale
                            drawImage(
                                image = bitmap,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt())
                            )
                        }
                    }

                    // Phase 178: reference-image underlay — above the paper/template/
                    // background page image (so it is traceable on a scanned or
                    // PDF-backed page) yet strictly BELOW the ink pass (strokes draw
                    // over it and never modify it).
                    if (referenceImagePage == pdfPageFilter) {
                        drawReferenceImage(
                            referenceImage, referenceImageOpacity,
                            referenceImageX, referenceImageY,
                            referenceImageWidth, referenceImageHeight
                        )
                    }

                    // Phase 198: `previewStroke` is the wet-only live stroke — the
                    // classic live preview draws in the LiveStrokePreview overlay.
                    drawCompositedLayersStrokes(
                        strokes = activeStrokeList,
                        previewStroke = liveWetPreviewStroke,
                        layers = layers,
                        activeLayerId = activeLayerId,
                        offsetY = 0f,
                        isDarkPaper = isDarkPaper,
                        inkRenderer = activeInkRenderer,
                        layerBitmapCache = layerBitmapCache,
                        canvasDrawScope = canvasDrawScope,
                        density = density,
                        layoutDirection = layoutDirection,
                        pageIdx = pdfPageFilter,
                        pageWidth = size.width,
                        pageHeight = size.height,
                        pageTopY = 0f,
                        graphicsLayer = graphicsLayer,
                        wetMixingEffect = wetMixingEffect,
                        currentTool = currentTool,
                        currentColor = wetEffectColor,
                        currentWidth = currentWidth,
                        activePoints = activePoints,
                        activeStart = activeStart,
                        wetCanvasEngine = wetCanvasEngine,
                        wetBrushEngine = wetBrushEngine,
                        gpuWetBrushesEnabled = gpuWetBrushesEnabled,
                        strokeShadowEnabled = strokeShadowEnabled,
                        symmetryMode = symmetryMode,
                        symmetryCenterX = symmetryCenterFor(size.width, 0f).x,
                        symmetryCenterY = symmetryCenterFor(size.width, 0f).y,
                        strokeRenderOpts = strokeRenderOpts,
                        liveStrokeSeed = currentStrokeSeed,
                        vibrancyBoost = vibrancyBoost,
                        blenderStrengthPercent = blenderStrengthPercent,
                        scatterAmountPercent = scatterAmountPercent,
                        paperTextureStrength = paperTextureStrength,
                        alphaLockLayerIds = resolvedAlphaLockIds,
                        clippingMaskLayerIds = resolvedClippingMaskIds
                    )
                } else if (!divideIntoPages) {
                    val (canvasW, infiniteH) = computeCanvasWorld(size.width)

                    drawPaperCard(0f, 0f, canvasW, infiniteH, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper, pageLabel = null, grainBrush = paperGrainBrush, paperEdge = paperEdge, grainScale = grainScale)
                    drawPaperTemplate(template, 0f, 0f, canvasW, infiniteH, isDarkPaper = isDarkPaper, paperTexture = paperTexture, templateOverrides = templateOverridesFor(template))

                    // Phase 178: reference-image underlay (seamless world coords).
                    if (referenceImagePage == pdfPageFilter) {
                        drawReferenceImage(
                            referenceImage, referenceImageOpacity,
                            referenceImageX, referenceImageY,
                            referenceImageWidth, referenceImageHeight
                        )
                    }

                    // Phase 198: wet-only live preview here too — see the single-page
                    // branch above.
                    drawCompositedLayersStrokes(
                        strokes = activeStrokeList,
                        previewStroke = liveWetPreviewStroke,
                        layers = layers,
                        activeLayerId = activeLayerId,
                        offsetY = 0f,
                        isDarkPaper = isDarkPaper,
                        inkRenderer = activeInkRenderer,
                        layerBitmapCache = layerBitmapCache,
                        canvasDrawScope = canvasDrawScope,
                        density = density,
                        layoutDirection = layoutDirection,
                        pageIdx = 0,
                        pageWidth = canvasW,
                        pageHeight = infiniteH,
                        pageTopY = 0f,
                        graphicsLayer = graphicsLayer,
                        wetMixingEffect = wetMixingEffect,
                        currentTool = currentTool,
                        currentColor = wetEffectColor,
                        currentWidth = currentWidth,
                        activePoints = activePoints,
                        activeStart = activeStart,
                        wetCanvasEngine = wetCanvasEngine,
                        wetBrushEngine = wetBrushEngine,
                        gpuWetBrushesEnabled = gpuWetBrushesEnabled,
                        strokeShadowEnabled = strokeShadowEnabled,
                        symmetryMode = symmetryMode,
                        symmetryCenterX = symmetryCenterFor(size.width, 0f).x,
                        symmetryCenterY = symmetryCenterFor(size.width, 0f).y,
                        strokeRenderOpts = strokeRenderOpts,
                        liveStrokeSeed = currentStrokeSeed,
                        vibrancyBoost = vibrancyBoost,
                        blenderStrengthPercent = blenderStrengthPercent,
                        scatterAmountPercent = scatterAmountPercent,
                        paperTextureStrength = paperTextureStrength,
                        alphaLockLayerIds = resolvedAlphaLockIds,
                        clippingMaskLayerIds = resolvedClippingMaskIds
                    )
                } else {
                    val renderPageCount = dynamicPageCount
                    val canvasW = max(size.width, pageWidthPx)

                    // R2-b2b4-DOS-03 (phase-150): the pre-fix loop ran
                    // `activeStrokeList.filter { it.pdfPage == pageIdx }` for EACH
                    // visible page, so a zoomed-out long document paid O(pages ×
                    // strokes) every frame. One O(strokes) groupBy per frame (done
                    // here, once) turns every page's stroke retrieval into a map
                    // lookup.
                    val strokesByPage = activeStrokeList.groupBy { it.pdfPage }

                    // Phase 198 (PERF 2.5): the visible window is computed in closed
                    // form BEFORE the loop. The pre-198 culling was correct but still
                    // ITERATED all `renderPageCount` indices, doing per-page band
                    // arithmetic + a `continue` for every off-screen page —
                    // O(totalPages) frames on long documents. Pages are fixed-stride
                    // slabs, so ViewportPageWindowPolicy resolves the inclusive
                    // first..last visible range in O(1) and the loop below touches
                    // ONLY visible pages: O(visiblePages), not O(totalPages).
                    val visibleTop = (0f - internalPanOffset.y) / internalZoomScale
                    val visibleBottom = (size.height - internalPanOffset.y) / internalZoomScale

                    // Horizontal band: when panned/zoomed so the whole world is
                    // off the left/right edges, nothing on this document draws.
                    // (Hoisted out of the page loop — it never depended on the
                    // page index.)
                    val horizontallyOffscreen = canvasW <= 0f ||
                        (((0f - internalPanOffset.x) / internalZoomScale) > canvasW) ||
                        (((size.width - internalPanOffset.x) / internalZoomScale) < 0f)

                    val visiblePageWindow = if (horizontallyOffscreen) {
                        IntRange.EMPTY
                    } else {
                        com.authorss81.noteflow.services.ViewportPageWindowPolicy.visiblePageRange(
                            viewportTop = visibleTop,
                            viewportBottom = visibleBottom,
                            pageStride = pageHeightPx + pageGapPx,
                            pageSlabHeight = pageHeightPx,
                            pageCount = renderPageCount
                        )
                    }

                    for (pageIdx in visiblePageWindow) {
                        val pageTopY = pageIdx * (pageHeightPx + pageGapPx)

                        drawPaperCard(0f, pageTopY, canvasW, pageHeightPx, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper, pageLabel = "Page ${pageIdx + 1}", showPageLabel = showPageIndicator, grainBrush = paperGrainBrush, paperEdge = paperEdge, grainScale = grainScale)
                        drawPaperTemplate(template, 0f, pageTopY, canvasW, pageHeightPx, isDarkPaper = isDarkPaper, paperTexture = paperTexture, templateOverrides = templateOverridesFor(template))

                        val pageBitmap = pdfPageBitmaps[pageIdx]
                        if (pageBitmap != null) {
                            val imgWidth = pageBitmap.width.toFloat()
                            val imgHeight = pageBitmap.height.toFloat()
                            if (imgWidth > 0f) {
                                val scale = canvasW / imgWidth
                                val dstWidth = canvasW
                                val dstHeight = imgHeight * scale
                                drawImage(
                                    image = pageBitmap,
                                    dstOffset = IntOffset(0, pageTopY.toInt()),
                                    dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt())
                                )
                            }
                        } else if (pdfPageBitmaps[0] != null && renderPageCount > 1) {
                            val bgBitmap = pdfPageBitmaps[0]!!
                            val imgWidth = bgBitmap.width.toFloat()
                            val imgHeight = bgBitmap.height.toFloat()
                            if (imgWidth > 0f) {
                                val scale = canvasW / imgWidth
                                val pageHInImgPx = pageHeightPx / scale
                                val srcY = (pageIdx * pageHInImgPx).toInt()
                                val srcH = minOf(pageHInImgPx.toInt(), (imgHeight - srcY).toInt())
                                if (srcY < bgBitmap.height && srcH > 0) {
                                    val dstH = (srcH * scale).toInt()
                                    drawImage(
                                        image = bgBitmap,
                                        srcOffset = IntOffset(0, srcY),
                                        srcSize = IntSize(bgBitmap.width, srcH),
                                        dstOffset = IntOffset(0, pageTopY.toInt()),
                                        dstSize = IntSize(canvasW.toInt(), dstH)
                                    )
                                }
                            }
                        }

                        // Phase 178: reference-image underlay — above this page's
                        // paper/template/page-bitmap (traceable on scanned pages)
                        // yet strictly BELOW this page's ink pass (strokes draw over
                        // it, never modify it). Only on its own page; offset into the
                        // paginated world rect.
                        if (pageIdx == referenceImagePage) {
                            drawReferenceImage(
                                referenceImage, referenceImageOpacity,
                                referenceImageX, referenceImageY,
                                referenceImageWidth, referenceImageHeight,
                                pageTopY = pageTopY
                            )
                        }

                        // R2-b2b4-DOS-03 (phase-150): map lookup (hoisted above)
                        // instead of a fresh whole-list filter per page.
                        val pageStrokes = strokesByPage[pageIdx] ?: emptyList()
                        // Phase 198: wet-only live preview, restricted to the page the
                        // stroke is being drawn on — the classic preview lives in the
                        // LiveStrokePreview overlay.
                        val pageLiveWetPreview = if (activeTargetPage == pageIdx) liveWetPreviewStroke else null
                        drawCompositedLayersStrokes(
                            strokes = pageStrokes,
                            previewStroke = pageLiveWetPreview,
                            layers = layers,
                            activeLayerId = activeLayerId,
                            offsetY = 0f,
                            isDarkPaper = isDarkPaper,
                            inkRenderer = activeInkRenderer,
                            layerBitmapCache = layerBitmapCache,
                            canvasDrawScope = canvasDrawScope,
                            density = density,
                            layoutDirection = layoutDirection,
                            pageIdx = pageIdx,
                            pageWidth = canvasW,
                            pageHeight = pageHeightPx,
                            pageTopY = pageTopY,
                            graphicsLayer = graphicsLayer,
                            wetMixingEffect = wetMixingEffect,
                            currentTool = currentTool,
                            currentColor = wetEffectColor,
                            currentWidth = currentWidth,
                            activePoints = activePoints,
                            activeStart = activeStart,
                            wetCanvasEngine = wetCanvasEngine,
                            wetBrushEngine = wetBrushEngine,
                            gpuWetBrushesEnabled = gpuWetBrushesEnabled,
                            strokeShadowEnabled = strokeShadowEnabled,
                            symmetryMode = symmetryMode,
                            symmetryCenterX = symmetryCenterFor(size.width, pageTopY).x,
                            symmetryCenterY = symmetryCenterFor(size.width, pageTopY).y,
                            strokeRenderOpts = strokeRenderOpts,
                            liveStrokeSeed = currentStrokeSeed,
                            vibrancyBoost = vibrancyBoost,
blenderStrengthPercent = blenderStrengthPercent,
                             scatterAmountPercent = scatterAmountPercent,
                             paperTextureStrength = paperTextureStrength,
                             alphaLockLayerIds = resolvedAlphaLockIds,
                             clippingMaskLayerIds = resolvedClippingMaskIds
                         )
                    }
                }

                // Phase 198: the live eraser-cursor preview that used to close this
                // draw block moved to the LiveStrokePreview overlay below — it read
                // `eraserCursorCanvas` (mutated per eraser move), so keeping it here
                // re-invalidated this whole canvas node per sample.
            }

            // Phase 198 (PERF 2.1): ISOLATED LIVE-STROKE LAYER.
            //
            // Every pen sample used to re-run THIS whole canvas' draw block,
            // because the live preview read `activePoints` /
            // `activeStart` / `activeEnd` in the same draw scope as the paper,
            // templates, page bitmaps, per-page groupBy and layer blits. The
            // live ink now draws in a SEPARATE canvas node stacked above the
            // main pass and carrying the IDENTICAL world transform (same
            // zoom/pan graphicsLayer), so:
            //   • a pen sample invalidates ONLY this small node — the committed
            //     page (paper/template/bitmaps/cached layer blits) keeps its
            //     raster until real content changes;
            //   • world coordinates are shared unchanged (the transform is
            //     applied by a parent graphicsLayer, exactly as before);
            //   • stacking order vs the sticky/media/loupe/ring/minimap
            //     siblings is preserved (this node sits below them).
            //
            // The composition-phase gate below flips only twice per stroke
            // (start/end): `derivedStateOf` collapses per-sample point appends
            // into the boolean "is a stroke live" without recomposing — the
            // per-sample data still flows through DRAW-phase reads inside the
            // overlay, which is what confines the invalidation to this node.
            // (`snapshotFlow`-into-state was evaluated and rejected: routing
            // per-sample emissions through composition state would move the
            // invalidation INTO recomposition — strictly worse than a
            // draw-scope read; copying the points per sample via
            // `derivedStateOf { toList() }` would allocate on every sample.)
            //
            // The volatile live state reaches the overlay through PROVIDER
            // lambdas (`() -> T`), never as value parameters — a value param
            // would recompose this subtree on every sample (shape tools write
            // `activeEnd` per move; the eraser writes its cursor per move).
            // Providers are read only inside the overlay's draw scope.
            //
            // Wet tools are the documented exception: their live preview stays
            // in the main pass (see `liveWetPreviewStroke`) because the AGSL
            // shader mixes committed strokes WITH the preview in one
            // saveLayer.
            // Keyed on the tool so the derived predicate can never capture a
            // stale tool selection; the point/cursor states have stable
            // identities (remember'd), so they need no key.
            val liveOverlayVisible by remember(currentTool) {
                derivedStateOf {
                    activePoints.isNotEmpty() ||
                        (activeStart != null && activeEnd != null) ||
                        (currentTool == StrokeTool.ERASER && eraserCursorCanvas != null)
                }
            }
            if (liveOverlayVisible) {
                LiveStrokePreview(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("live_stroke_preview")
                        .graphicsLayer {
                            scaleX = internalZoomScale
                            scaleY = internalZoomScale
                            translationX = internalPanOffset.x
                            translationY = internalPanOffset.y
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        },
                    currentTool = currentTool,
                    currentColor = currentColor,
                    currentWidth = currentWidth,
                    inkRenderer = if (advancedBrushesEnabled) inkRenderer else null,
                    isDarkPaper = isDarkPaper,
                    strokeShadowEnabled = strokeShadowEnabled,
                    vibrancyBoost = vibrancyBoost,
                    strokeRenderOpts = strokeRenderOpts,
                    symmetryMode = symmetryMode,
                    symmetryCenterResolver = { screenW, worldY -> symmetryCenterFor(screenW, worldY) },
                    pageTopYResolver = { calculatePageYOffset(activeTargetPage) },
                    activePoints = activePoints,
                    activeStartProvider = { activeStart },
                    activeEndProvider = { activeEnd },
                    activeTargetPage = activeTargetPage,
                    advancedBrushesEnabled = advancedBrushesEnabled,
                    currentColorMode = currentColorMode,
                    currentColorSeed = currentColorSeed,
                    currentGradientToColor = currentGradientToColor,
                    eraserCursorProvider = { eraserCursorCanvas },
                    eraserMode = eraserMode,
                    activeStrokes = activeStrokeList,
                    scatterAmountPercent = scatterAmountPercent,
                    gradientDragStart = gradientDragStart,
                    gradientDragCurrent = gradientDragCurrent,
                    rulerEnabled = rulerEnabled
                )
            }

            // Phase 215: SELECTION OVERLAY — visible-at-rest dashed bounds +
            // per-stroke highlight while the SELECT tool holds a selection, and
            // the live lasso/marquee trail while one is being drawn. A separate
            // node with the IDENTICAL world transform (phase-198 pattern): the
            // per-sample lasso appends are read in THIS node's draw scope only,
            // so they never invalidate the main canvas pass, and no extra frame
            // pump runs — the overlay re-draws only when its inputs change.
            if (currentTool == StrokeTool.SELECT &&
                (lassoActive || strokeSelection.ids.isNotEmpty())
            ) {
                StrokeSelectionOverlay(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("stroke_selection_overlay")
                        .graphicsLayer {
                            scaleX = internalZoomScale
                            scaleY = internalZoomScale
                            translationX = internalPanOffset.x
                            translationY = internalPanOffset.y
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        },
                    accentColor = MaterialTheme.colorScheme.primary,
                    strokesProvider = { activeStrokeList },
                    selectedIds = strokeSelection.ids,
                    selectionBounds = strokeSelection.bounds,
                    lassoPointsProvider = { lassoPathPoints.toList() },
                    lassoVisible = lassoActive,
                    zoomScale = internalZoomScale,
                    // Phase 226: transform handles + preview wiring.
                    transformLocked = selectionTransformLocked,
                    onSelectionScale = currentOnSelectionScale,
                    onSelectionRotate = currentOnSelectionRotate
                )
            }

            for (note in activeStickyNoteList) {
                DraggableStickyNoteCard(
                    note = note,
                    zoomScale = internalZoomScale,
                    panOffset = internalPanOffset,
                    isContinuousMode = isContinuousMode,
                    pdfPageFilter = pdfPageFilter,
                    stickyNotes = stickyNotes,
                    activeStickyNoteList = activeStickyNoteList,
                    onStickyNotesChanged = onStickyNotesChanged
                )
            }

            for (embed in activeMediaEmbedList) {
                DraggableMediaEmbedCard(
                    embed = embed,
                    zoomScale = internalZoomScale,
                    panOffset = internalPanOffset,
                    isContinuousMode = isContinuousMode,
                    pdfPageFilter = pdfPageFilter,
                    mediaEmbeds = mediaEmbeds,
                    activeMediaEmbedList = activeMediaEmbedList,
                    activeStrokeList = activeStrokeList,
                    isPlayingVoice = isPlayingVoice,
                    activeVoicePlaybackFilePath = activeVoicePlaybackFilePath,
                    activeVoicePositionMsProvider = activeVoicePositionMsProvider,
                    activeVoiceSpeed = activeVoiceSpeed,
                    onMediaEmbedsChanged = onMediaEmbedsChanged,
                    onToggleVoicePlay = onToggleVoicePlay,
                    onVoiceSeekTo = onVoiceSeekTo,
                    onVoiceSpeedChange = onVoiceSpeedChange,
                    onExtractOcr = onExtractOcr
                )
            }

            // Phase 35: now a real magnifier — a 5x5 pixel-grid loupe sampled from
            // the ACTUAL rendered page bitmap around the pointer, so users can
            // micro-target strokes that are thinner than a finger. Falls back to a
            // plain color circle when no bitmap is available.
            if (currentTool == StrokeTool.EYEDROPPER && eyedropperPosition != null && sampledColorPreview != null) {
                val pos = eyedropperPosition!!
                val sampledColor = sampledColorPreview!!
                val loupeCanvasOffset = Offset(
                    x = (pos.x - internalPanOffset.x) / internalZoomScale,
                    y = (pos.y - internalPanOffset.y) / internalZoomScale
                )
                EyedropperMagnifierLoupe(
                    pos = pos,
                    sampledColor = sampledColor,
                    canvasOffset = loupeCanvasOffset,
                    targetPage = activeTargetPage,
                    rawBitmap = activeRawBitmapMap[activeTargetPage],
                    pageWidthPx = pageWidthPx,
                    pageHeightPx = pageHeightPx,
                    pageTopY = calculatePageYOffset(activeTargetPage),
                    fallbackColor = parsedPaperColor,
                    zoomScale = internalZoomScale,
                    density = LocalDensity.current
                )
            }

            // Phase 155: QUICK-COLOR RING overlay. Drawn AFTER the loupe so the
            // ring always sits on top; instant (no animation) per reduce-motion.
            if (quickColorRingOpen && quickColorSwatches.isNotEmpty()) {
                QuickColorRingOverlay(
                    anchor = quickColorRingAnchor,
                    swatches = quickColorSwatches,
                    currentColor = currentColor,
                    selectedIndex = quickColorRingSelection,
                    visibleSwatchCount = com.authorss81.noteflow.services.QuickColorRingMath.cappedSwatches(quickColorSwatches.size)
                )
            }

            // Phase 129: the map box is proportional to the canvas WORLD aspect
            // ratio (fitted inside the pre-35 120x140dp max box, aspect
            // preserved) and pan/zoom mapping uses a single uniform scale so it
            // agrees with the page — including seamless/infinite mode. The
            // widget sits at its default bottom-right corner and is draggable
            // only when the user opts in (default OFF); the drag offset is
            // session-scoped. The collapsible header is kept.
            if (showMinimap) {
                val mapDensity = LocalDensity.current
                // Phase 244/248 (Bug 1): anchor to this canvas box's REAL bounds
                // (canvasBoxW/H captured from the BoxWithConstraints scope),
                // NOT the device-wide LocalConfiguration dimensions. The canvas
                // may be smaller than the physical display (app bar, bottom bar,
                // system bars, cutout — and the second pane in a double-pane
                // Expanded window), so the old device dims pushed the
                // bottom-right minimap past the visible area on some
                // devices/orientations. Using the ACTUAL canvas pane keeps the
                // minimap inside view on every posture. `paneW`/`paneH` are the
                // pane-local names so the bindings can never be confused with
                // full-window dimensions.
                val paneW = canvasBoxW
                val paneH = canvasBoxH
                val (worldW, worldH) = computeCanvasWorld(paneW)
                val safePageW = if (worldW > 0f) worldW else 1000f
                val safeCanvasH = if (worldH > 0f) worldH else 1000f

                val maxBoxW = with(mapDensity) { MinimapGeometryPolicy.MAX_BOX_WIDTH_DP.dp.toPx() }
                val maxBoxH = with(mapDensity) { MinimapGeometryPolicy.MAX_BOX_HEIGHT_DP.dp.toPx() }
                val fit = MinimapGeometryPolicy.aspectFit(safePageW, safeCanvasH, maxBoxW, maxBoxH)
                val minimapWidthPx = fit.width
                val minimapHeightPx = fit.height
                val minimapWidthDp = with(mapDensity) { minimapWidthPx.toDp() }
                val minimapHeightDp = with(mapDensity) { minimapHeightPx.toDp() }
                val headerWidthDp = maxOf(minimapWidthDp, with(mapDensity) { 72.dp })

                val defaultAnchor = MinimapGeometryPolicy.defaultAnchorBottomEnd(
                    screenW = paneW,
                    screenH = paneH,
                    mapW = minimapWidthPx,
                    mapH = minimapHeightPx,
                    marginPx = with(mapDensity) { MinimapGeometryPolicy.DEFAULT_MARGIN_DP.dp.toPx() }
                )
                val restingPos = if (
                    FloatingWidgetDragPolicy.shouldApplyDraggedPosition(
                        enabled = minimapDraggable,
                        hasDraggedOffset = minimapDragOffset != null
                    ) && minimapDragOffset != null
                ) {
                    minimapDragOffset!!
                } else {
                    Offset(defaultAnchor.x, defaultAnchor.y)
                }
                val minimapInsets = WindowInsets.safeDrawing
                val topInsetPx = with(mapDensity) { minimapInsets.getTop(mapDensity).toFloat() }
                val bottomInsetPx = with(mapDensity) { minimapInsets.getBottom(mapDensity).toFloat() }
                val startInsetPx = with(mapDensity) { minimapInsets.getLeft(mapDensity, LayoutDirection.Ltr).toFloat() }
                val endInsetPx = with(mapDensity) { minimapInsets.getRight(mapDensity, LayoutDirection.Ltr).toFloat() }

                Surface(
                    tonalElevation = 6.dp,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(restingPos.x.roundToInt(), restingPos.y.roundToInt()) }
                        .pointerInput(minimapDraggable, minimapWidthPx, minimapHeightPx, paneW, paneH) {
                            if (!FloatingWidgetDragPolicy.mayDrag(minimapDraggable)) return@pointerInput
                            var dragStart = Offset.Zero
                            var dragBase = restingPos
                            detectDragGestures(
                                onDragStart = { dragStart = it; dragBase = restingPos },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val constrained = FloatingWidgetDragPolicy.constrainWithinSafeArea(
                                        dragBase.x + change.position.x - dragStart.x,
                                        dragBase.y + change.position.y - dragStart.y,
                                        paneW, paneH, minimapWidthPx, minimapHeightPx,
                                        topInsetPx, bottomInsetPx, startInsetPx, endInsetPx
                                    )
                                    minimapDragOffset = Offset(constrained.x, constrained.y)
                                },
                                onDragEnd = {},
                                onDragCancel = {}
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp).width(headerWidthDp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Minimap",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            IconButton(
                                onClick = { minimapExpanded = !minimapExpanded },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    if (minimapExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                                    contentDescription = "Toggle Minimap",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        if (minimapExpanded) {
                            Spacer(modifier = Modifier.height(4.dp))

                            // Phase 35: spatial HUD — spring-smoothed zoom %, active
                            // layer + layer count, and the viewport bounds in canvas
                            // coords. The zoom controls keep the canvas point under the
                            // viewport centre stationary and spring back to the "nice"
                            // level under full motion; reduce-motion snaps instead.
                            val smoothZoomPct = androidx.compose.animation.core.animateFloatAsState(
                                targetValue = internalZoomScale * 100f,
                                animationSpec = if (reduceMotion) {
                                    androidx.compose.animation.core.snap()
                                } else {
                                    com.authorss81.noteflow.theme.MotionSystem.SpringCanvasPan
                                },
                                label = "minimapZoom"
                            )
                            val activeLayerName = remember(layers, activeLayerId) {
                                layers.find { it.id == activeLayerId }?.name
                            }
                            val viewTopLeft = Offset(
                                -internalPanOffset.x / internalZoomScale,
                                -internalPanOffset.y / internalZoomScale
                            )
                            val viewBottomRight = Offset(
                                viewTopLeft.x + paneW / internalZoomScale,
                                viewTopLeft.y + paneH / internalZoomScale
                            )

                            fun zoomCanvasBy(mult: Float) {
                                val newScale = (internalZoomScale * mult).coerceIn(0.5f, 4.0f)
                                val center = Offset(paneW / 2f, paneH / 2f)
                                val canvasPoint = Offset(
                                    (center.x - internalPanOffset.x) / internalZoomScale,
                                    (center.y - internalPanOffset.y) / internalZoomScale
                                )
                                updateZoomAndPan(
                                    newScale,
                                    Offset(center.x - canvasPoint.x * newScale, center.y - canvasPoint.y * newScale)
                                )
                            }

                            // Phase 244 (Bug 3): the minimap's informational lines
                            // are pinned to the header width with single-line
                            // ellipsis so a long layer name or a far-panned view
                            // coordinate can't balloon the HUD height or spill
                            // past its (possibly narrow) box.
                            Text(
                                text = "Zoom ${smoothZoomPct.value.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                            )
                            Text(
                                text = buildString {
                                    append("Layers ${layers.size}")
                                    if (!activeLayerName.isNullOrBlank()) append(" · ${activeLayerName}")
                                    if (layers.isEmpty()) append(" · base")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                            )
                            Text(
                                text = "View (${viewTopLeft.x.toInt()},${viewTopLeft.y.toInt()})–(${viewBottomRight.x.toInt()},${viewBottomRight.y.toInt()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                IconButton(
                                    onClick = { zoomCanvasBy(0.75f) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Outlined.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(14.dp))
                                }
                                TextButton(
                                    onClick = { updateZoomAndPan(1f, Offset.Zero) },
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("100%", style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(
                                    onClick = { zoomCanvasBy(1.3333f) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = "Zoom In", modifier = Modifier.size(14.dp))
                                }
                            }

                            // Phase 172: minimap quick-view actions — zoom-to-fit and
                            // jump-home. Targets come from the pure-JVM
                            // CanvasNavigationPolicy (budgeted bounds + clamped fit);
                            // they animate through updateZoomAndPan with a spring and
                            // SNAP under reduce-motion. Scrollable so the row never
                            // clips on a narrow (72dp) map header.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Go:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FilledTonalIconButton(
                                    onClick = {
                                        val worldDims = computeCanvasWorld(paneW)
                                        val fitWorldW = if (worldDims.first > 0f) worldDims.first else MinimapGeometryPolicy.FALLBACK_WORLD
                                        val fitWorldH = if (worldDims.second > 0f) worldDims.second else MinimapGeometryPolicy.FALLBACK_WORLD
                                        val contentBounds = com.authorss81.noteflow.services.CanvasNavigationPolicy.computeContentBounds(
                                            activeStrokeList
                                        ) { page -> if (isContinuousMode) calculatePageYOffset(page) else 0f }
                                        val fit = com.authorss81.noteflow.services.CanvasNavigationPolicy.zoomToFit(
                                            contentBounds, paneW, paneH, fitWorldW, fitWorldH
                                        )
                                        navigateCanvasTo(fit.scale, Offset(fit.panX, fit.panY))
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        Icons.Outlined.CenterFocusWeak,
                                        contentDescription = "Zoom to fit ink",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val home = com.authorss81.noteflow.services.CanvasNavigationPolicy.jumpHome()
                                        navigateCanvasTo(home.scale, Offset(home.panX, home.panY))
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        Icons.Outlined.Home,
                                        contentDescription = "Jump to page start (home)",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))

                            Box(
                                modifier = Modifier
                                    .size(minimapWidthDp, minimapHeightDp)
                                    .background(if (isDarkTheme) Color(0xFF1E293B) else Color(0xFFF1F5F9), RoundedCornerShape(6.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                    .pointerInput(isContinuousMode, dynamicPageCount, divideIntoPages, layoutZoomScale, paneW, paneH, pageWidthPx, pageHeightPx) {
                                        val (w, h) = computeCanvasWorld(paneW)
                                        val spW = if (w > 0f) w else 1000f
                                        val spH = if (h > 0f) h else 1000f

                                        // Single uniform scale — the map box's aspect was
                                        // fitted to the world, so one scale maps both axes.
                                        val mapScale = size.width / spW

                                        val updatePanFromMap = { touchPos: Offset ->
                                            val targetCanvasX = (touchPos.x / mapScale).coerceIn(0f, spW)
                                            val targetCanvasY = (touchPos.y / mapScale).coerceIn(0f, spH)

                                            val newPanX = (paneW / 2f) - (targetCanvasX * internalZoomScale)
                                            val newPanY = (paneH / 2f) - (targetCanvasY * internalZoomScale)
                                            updateZoomAndPan(internalZoomScale, Offset(newPanX, newPanY))
                                        }

                                        detectTapGestures { tapOffset ->
                                            updatePanFromMap(tapOffset)
                                        }
                                    }
                                    .pointerInput(isContinuousMode, dynamicPageCount, divideIntoPages, layoutZoomScale, paneW, paneH, pageWidthPx, pageHeightPx) {
                                        val (w, h) = computeCanvasWorld(paneW)
                                        val spW = if (w > 0f) w else 1000f
                                        val spH = if (h > 0f) h else 1000f

                                        val mapScale = size.width / spW

                                        detectDragGestures { change, _ ->
                                            change.consume()
                                            val targetCanvasX = (change.position.x / mapScale).coerceIn(0f, spW)
                                            val targetCanvasY = (change.position.y / mapScale).coerceIn(0f, spH)

                                            val newPanX = (paneW / 2f) - (targetCanvasX * internalZoomScale)
                                            val newPanY = (paneH / 2f) - (targetCanvasY * internalZoomScale)
                                            updateZoomAndPan(internalZoomScale, Offset(newPanX, newPanY))
                                        }
                                    }
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val (w, h) = computeCanvasWorld(paneW)
                                    val spW = if (w > 0f) w else 1000f
                                    val spH = if (h > 0f) h else 1000f

                                    // Uniform map scale so strokes + viewport align with
                                    // the page at the fitted aspect ratio.
                                    val mapScale = minOf(size.width / spW, size.height / spH)

                                    drawRect(
                                        color = if (isDarkTheme) Color(0xFF334155) else Color.White,
                                        topLeft = Offset(0f, 0f),
                                        size = Size(spW * mapScale, spH * mapScale)
                                    )

                                    // Draw stroke preview thumbnails — R2-b2b4-DOS-03 (phase-150): the pre-fix
                                    // loop re-walked EVERY stroke/point from the main
                                    // thread at a fixed 1-4 stride (~50k drawLine at the
                                    // phase-50 geometry cap) on every pan/zoom frame.
                                    // Budgeted strides (MinimapGeometryPolicy) hold the
                                    // pass to <= MAX_MINIMAP_SAMPLED_STROKES sampled
                                    // strokes and <= MAX_MINIMAP_POLYLINE_SEGMENTS
                                    // poly-line segments total.
                                    val strokeCount = activeStrokeList.size
                                    val totalPoints = activeStrokeList.sumOf { it.points.size }
                                    val strokeStep = MinimapGeometryPolicy.strokeStepFor(strokeCount)
                                    val pointStep = MinimapGeometryPolicy.pointStepFor(totalPoints)
                                    for (sIdx in 0 until strokeCount step strokeStep) {
                                        val stroke = activeStrokeList[sIdx]
                                        if (stroke.points.size > 1) {
                                            val pCount = stroke.points.size
                                            var prevPt = stroke.points[0]
                                            var drew = false
                                            for (i in pointStep until pCount step pointStep) {
                                                val nextPt = stroke.points[i]
                                                drawLine(
                                                    color = stroke.color.copy(alpha = 0.7f),
                                                    start = Offset(prevPt.x * mapScale, prevPt.y * mapScale),
                                                    end = Offset(nextPt.x * mapScale, nextPt.y * mapScale),
                                                    strokeWidth = 2f
                                                )
                                                prevPt = nextPt
                                                drew = true
                                            }
                                            // Phase-150 review fix 5: when the GLOBAL point
                                            // stride overshoots this stroke's own point count
                                            // (huge document ⇒ large stride), the poly-line
                                            // loop above draws nothing and a short stroke used
                                            // to vanish from the thumbnail. Draw the stroke as
                                            // a single start→end line instead so it stays
                                            // visible, at the cost of ≤1 extra drawLine per
                                            // sampled stroke (already inside the policy's
                                            // maxLineDraws bound).
                                            if (!drew) {
                                                val startPt = stroke.points[0]
                                                val endPt = stroke.points[pCount - 1]
                                                drawLine(
                                                    color = stroke.color.copy(alpha = 0.7f),
                                                    start = Offset(startPt.x * mapScale, startPt.y * mapScale),
                                                    end = Offset(endPt.x * mapScale, endPt.y * mapScale),
                                                    strokeWidth = 2f
                                                )
                                            }
                                        } else if (stroke.start != null && stroke.end != null) {
                                            drawLine(
                                                color = stroke.color.copy(alpha = 0.7f),
                                                start = Offset(stroke.start.x * mapScale, stroke.start.y * mapScale),
                                                end = Offset(stroke.end.x * mapScale, stroke.end.y * mapScale),
                                                strokeWidth = 2f
                                            )
                                        }
                                    }

                                    val viewWOnCanvas = paneW / internalZoomScale
                                    val viewHOnCanvas = paneH / internalZoomScale
                                    val viewXOnCanvas = -internalPanOffset.x / internalZoomScale
                                    val viewYOnCanvas = -internalPanOffset.y / internalZoomScale

                                    val rectX = (viewXOnCanvas * mapScale).coerceIn(0f, size.width)
                                    val rectY = (viewYOnCanvas * mapScale).coerceIn(0f, size.height)
                                    val rectW = (viewWOnCanvas * mapScale).coerceIn(10f, size.width)
                                    val rectH = (viewHOnCanvas * mapScale).coerceIn(10f, size.height)

                                    drawRect(
                                        color = Color(0xFF2563EB).copy(alpha = 0.25f),
                                        topLeft = Offset(rectX, rectY),
                                        size = Size(rectW, rectH)
                                    )
                                    drawRect(
                                        color = Color(0xFF2563EB),
                                        topLeft = Offset(rectX, rectY),
                                        size = Size(rectW, rectH),
                                        style = DrawStrokeStyle(width = 2f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(currentTool) || currentTool == StrokeTool.CALLIGRAPHIC || currentTool == StrokeTool.CHISEL_MARKER || wetCanvasEngine.isCanvasWet) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (wetCanvasEngine.isCanvasWet) "💧 Wet ${(wetCanvasEngine.activeWetnessLevel * 100).toInt()}%" else "☀️ Sheet Dry",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (wetCanvasEngine.isCanvasWet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalIconButton(
                        onClick = { showBrushStudio = true },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Outlined.Palette, contentDescription = "Brush Studio", modifier = Modifier.size(16.dp))
                    }

                    if (wetCanvasEngine.isCanvasWet) {
                        FilledTonalIconButton(
                            onClick = { wetCanvasEngine.dryCanvasSheet() },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Outlined.WbSunny, contentDescription = "Dry Page Sheet", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        if (showBrushStudio) {
            BrushStudioDialog(
                engine = wetCanvasEngine,
                velocityModulated = velocityModulated,
                velocityIntensity = velocityIntensity,
                onVelocityModulatedChange = { value ->
                    velocityModulated = value
                    brushRenderSettings.velocityModulationEnabled = value
                },
                onVelocityIntensityChange = { value ->
                    velocityIntensity = value
                    brushRenderSettings.velocityModulationIntensity = value
                },
                nibAngleDeg = nibAngleDeg,
                onNibAngleChange = { value ->
                    nibAngleDeg = value
                    brushRenderSettings.calligraphicNibAngleDeg = value
                },
                chiselNibAngleDeg = chiselNibAngleDeg,
                onChiselNibAngleChange = { value ->
                    chiselNibAngleDeg = value
                     brushRenderSettings.chiselNibAngleDeg = value
                 },
                 onDismiss = { showBrushStudio = false }
             )
         }
     }
 }

/**
 * Phase 198 (PERF 2.1): isolated live-stroke layer.
 *
 * Draws ONLY the in-progress ink (classic tools) and the eraser aim cursor,
 * inside a canvas node whose ONLY snapshot dependencies are the per-sample
 * live state — so a pen sample re-draws this node alone instead of the whole
 * [AnnotationCanvas] pass. The caller stacks it above the main canvas with the
 * identical zoom/pan [modifier] transform, so all coordinates here are the
 * same world coordinates the main pass uses.
 *
 * Rendering parity notes:
 *  • The preview Stroke is built with exactly the fields the pre-198 inline
 *    constructions used (id "preview", pdfPage activeTargetPage,
 *    current mode/seed/gradient) and rendered through the same
 *    [drawSingleStroke] + view-time symmetry mirror as committed strokes.
 *  • Wet tools are excluded here: their live preview renders in the MAIN
 *    canvas ([AnnotationCanvas]'s `liveWetPreviewStroke`) because the AGSL
 *    wet-mixing shader must see committed strokes + preview in one saveLayer.
 *  • The composition body reads NO per-sample state; everything below runs in
 *    the draw phase, which is what keeps invalidation scoped to this node.
 */
@Composable
private fun LiveStrokePreview(
    modifier: Modifier,
    currentTool: StrokeTool,
    currentColor: Color,
    currentWidth: Float,
    advancedBrushesEnabled: Boolean,
    inkRenderer: CanvasStrokeRenderer?,
    isDarkPaper: Boolean,
    // Phase 213: composition-level shadow gate, threaded so the LIVE preview
    // casts the same drop shadow the committed stroke will.
    strokeShadowEnabled: Boolean,
    vibrancyBoost: Float,
    strokeRenderOpts: StrokeRenderOpts,
    symmetryMode: SymmetryMode,
    symmetryCenterResolver: (screenW: Float, worldY: Float) -> Offset,
    pageTopYResolver: () -> Float,
    activePoints: List<PointF>,
    // Phase 198: volatile per-sample state arrives as PROVIDER lambdas and is
    // read only in the draw scope — as value params they would recompose this
    // composable on every pen/eraser sample (see call-site comment).
    activeStartProvider: () -> PointF?,
    activeEndProvider: () -> PointF?,
    activeTargetPage: Int,
    currentColorMode: com.authorss81.noteflow.data.model.StrokeColorMode,
    currentColorSeed: Int,
    currentGradientToColor: Color,
    eraserCursorProvider: () -> Offset?,
    eraserMode: com.authorss81.noteflow.services.EraserMode,
    activeStrokes: List<Stroke>,
    // Phase 220: pro brush controls — scatter for bitmap stamps.
    scatterAmountPercent: Int = 0,
    // Phase 221 review fix #6: gradient drag live preview state.
    gradientDragStart: Offset? = null,
    gradientDragCurrent: Offset? = null,
    // Phase 223: ruler — draw a live straight guide (start→current) ON TOP of
    // the freehand preview so the user sees exactly where the committed LINE
    // will snap.
    rulerEnabled: Boolean = false
) {
    Canvas(modifier = modifier) {
        val hasLiveInk = activePoints.isNotEmpty() || (activeStartProvider() != null && activeEndProvider() != null)

        // 1. Classic live ink preview (wet tools render in the main pass).
        if (hasLiveInk && !com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(currentTool)) {
            val previewStroke = Stroke(
                id = "preview",
                tool = currentTool,
                colorInt = currentColor.toArgb(),
                width = currentWidth,
                points = activePoints,
                start = activeStartProvider(),
                end = activeEndProvider(),
                pdfPage = activeTargetPage,
                isAdvanced = advancedBrushesEnabled,
                colorMode = currentColorMode,
                colorSeed = currentColorSeed,
                gradientToColorInt = currentGradientToColor.toArgb()
            )
            drawSingleStroke(
                stroke = previewStroke,
                offsetY = 0f,
                isDarkPaper = isDarkPaper,
                inkRenderer = inkRenderer,
                renderOpts = strokeRenderOpts,
                vibrancy = vibrancyBoost,
                shadowEnabled = strokeShadowEnabled,
                scatterAmountPercent = scatterAmountPercent
            )
            // Phase 07: view-time symmetry mirror of the live preview — same
            // page-anchored axis center the committed strokes mirror through.
            if (symmetryMode != SymmetryMode.OFF && currentTool != StrokeTool.TEXT) {
                val axisCenter = symmetryCenterResolver(size.width, pageTopYResolver())
                fun mirror(p: PointF): PointF {
                    val m = SymmetryHelper.mirrorPoint(p.x, p.y, symmetryMode, axisCenter.x, axisCenter.y)
                    return p.copy(x = m.x, y = m.y)
                }
                drawSingleStroke(
                    stroke = previewStroke.copy(
                        points = previewStroke.points.map { mirror(it) },
                        start = previewStroke.start?.let { mirror(it) },
                        end = previewStroke.end?.let { mirror(it) }
                    ),
                    offsetY = 0f,
                    isDarkPaper = isDarkPaper,
                    inkRenderer = inkRenderer,
                    renderOpts = strokeRenderOpts,
                    vibrancy = vibrancyBoost,
                    shadowEnabled = strokeShadowEnabled,
                    scatterAmountPercent = scatterAmountPercent
                )
            }
        }

        // Phase 223: ruler guide — while dragging with the ruler on, draw a
        // straight start→current line over the freehand preview so the user sees
        // the exact geometry that will be committed (it is drawn AFTER the
        // freehand preview so it reads as the authoritative line).
        // Phase 223 review fix: the ruler guide uses the SAME exclusion set the
        // commit path uses (wet/fleeting + LASER + style-preserving tools stay
        // freehand), so a straight guide is never shown over a stroke that will
        // be committed as a wavy line.
        val rulerGuideExcludedTool = currentTool == StrokeTool.LASER ||
            com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(currentTool) ||
            currentTool == StrokeTool.DOTTED || currentTool == StrokeTool.NEON ||
            currentTool == StrokeTool.CHARCOAL || currentTool == StrokeTool.OIL_PASTEL ||
            currentTool == StrokeTool.DRY_BRUSH || currentTool == StrokeTool.PALETTE_KNIFE
        if (rulerEnabled && !rulerGuideExcludedTool && currentTool.isFreehandTool) {
            val rs = activeStartProvider()
            val re = activeEndProvider()
            if (rs != null && re != null) {
                val guideColor = currentColor.copy(alpha = 0.85f)
                drawLine(
                    color = guideColor,
                    start = Offset(rs.x, rs.y),
                    end = Offset(re.x, re.y),
                    strokeWidth = currentWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        // Phase 221 review fix #6: gradient drag live preview — a semi-transparent
        // arrow showing the gradient direction while the user is dragging.
        if (currentTool == StrokeTool.GRADIENT && gradientDragStart != null && gradientDragCurrent != null) {
            val start = gradientDragStart
            val end = gradientDragCurrent
            val dx = end.x - start.x
            val dy = end.y - start.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len > 2f) {
                val previewColor = currentColor.copy(alpha = 0.5f)
                drawLine(
                    color = previewColor,
                    start = Offset(start.x, start.y),
                    end = Offset(end.x, end.y),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
                val angle = kotlin.math.atan2(dy, dx)
                val headLen = 14f
                val headAngle = 0.5f
                drawLine(
                    color = previewColor,
                    start = Offset(end.x, end.y),
                    end = Offset(
                        end.x - headLen * kotlin.math.cos(angle - headAngle).toFloat(),
                        end.y - headLen * kotlin.math.sin(angle - headAngle).toFloat()
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = previewColor,
                    start = Offset(end.x, end.y),
                    end = Offset(
                        end.x - headLen * kotlin.math.cos(angle + headAngle).toFloat(),
                        end.y - headLen * kotlin.math.sin(angle + headAngle).toFloat()
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
        }

        // 2. Eraser aim cursor (moved verbatim from the pre-198 main pass):
        //    PARTIAL -> the round mask circle the next stamp will carve;
        //    STROKE  -> highlight every stroke the hit-test predicts removed.
        if (currentTool == StrokeTool.ERASER) {
            val cursorPos = eraserCursorProvider()
            if (cursorPos != null) {
                if (eraserMode == com.authorss81.noteflow.services.EraserMode.PARTIAL) {
                    val previewR = com.authorss81.noteflow.services.EraserGeometryPolicy.previewRadius(currentWidth, currentWidth)
                    // Phase 200 (PERF 3.5): AA parity with ink. The flat fill
                    // becomes a radial gradient whose falloff is SAMPLED from
                    // EraserGeometryPolicy.cursorFillAlphaAt — the exact
                    // BrushColorModeMath.edgeFeather(hardness=1) curve the wet
                    // shader uses — so the cursor edge has the same guaranteed
                    // >=1.5px penumbra as real ink instead of a hard aliased
                    // rim.
                    //
                    // Review-fix (phase-200): the stops are NOT uniform across
                    // [0,1] — that under-sampled the penumbra on large radii
                    // (at r=96px the whole 1.5px hermite fell inside one stop
                    // interval and rendered ~8px wide). The layout now holds an
                    // opaque plateau out to cursorBandStartNd(previewR) — the
                    // exact edgeFeather band start — and spends ALL sampling
                    // stops uniformly across [bandStart, 1]. Piecewise-linear
                    // interpolation of the hermite over n segments has max
                    // error ≈ 0.75/n² of full scale (≈0.5% at n=12), so the
                    // shipped gradient tracks the ink curve to sub-1% alpha.
                    val fillColor =
                        currentColor.copy(alpha = com.authorss81.noteflow.services.EraserGeometryPolicy.CURSOR_FILL_ALPHA)
                    val n = com.authorss81.noteflow.services.EraserGeometryPolicy.CURSOR_FEATHER_STOP_COUNT
                    val bandStart =
                        com.authorss81.noteflow.services.EraserGeometryPolicy.cursorBandStartNd(previewR)
                    val stops = ArrayList<Pair<Float, Color>>(n + 2)
                    stops.add(
                        0f to fillColor.copy(
                            alpha = fillColor.alpha * com.authorss81.noteflow.services.EraserGeometryPolicy.cursorFillAlphaAt(0f, previewR)
                        )
                    )
                    for (i in 0..n) {
                        val nd = bandStart + (1f - bandStart) * i.toFloat() / n
                        val a = com.authorss81.noteflow.services.EraserGeometryPolicy.cursorFillAlphaAt(nd, previewR)
                        stops.add(nd to fillColor.copy(alpha = fillColor.alpha * a))
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = stops.toTypedArray(),
                            center = cursorPos,
                            radius = previewR
                        ),
                        radius = previewR,
                        center = cursorPos
                    )
                    drawCircle(
                        currentColor.copy(alpha = com.authorss81.noteflow.services.EraserGeometryPolicy.CURSOR_RING_ALPHA),
                        radius = previewR,
                        center = cursorPos,
                        style = DrawStrokeStyle(width = com.authorss81.noteflow.services.EraserGeometryPolicy.CURSOR_RING_WIDTH_PX)
                    )
                } else {
                    // Phase 203: plain hit-test — mirrored twins are real stroke
                    // rows, so the highlight predicts exactly the row(s) the
                    // eraser would delete (the old mirror-the-query-point
                    // special-case is gone with the view-time erase path).
                    for (stroke in activeStrokes) {
                        val hits = strokeContainsPoint(stroke, cursorPos)
                        if (!hits) continue
                        if (stroke.points.size > 1) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(stroke.points.first().x, stroke.points.first().y)
                                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, currentColor.copy(alpha = 0.25f), style = DrawStrokeStyle(width = stroke.width + 10f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        } else {
                            val anchor = stroke.start ?: stroke.end ?: continue
                            drawCircle(currentColor.copy(alpha = 0.25f), radius = (stroke.width + 18f), center = Offset(anchor.x, anchor.y))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 35: magnifier loupe for the Eyedropper tool. Draws a 5x5 grid of pixels
 * sampled from the actual rendered page bitmap around the pointer position,
 * magnified ~4x so users can micro-target sub-finger strokes. Pure rendering —
 * the color pick logic stays in [sampleColorAt]/EyedropperSamplingMath.
 */
/**
 * Phase 215: stroke-selection visuals for the real SELECT tool. Draws, in WORLD
 * coordinates under the same zoom/pan transform as ink:
 *  1. while a lasso/marquee drag is live ([lassoVisible]) — the closed trail as
 *     a translucent fill + dashed outline;
 *  2. at rest — every selected stroke highlighted (eraser-highlight geometry:
 *     `width + 10f` round pass at low alpha, but in the ACCENT color so it is
 *     never mistaken for an erase preview) plus a dashed bounding box around
 *     [selectionBounds] (visible at rest by design — unlike the phase-193
 *     resize handles, selection must stay legible between gestures).
 *
 * No animation pump: the node re-draws only when its inputs change.
 * `internal` so the phase-215 Paparazzi screenshot can render it directly.
 */
@Composable
internal fun StrokeSelectionOverlay(
    modifier: Modifier,
    accentColor: Color,
    strokesProvider: () -> List<Stroke>,
    selectedIds: Set<String>,
    selectionBounds: androidx.compose.ui.geometry.Rect,
    lassoPointsProvider: () -> List<PointF>,
    lassoVisible: Boolean,
    // Current canvas zoom — dash patterns divide by it so dashes keep a
    // constant SCREEN size at any magnification.
    zoomScale: Float = 1f,
    // Phase 226: transform handles — visible AT REST (alpha 1) for a held
    // selection, unlike the phase-193 dim canvas resize handles. 4 corner
    // handles scale; the top handle rotates. Mid-drag preview is a dashed
    // outline only; the single commit call fires on gesture end.
    transformLocked: Boolean = true,
    onSelectionScale: (scaleX: Float, scaleY: Float, centerX: Float, centerY: Float) -> Unit = { _, _, _, _ -> },
    onSelectionRotate: (degrees: Float, centerX: Float, centerY: Float) -> Unit = { _, _, _ -> }
) {
    val density = LocalDensity.current
    val handlePx = with(density) { com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.HANDLE_SIZE_DP.dp.toPx() }
    val rotHandlePx = with(density) { com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.ROTATION_HANDLE_SIZE_DP.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }

    // Phase 226 review-fix (finding #4): at the minimum selection size
    // (MIN_SELECTION_SIZE_PX=20) the fixed 24-26dp handles (~72px at xxhdpi)
    // dwarf and mutually overlap a tiny selection. Shrink the handles toward
    // the selection's smaller dimension (floored at a minimum still-grabbable
    // size) so a small selection keeps usable, non-overlapping handles while a
    // large selection keeps the standard embed-sized handles.
    val minSelectDim = min(selectionBounds.width, selectionBounds.height)
    val handleScale = if (minSelectDim <= 0f) 1f else {
        val cap = minSelectDim * 0.5f / handlePx
        cap.coerceIn(0.33f, 1f)
    }
    val selHandlePx = handlePx * handleScale
    val selRotHandlePx = rotHandlePx * handleScale

    // Phase 226: transient transform-preview state (scale and rotation), owned
    // here so mid-drag previews render in this node's draw scope and the main
    // canvas pass is never invalidated per sample.
    var previewScaleX by remember { mutableFloatStateOf(1f) }
    var previewScaleY by remember { mutableFloatStateOf(1f) }
    var previewRotation by remember { mutableFloatStateOf(0f) }
    var transformActive by remember { mutableStateOf(false) }
    var scaleDragCorner by remember { mutableStateOf<com.authorss81.noteflow.services.SelectionTransformPolicy.Corner?>(null) }

    val currentSelectionBounds by rememberUpdatedState(selectionBounds)
    val currentZoom by rememberUpdatedState(zoomScale)

    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val lasso = if (lassoVisible) lassoPointsProvider() else emptyList()
            if (lasso.size >= 2) {
                val trail = androidx.compose.ui.graphics.Path().apply {
                    moveTo(lasso.first().x, lasso.first().y)
                    for (i in 1 until lasso.size) lineTo(lasso[i].x, lasso[i].y)
                    close()
                }
                drawPath(trail, accentColor.copy(alpha = com.authorss81.noteflow.services.LassoTrailPolicy.TRAIL_FILL_ALPHA))
                drawPath(
                    trail,
                    accentColor.copy(alpha = com.authorss81.noteflow.services.LassoTrailPolicy.TRAIL_OUTLINE_ALPHA),
                    style = DrawStrokeStyle(
                        width = com.authorss81.noteflow.services.LassoTrailPolicy.TRAIL_STROKE_WIDTH_PX,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(com.authorss81.noteflow.services.LassoTrailPolicy.trailDashPattern(zoomScale))
                    )
                )
            }

            // 2. Resting (or live-preview) selection: per-stroke highlight +
            //    dashed union bounds.
            if (selectedIds.isNotEmpty()) {
                for (stroke in strokesProvider()) {
                    if (stroke.id !in selectedIds) continue
                    if (stroke.points.size > 1) {
                        val highlight = androidx.compose.ui.graphics.Path().apply {
                            moveTo(stroke.points.first().x, stroke.points.first().y)
                            for (i in 1 until stroke.points.size) lineTo(stroke.points[i].x, stroke.points[i].y)
                        }
                        drawPath(
                            highlight,
                            accentColor.copy(alpha = com.authorss81.noteflow.services.LassoTrailPolicy.HIGHLIGHT_ALPHA),
                            style = DrawStrokeStyle(
                                width = stroke.width + 10f,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round
                            )
                        )
                    } else {
                        val anchor = stroke.start ?: stroke.end ?: continue
                        drawCircle(
                            accentColor.copy(alpha = com.authorss81.noteflow.services.LassoTrailPolicy.HIGHLIGHT_ALPHA),
                            radius = stroke.width + 18f,
                            center = Offset(anchor.x, anchor.y)
                        )
                    }
                }

                // The dashed box: resting bounds, OR the live transform preview
                // (scaled / rotated) while a handle is being dragged. The preview
                // is render-side only — nothing is committed until drag-end.
                val displayBounds = when {
                    transformActive && scaleDragCorner != null ->
                        com.authorss81.noteflow.services.SelectionTransformPolicy.scaledBounds(
                            currentSelectionBounds, previewScaleX, previewScaleY
                        )
                    transformActive && previewRotation != 0f ->
                        com.authorss81.noteflow.services.SelectionTransformPolicy.rotatedBounds(
                            currentSelectionBounds, previewRotation
                        )
                    else -> currentSelectionBounds
                }

                if (displayBounds.width > 0f || displayBounds.height > 0f) {
                    val padded = displayBounds.inflate(com.authorss81.noteflow.services.StrokeHitPolicy.SELECTION_BOUNDS_PADDING_PX)
                    drawRoundRect(
                        color = accentColor.copy(alpha = com.authorss81.noteflow.services.LassoTrailPolicy.BOUNDS_OUTLINE_ALPHA),
                        topLeft = Offset(padded.left, padded.top),
                        size = Size(padded.width, padded.height),
                        cornerRadius = CornerRadius(6f, 6f),
                        style = DrawStrokeStyle(
                            width = com.authorss81.noteflow.services.LassoTrailPolicy.BOUNDS_STROKE_WIDTH_PX,
                            pathEffect = PathEffect.dashPathEffect(com.authorss81.noteflow.services.LassoTrailPolicy.boundsDashPattern(zoomScale))
                        )
                    )
                }
            }
        }

        // 3. Phase 226: transform handles — visible AT REST for a held
        //    selection. Composed only while a selection is held (never during a
        //    lasso draw). Each corner handles a scale drag; the top-center
        //    handle rotates.
        if (selectedIds.isNotEmpty() && !lassoVisible) {
            val bounds = selectionBounds
            val cx = (bounds.left + bounds.right) / 2f
            val cy = (bounds.top + bounds.bottom) / 2f
            com.authorss81.noteflow.services.SelectionTransformPolicy.Corner.entries.forEach { corner ->
                val (hx, hy) = com.authorss81.noteflow.services.SelectionTransformPolicy.cornerPosition(bounds, corner)
                SelectionCornerHandle(
                    modifier = Modifier.offset { IntOffset(hx.roundToInt() - (selHandlePx / 2f).roundToInt(), hy.roundToInt() - (selHandlePx / 2f).roundToInt()) },
                    sizePx = selHandlePx,
                    accentColor = accentColor,
                    zoomScale = currentZoom,
                    corner = corner,
                    locked = transformLocked,
                    onDragUpdate = { worldDx, worldDy ->
                        val (sx, sy) = com.authorss81.noteflow.services.SelectionTransformPolicy.cornerScaleFromDrag(
                            corner = corner, bounds = bounds, dragWorldDx = worldDx, dragWorldDy = worldDy, locked = transformLocked
                        )
                        scaleDragCorner = corner
                        transformActive = true
                        previewScaleX = sx
                        previewScaleY = sy
                        previewRotation = 0f
                    },
                    onDragEnd = { worldDx, worldDy ->
                        val (sx, sy) = com.authorss81.noteflow.services.SelectionTransformPolicy.cornerScaleFromDrag(
                            corner = corner, bounds = bounds, dragWorldDx = worldDx, dragWorldDy = worldDy, locked = transformLocked
                        )
                        scaleDragCorner = null
                        transformActive = false
                        previewScaleX = 1f
                        previewScaleY = 1f
                        if (sx != 1f || sy != 1f) {
                            onSelectionScale(sx, sy, cx, cy)
                        }
                    },
                    onDragCancel = {
                        scaleDragCorner = null
                        transformActive = false
                        previewScaleX = 1f
                        previewScaleY = 1f
                    }
                )
            }

            SelectionRotationHandle(
                modifier = Modifier.offset { IntOffset(cx.roundToInt() - (selRotHandlePx / 2f).roundToInt(), (bounds.top - gapPx - selRotHandlePx).roundToInt()) },
                sizePx = selRotHandlePx,
                gapPx = gapPx,
                selectionHalfWidth = bounds.width / 2f,
                selectionHalfHeight = bounds.height / 2f,
                accentColor = accentColor,
                zoomScale = currentZoom,
                onDragUpdate = { degrees ->
                    transformActive = true
                    scaleDragCorner = null
                    previewScaleX = 1f
                    previewScaleY = 1f
                    previewRotation = degrees
                },
                onDragEnd = { degrees ->
                    transformActive = false
                    previewRotation = 0f
                    if (degrees != 0f) {
                        onSelectionRotate(degrees, cx, cy)
                    }
                },
                onDragCancel = {
                    transformActive = false
                    previewRotation = 0f
                }
            )
        }
    }
}

/**
 * Phase 226: a single corner scale handle for a held selection. Visible at rest
 * (alpha 1). Dragging accumulates the world-coordinate delta and reports it
 * (for preview) every sample via [onDragUpdate] and once on release via
 * [onDragEnd] — the caller derives scale factors through
 * [com.authorss81.noteflow.services.SelectionTransformPolicy].
 */
@Composable
private fun SelectionCornerHandle(
    modifier: Modifier,
    sizePx: Float,
    accentColor: Color,
    zoomScale: Float,
    corner: com.authorss81.noteflow.services.SelectionTransformPolicy.Corner,
    locked: Boolean,
    onDragUpdate: (worldDx: Float, worldDy: Float) -> Unit,
    onDragEnd: (worldDx: Float, worldDy: Float) -> Unit,
    onDragCancel: () -> Unit
) {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current
    var accDx by remember { mutableFloatStateOf(0f) }
    var accDy by remember { mutableFloatStateOf(0f) }
    val currentOnUpdate by rememberUpdatedState(onDragUpdate)
    val currentOnEnd by rememberUpdatedState(onDragEnd)
    val currentOnCancel by rememberUpdatedState(onDragCancel)
    val currentZoom by rememberUpdatedState(zoomScale)
    val currentLocked by rememberUpdatedState(locked)
    val sizePxDp = with(LocalDensity.current) { sizePx.toDp() }

    Box(
        modifier = modifier
            .size(sizePxDp)
            .pointerInput(corner) {
                detectDragGestures(
                    onDragStart = {
                        accDx = 0f
                        accDy = 0f
                        if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(true, reduceMotion)) {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accDx += dragAmount.x / currentZoom.coerceAtLeast(0.01f)
                        accDy += dragAmount.y / currentZoom.coerceAtLeast(0.01f)
                        currentOnUpdate(accDx, accDy)
                    },
                    onDragEnd = {
                        currentOnEnd(accDx, accDy)
                    },
                    onDragCancel = {
                        currentOnCancel()
                    }
                )
            }
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.OpenWith,
            contentDescription = "Scale selection",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(12.dp)
        )
    }
}

/**
 * Phase 226: rotation handle for a held selection, above its top-centre. Uses
 * the SAME drag math as the canvas items'
 * [com.authorss81.noteflow.services.CanvasItemRotationMath.rotationFromHandleDrag]
 * (the selection has no persistent rotation, so [currentDegrees] is 0 each
 * gesture — the resulting angle IS the absolute rotation to bake into points).
 */
@Composable
private fun SelectionRotationHandle(
    modifier: Modifier,
    sizePx: Float,
    gapPx: Float,
    selectionHalfWidth: Float,
    selectionHalfHeight: Float,
    accentColor: Color,
    zoomScale: Float,
    onDragUpdate: (degrees: Float) -> Unit,
    onDragEnd: (degrees: Float) -> Unit,
    onDragCancel: () -> Unit
) {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current
    val currentOnUpdate by rememberUpdatedState(onDragUpdate)
    val currentOnEnd by rememberUpdatedState(onDragEnd)
    val currentOnCancel by rememberUpdatedState(onDragCancel)
    val currentZoom by rememberUpdatedState(zoomScale)
    val sizePxDp = with(LocalDensity.current) { sizePx.toDp() }
    var lastDegrees by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .size(sizePxDp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        lastDegrees = 0f
                        if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(true, reduceMotion)) {
                            hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val degrees = com.authorss81.noteflow.services.CanvasItemRotationMath.rotationFromHandleDrag(
                            handleCenterRelCardCenterX = 0f,
                            handleCenterRelCardCenterY = -(gapPx + sizePx / 2f + selectionHalfHeight),
                            pointerRelHandleCenterX = change.position.x - sizePx / 2f,
                            pointerRelHandleCenterY = change.position.y - sizePx / 2f,
                            zoom = currentZoom,
                            currentDegrees = 0f
                        )
                        lastDegrees = degrees
                        currentOnUpdate(degrees)
                    },
                    onDragEnd = {
                        currentOnEnd(lastDegrees)
                    },
                    onDragCancel = {
                        currentOnCancel()
                    }
                )
            }
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.RotateRight,
            contentDescription = "Rotate selection",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun EyedropperMagnifierLoupe(
    pos: Offset,
    sampledColor: Color,
    canvasOffset: Offset,
    targetPage: Int,
    rawBitmap: android.graphics.Bitmap?,
    pageWidthPx: Float,
    pageHeightPx: Float,
    pageTopY: Float,
    fallbackColor: Color,
    zoomScale: Float,
    density: androidx.compose.ui.unit.Density
) {
    val gridCells = 5
    val cellDp = 22.dp
    val gridSizeDp = cellDp * gridCells
    val cellPx = with(density) { cellDp.toPx() }
    val mag = 4f
    val step = cellPx / mag
    val loupeDp = gridSizeDp + 22.dp
    val textLight = sampledColor.luminance() > 0.5f

    val paperColor = fallbackColor
    val paperArgb = paperColor.toArgb()
    val bmp = rawBitmap?.takeIf { !it.isRecycled }

    val hexLabel = remember(sampledColor) {
        "#${Integer.toHexString(sampledColor.toArgb()).takeLast(6).uppercase(java.util.Locale.US)}"
    }

    // Place the loupe above-left of the pointer so the finger doesn't cover it.
    val gridSizePx = with(density) { gridSizeDp.toPx() }
    val chipOffsetPx = with(density) { 26.dp.toPx() }
    val offsetPx = with(density) { (gridSizeDp / 2f + 12.dp).toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((pos.x - offsetPx).toInt(), (pos.y - offsetPx - gridSizePx - chipOffsetPx).toInt()) }
            .size(loupeDp)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.12f))
    ) {
        Canvas(modifier = Modifier.size(gridSizeDp)) {
            val center = size.width / 2f
            for (r in 0 until gridCells) {
                for (c in 0 until gridCells) {
                    val worldX = canvasOffset.x + (c - (gridCells - 1) / 2f) * step
                    val worldY = canvasOffset.y + (r - (gridCells - 1) / 2f) * step
                    var cellArgb = paperArgb
                    if (bmp != null) {
                        val px = com.authorss81.noteflow.services.EyedropperSamplingMath.canvasToPagePixel(
                            canvasX = worldX,
                            canvasY = worldY,
                            pageTopY = pageTopY,
                            pageWidthPx = pageWidthPx,
                            pageHeightPx = pageHeightPx,
                            bitmapWidth = bmp.width,
                            bitmapHeight = bmp.height
                        )
                        if (px != null) {
                            try {
                                cellArgb = bmp.getPixel(px.first, px.second)
                            } catch (e: Exception) {
                                // out-of-bounds race — fall back to paper
                            }
                        }
                    }
                    drawRect(
                        color = Color(cellArgb),
                        topLeft = Offset(c * cellPx, r * cellPx),
                        size = Size(cellPx, cellPx)
                    )
                    drawRect(
                        color = Color.Black.copy(alpha = 0.12f),
                        topLeft = Offset(c * cellPx, r * cellPx),
                        size = Size(cellPx, cellPx),
                        style = DrawStrokeStyle(width = 1f)
                    )
                }
            }
            // Center-cell targeting ring so the exact sampled pixel is obvious.
            drawCircle(
                color = Color.White,
                radius = cellPx * 0.48f,
                center = Offset(center, center),
                style = DrawStrokeStyle(width = 3f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.5f),
                radius = cellPx * 0.48f,
                center = Offset(center, center),
                style = DrawStrokeStyle(width = 1.5f)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = gridSizeDp + 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (textLight) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = hexLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (textLight) Color.White else Color.Black,
                    fontSize = 10.sp
                )
            )
        }
    }
}

/**
 * Phase 245: movement-aware long-press wait for the Quick-Color Ring.
 *
 * Waits until the [down] pointer either goes UP (returns TRUE — a normal
 * tap/release) or clearly starts a stroke (returns FALSE — the user is DRAWING,
 * not long-pressing). "Clearly starts a stroke" is
 * [QuickColorRingMath.holdWithinLongPressSlop]: the pointer's displacement from
 * its DOWN position crossed the touch slop. A second pointer pressing in the
 * middle of the hold also returns FALSE, so pinch/undo/redo gestures never get
 * the ring. The caller only opens the ring if this suspends for the FULL
 * long-press window without returning — i.e. the pointer stayed effectively
 * STILL, so neither the stroke path nor a two-finger gesture needs the events.
 *
 * Bonus: because the stroke's first sample is recorded at the ORIGINAL down
 * position, a ring that yields mid-target never drops the stroke start and
 * never leaves a stray dot at a ring anchor.
 */
private suspend fun AwaitPointerEventScope.waitForUpOrSlopMove(
    down: PointerInputChange,
    slopPx: Float
): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null || !change.pressed) return true
        if (event.changes.any { it.id != down.id && it.pressed }) return false
        if (!com.authorss81.noteflow.services.QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = change.position.x,
                pointerY = change.position.y,
                downX = down.position.x,
                downY = down.position.y,
                slopPx = slopPx
            )
        ) return false
    }
}

/**
 * Phase 155: the QUICK-COLOR RING overlay — the radial mini-palette shown while
 * the user long-presses + drags on the canvas. Rendered with a single Canvas
 * positioned exactly at the finger anchor; INSTANT (no animation) per the app's
 * reduce-motion policy. The center disc shows the CURRENT tool color and means
 * "keep current"; the outer band shows the active palette swatches in the same
 * clockwise 12-o'clock layout QuickColorRingMath.ringLayout produces, so the
 * hit-test and the pixels always agree.
 */
@Composable
private fun QuickColorRingOverlay(
    anchor: Offset,
    swatches: List<Color>,
    currentColor: Color,
    selectedIndex: Int,
    visibleSwatchCount: Int
) {
    val outerR = com.authorss81.noteflow.services.QuickColorRingMath.RING_OUTER_RADIUS_PX
    val innerR = com.authorss81.noteflow.services.QuickColorRingMath.RING_INNER_RADIUS_PX
    val midR = (outerR + innerR) / 2f
    val swatchR = com.authorss81.noteflow.services.QuickColorRingMath.SWATCH_RADIUS_PX
    val centers = com.authorss81.noteflow.services.QuickColorRingMath.ringLayout(visibleSwatchCount, anchor.x, anchor.y)
    val underlineColor = if (currentColor.luminance() > 0.5f) Color.Black else Color.White

    Canvas(
        modifier = Modifier
            .offset { IntOffset((anchor.x - outerR).toInt(), (anchor.y - outerR).toInt()) }
            .size(with(androidx.compose.ui.platform.LocalDensity.current) {
                (outerR * 2).toDp()
            })
    ) {
        // Backing disc (dark scrim) so the ring reads over light paper.
        drawCircle(color = Color.Black.copy(alpha = 0.30f), radius = outerR)
        drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = midR)

        drawCircle(
            color = currentColor,
            radius = com.authorss81.noteflow.services.QuickColorRingMath.CENTER_RADIUS_PX
        )
        drawCircle(
            color = underlineColor,
            radius = com.authorss81.noteflow.services.QuickColorRingMath.CENTER_RADIUS_PX,
            style = DrawStrokeStyle(width = if (selectedIndex == com.authorss81.noteflow.services.QuickColorRingMath.CENTER_SLOT) 5f else 2f)
        )

        if (selectedIndex == com.authorss81.noteflow.services.QuickColorRingMath.NOTHING_HIT) {
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = midR,
                style = DrawStrokeStyle(width = 2f)
            )
        }

        for (i in centers.indices) {
            val (cx, cy) = centers[i]
            val isSelected = selectedIndex == i
            if (isSelected) {
                drawCircle(color = Color.White.copy(alpha = 0.95f), radius = swatchR + 5f)
                drawCircle(color = Color.Black.copy(alpha = 0.6f), radius = swatchR + 6f, style = DrawStrokeStyle(width = 2f))
            }
            drawCircle(color = swatches[i % swatches.size], radius = swatchR)
            drawCircle(
                color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.35f),
                radius = swatchR,
                style = DrawStrokeStyle(width = if (isSelected) 3f else 1.5f)
            )
        }
    }
}

private fun DrawScope.drawPaperCard(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    paperColor: Color = Color.White,
    isDarkPaper: Boolean = false,
    pageLabel: String? = null,
    showPageLabel: Boolean = true,
    grainBrush: Brush? = null,
    // Phase 227: deckled paper edge + texture-strength dial. Defaults reproduce
    // the pre-227 card exactly (ROUNDED corners, full-strength grain pass).
    paperEdge: com.authorss81.noteflow.services.PaperEdgePolicy.PaperEdge =
        com.authorss81.noteflow.services.PaperEdgePolicy.PaperEdge.ROUNDED,
    grainScale: Float = 1f
) {
    val borderColor = if (isDarkPaper) Color(0xFF475569) else Color.LightGray.copy(alpha = 0.6f)

    if (paperEdge == com.authorss81.noteflow.services.PaperEdgePolicy.PaperEdge.DECKLED) {
        // Deckled edge: a deterministic wavy sheet (PaperEdgePolicy math, pure
        // JVM) drawn as a single closed silhouette. Fill, grain and border all
        // clip to it so the torn edge reads as a real cut-out; a soft native
        // blurred stroke underneath supplies the sheet's drop.
        val sheet = deckledSheetPath(x, y, width, height, isDarkPaper)
        drawDeckleSheetShadow(x, y, width, height, isDarkPaper)
        clipPath(path = sheet) {
            drawRoundRect(
                color = paperColor,
                topLeft = Offset(x, y),
                size = Size(width, height),
                cornerRadius = CornerRadius.Zero
            )
            drawPaperGrain(grainBrush, x, y, width, height, CornerRadius.Zero, grainScale)
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(x, y),
                size = Size(width, height),
                cornerRadius = CornerRadius.Zero,
                style = DrawStrokeStyle(width = 2f)
            )
        }
    } else {
        val radius = if (paperEdge == com.authorss81.noteflow.services.PaperEdgePolicy.PaperEdge.RECT) {
            CornerRadius(0f, 0f)
        } else {
            CornerRadius(8f, 8f)
        }
        drawRoundRect(
            color = paperColor,
            topLeft = Offset(x, y),
            size = Size(width, height),
            cornerRadius = radius
        )
        // Phase 200 (PERF 3.3): tileable paper-grain noise, REPEAT-tiled by a
        // cached BitmapShader — ONE textured quad per page card, drawn over the
        // flat tint and strictly UNDER everything else (template/background/ink).
        drawPaperGrain(grainBrush, x, y, width, height, radius, grainScale)
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(x, y),
            size = Size(width, height),
            cornerRadius = radius,
            style = DrawStrokeStyle(width = 2f)
        )
    }

    if (showPageLabel) {
        pageLabel?.let { label ->
            drawRoundRect(
                color = if (isDarkPaper) Color(0xFF334155) else Color(0xFF1E293B).copy(alpha = 0.85f),
                topLeft = Offset(x + 16f, y + 16f),
                size = Size(130f, 32f),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x + 28f,
                y + 38f,
                android.graphics.Paint().apply {
                    setColor(android.graphics.Color.WHITE)
                    textSize = 20f
                    isAntiAlias = true
                    isFakeBoldText = true
                }
            )
        }
    }
}

/**
 * Phase 227: one grain pass scaled into the alpha channel ([grainScale] ≤ 1)
 * plus an additive boost draw when the user dials ABOVE the default (the
 * cached tile is per-paper-family, so no per-strength tiles are generated).
 * At the default 50 the scale is exactly 1.0 and the single full-alpha pass is
 * byte-identical to the pre-227 grain draw.
 */
private fun DrawScope.drawPaperGrain(
    grainBrush: Brush?,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    cornerRadius: CornerRadius,
    grainScale: Float
) {
    if (grainBrush == null) return
    val scale = if (grainScale.isFinite()) grainScale.coerceAtLeast(0f) else 0f
    drawRoundRect(
        brush = grainBrush,
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = cornerRadius,
        alpha = scale.coerceIn(0f, 1f)
    )
    if (scale > 1f) {
        drawRoundRect(
            brush = grainBrush,
            topLeft = Offset(x, y),
            size = Size(width, height),
            cornerRadius = cornerRadius,
            alpha = (scale - 1f).coerceIn(0f, 1f)
        )
    }
}

/**
 * Phase 227: the deckled sheet silhouette. Node positions are generated by the
 * pure-JVM table (deterministic per paper family, bounded to
 * PaperEdgePolicy.amplitudePx, corner-faded so the sheet corners stay sharp),
 * then smoothed through the quadratic-midpoint technique — nothing is random,
 * so consecutive pages (same seed) share one stock edge.
 */
private fun DrawScope.deckledSheetPath(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    isDarkPaper: Boolean
): androidx.compose.ui.graphics.Path {
    val ampPx = com.authorss81.noteflow.services.PaperEdgePolicy.amplitudePx(density)
    val seed = com.authorss81.noteflow.services.PaperEdgePolicy.seedFor(isDarkPaper)
    val nodes = com.authorss81.noteflow.services.PaperEdgePolicy.deckleNodes(x, y, width, height, ampPx, seed)
    val midpoints = com.authorss81.noteflow.services.PaperEdgePolicy.smoothedDeckleMidpoints(nodes)
    val path = androidx.compose.ui.graphics.Path()
    if (midpoints.isEmpty()) {
        path.moveTo(x, y)
        return path
    }
    path.moveTo(midpoints[0].first, midpoints[0].second)
    for (i in 1 until midpoints.size) {
        path.lineTo(midpoints[i].first, midpoints[i].second)
    }
    path.close()
    return path
}

/**
 * Phase 227: soft drop under the deckled sheet (blurred dark stroke slightly
 * down-right). Same BlurMaskFilter technique as the phase-213 stroke shadows;
 * pitch-dark sheets use a subtler shadow than light stock.
 */
private fun DrawScope.drawDeckleSheetShadow(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    isDarkPaper: Boolean
) {
    val ampPx = com.authorss81.noteflow.services.PaperEdgePolicy.amplitudePx(density)
    val seed = com.authorss81.noteflow.services.PaperEdgePolicy.seedFor(isDarkPaper)
    val offset = 3f * density
    val nodes = com.authorss81.noteflow.services.PaperEdgePolicy.deckleNodes(x, y, width, height, ampPx, seed)
    val midpoints = com.authorss81.noteflow.services.PaperEdgePolicy.smoothedDeckleMidpoints(nodes)
    if (midpoints.isEmpty()) return
    val native = android.graphics.Path()
    native.moveTo(midpoints[0].first + offset, midpoints[0].second + offset)
    for (i in 1 until midpoints.size) {
        native.lineTo(midpoints[i].first + offset, midpoints[i].second + offset)
    }
    native.close()
    val blurPx = com.authorss81.noteflow.services.PaperEdgePolicy.DECKLE_SHADOW_NOMINAL_WIDTH_PX
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = ampPx * 2f + blurPx * 0.8f
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = android.graphics.Color.argb(
            if (isDarkPaper) 42 else 58,
            0, 0, 0
        )
        maskFilter = android.graphics.BlurMaskFilter(
            blurPx,
            android.graphics.BlurMaskFilter.Blur.NORMAL
        )
    }
    drawContext.canvas.nativeCanvas.drawPath(native, paint)
}

/** Phase 219: per-template-type visual overrides read from SettingsManager. */
private data class TemplateOverrides(
    val lineSpacingDp: Float? = null,
    val gridOpacity: Float? = null,
    val dotRadiusPx: Float? = null,
    val accentColorHex: String? = null
) {
    companion object {
        val DEFAULT = TemplateOverrides()
    }
}

private fun DrawScope.drawPaperTemplate(
    template: String,
    xOffset: Float,
    yOffset: Float,
    width: Float,
    height: Float,
    isDarkPaper: Boolean = false,
    paperTexture: ImageBitmap? = null,
    templateOverrides: TemplateOverrides = TemplateOverrides.DEFAULT
) {
    // Phase 07: tiled custom paper texture pack (drawn first so the classic
    // template grid/lines overlay it when both are configured).
    if (paperTexture != null) {
        val tw = paperTexture.width.toFloat()
        val th = paperTexture.height.toFloat()
        if (tw > 0f && th > 0f) {
            var ty = yOffset
            while (ty < yOffset + height) {
                var tx = xOffset
                while (tx < xOffset + width) {
                    drawImage(
                        image = paperTexture,
                        dstOffset = IntOffset(tx.toInt(), ty.toInt()),
                        dstSize = IntSize(tw.toInt(), th.toInt())
                    )
                    tx += tw
                }
                ty += th
            }
        }
    }
    val gridAlpha = templateOverrides.gridOpacity
        ?: (if (isDarkPaper) 0.35f else 0.22f)
    val baseGridColor = templateOverrides.accentColorHex?.let {
        try { Color(android.graphics.Color.parseColor(it)) } catch (_: Exception) { Color.Gray }
    } ?: if (isDarkPaper) Color(0xFF94A3B8) else Color.Gray
    val gridColor = baseGridColor.copy(alpha = gridAlpha)
    when (template) {
        "lined" -> {
            val lineSpacing = (templateOverrides.lineSpacingDp ?: 36f).dp.toPx()
            var y = yOffset + lineSpacing
            while (y < yOffset + height) {
                drawLine(gridColor, Offset(xOffset, y), Offset(xOffset + width, y), strokeWidth = 1.5f)
                y += lineSpacing
            }
        }
        "grid" -> {
            val gridSize = (templateOverrides.lineSpacingDp ?: 28f).dp.toPx()
            var x = xOffset + gridSize
            while (x < xOffset + width) {
                drawLine(gridColor, Offset(x, yOffset), Offset(x, yOffset + height), strokeWidth = 1f)
                x += gridSize
            }
            var y = yOffset + gridSize
            while (y < yOffset + height) {
                drawLine(gridColor, Offset(xOffset, y), Offset(xOffset + width, y), strokeWidth = 1f)
                y += gridSize
            }
        }
        "dots" -> {
            val dotSpacing = (templateOverrides.lineSpacingDp ?: 28f).dp.toPx()
            val dotRadius = templateOverrides.dotRadiusPx ?: 2f
            var x = xOffset + dotSpacing
            while (x < xOffset + width) {
                var y = yOffset + dotSpacing
                while (y < yOffset + height) {
                    drawCircle(gridColor, radius = dotRadius, center = Offset(x, y))
                    y += dotSpacing
                }
                x += dotSpacing
            }
        }
        "cross_grid" -> {
            val crossSpacing = (templateOverrides.lineSpacingDp ?: 28f).dp.toPx()
            val dotRadius = templateOverrides.dotRadiusPx ?: 2f
            val faintGridColor = gridColor.copy(alpha = gridAlpha * 0.5f)
            var x = xOffset + crossSpacing
            while (x < xOffset + width) {
                drawLine(faintGridColor, Offset(x, yOffset), Offset(x, yOffset + height), strokeWidth = 0.5f)
                x += crossSpacing
            }
            var y = yOffset + crossSpacing
            while (y < yOffset + height) {
                drawLine(faintGridColor, Offset(xOffset, y), Offset(xOffset + width, y), strokeWidth = 0.5f)
                y += crossSpacing
            }
            var dx = xOffset + crossSpacing
            while (dx < xOffset + width) {
                var dy = yOffset + crossSpacing
                while (dy < yOffset + height) {
                    drawCircle(gridColor, radius = dotRadius, center = Offset(dx, dy))
                    dy += crossSpacing
                }
                dx += crossSpacing
            }
        }
        "cornell" -> {
            val accentColor = if (isDarkPaper) Color(0xFF38BDF8).copy(alpha = 0.5f) else Color(0xFF0284C7).copy(alpha = 0.4f)
            val lineSpacing = 32.dp.toPx()
            val headerY = yOffset + 100.dp.toPx()
            val summaryY = yOffset + height - 140.dp.toPx()
            val cueX = xOffset + width * 0.30f

            drawLine(accentColor, Offset(xOffset, headerY), Offset(xOffset + width, headerY), strokeWidth = 3.5f)
            drawLine(accentColor, Offset(xOffset, summaryY), Offset(xOffset + width, summaryY), strokeWidth = 3.5f)
            drawLine(accentColor, Offset(cueX, headerY), Offset(cueX, summaryY), strokeWidth = 2.5f)

            var y = headerY + lineSpacing
            while (y < summaryY) {
                drawLine(gridColor, Offset(xOffset, y), Offset(xOffset + width, y), strokeWidth = 1f)
                y += lineSpacing
            }
            var sumY = summaryY + lineSpacing
            while (sumY < yOffset + height) {
                drawLine(gridColor, Offset(xOffset, sumY), Offset(xOffset + width, sumY), strokeWidth = 1f)
                sumY += lineSpacing
            }
        }
        "meeting" -> {
            val accentColor = if (isDarkPaper) Color(0xFFA855F7).copy(alpha = 0.5f) else Color(0xFF7E22CE).copy(alpha = 0.4f)
            val lineSpacing = 30.dp.toPx()
            val headerY = yOffset + 120.dp.toPx()
            val splitX = xOffset + width * 0.58f

            drawRect(accentColor.copy(alpha = 0.1f), topLeft = Offset(xOffset + 16.dp.toPx(), yOffset + 16.dp.toPx()), size = androidx.compose.ui.geometry.Size(width - 32.dp.toPx(), 88.dp.toPx()))
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), yOffset + 16.dp.toPx()), Offset(xOffset + width - 16.dp.toPx(), yOffset + 16.dp.toPx()), strokeWidth = 2f)
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), yOffset + 104.dp.toPx()), Offset(xOffset + width - 16.dp.toPx(), yOffset + 104.dp.toPx()), strokeWidth = 2f)

            drawLine(accentColor, Offset(splitX, headerY), Offset(splitX, yOffset + height - 16.dp.toPx()), strokeWidth = 2.5f)

            var y = headerY + lineSpacing
            while (y < yOffset + height - 16.dp.toPx()) {
                drawLine(gridColor, Offset(xOffset + 16.dp.toPx(), y), Offset(splitX - 12.dp.toPx(), y), strokeWidth = 1f)
                y += lineSpacing
            }

            var ay = headerY + lineSpacing
            while (ay < yOffset + height - 16.dp.toPx()) {
                drawRect(accentColor, topLeft = Offset(splitX + 16.dp.toPx(), ay - 14.dp.toPx()), size = androidx.compose.ui.geometry.Size(14.dp.toPx(), 14.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                drawLine(gridColor, Offset(splitX + 38.dp.toPx(), ay), Offset(xOffset + width - 16.dp.toPx(), ay), strokeWidth = 1f)
                ay += lineSpacing
            }
        }
        "todo" -> {
            val accentColor = if (isDarkPaper) Color(0xFF10B981).copy(alpha = 0.5f) else Color(0xFF059669).copy(alpha = 0.4f)
            val rowHeight = 36.dp.toPx()
            val topHeaderY = yOffset + 70.dp.toPx()
            val bottomNotesY = yOffset + height - 160.dp.toPx()
            val col2X = xOffset + width * 0.5f

            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), topHeaderY), Offset(xOffset + width - 16.dp.toPx(), topHeaderY), strokeWidth = 3f)
            drawLine(accentColor, Offset(col2X, topHeaderY), Offset(col2X, bottomNotesY), strokeWidth = 2f)
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), bottomNotesY), Offset(xOffset + width - 16.dp.toPx(), bottomNotesY), strokeWidth = 3f)

            var y1 = topHeaderY + rowHeight
            while (y1 < bottomNotesY) {
                drawRect(accentColor, topLeft = Offset(xOffset + 20.dp.toPx(), y1 - 18.dp.toPx()), size = androidx.compose.ui.geometry.Size(16.dp.toPx(), 16.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                drawLine(gridColor, Offset(xOffset + 44.dp.toPx(), y1), Offset(col2X - 12.dp.toPx(), y1), strokeWidth = 1f)
                y1 += rowHeight
            }

            var y2 = topHeaderY + rowHeight
            while (y2 < bottomNotesY) {
                drawRect(accentColor, topLeft = Offset(col2X + 16.dp.toPx(), y2 - 18.dp.toPx()), size = androidx.compose.ui.geometry.Size(16.dp.toPx(), 16.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                drawLine(gridColor, Offset(col2X + 40.dp.toPx(), y2), Offset(xOffset + width - 20.dp.toPx(), y2), strokeWidth = 1f)
                y2 += rowHeight
            }

            var ny = bottomNotesY + rowHeight
            while (ny < yOffset + height) {
                drawLine(gridColor, Offset(xOffset + 20.dp.toPx(), ny), Offset(xOffset + width - 20.dp.toPx(), ny), strokeWidth = 1f)
                ny += rowHeight
            }
        }
        "kanban" -> {
            val accentColor = if (isDarkPaper) Color(0xFF6366F1).copy(alpha = 0.5f) else Color(0xFF4F46E5).copy(alpha = 0.4f)
            val headerY = yOffset + 60.dp.toPx()
            val col1X = xOffset + width * 0.33f
            val col2X = xOffset + width * 0.66f
            
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), headerY), Offset(xOffset + width - 16.dp.toPx(), headerY), strokeWidth = 3f)
            drawLine(accentColor, Offset(col1X, headerY), Offset(col1X, yOffset + height - 20.dp.toPx()), strokeWidth = 2f)
            drawLine(accentColor, Offset(col2X, headerY), Offset(col2X, yOffset + height - 20.dp.toPx()), strokeWidth = 2f)
            
            var y = headerY + 40.dp.toPx()
            while (y < yOffset + height - 20.dp.toPx()) {
                drawLine(gridColor, Offset(xOffset + 16.dp.toPx(), y), Offset(col1X - 8.dp.toPx(), y), strokeWidth = 1f)
                drawLine(gridColor, Offset(col1X + 8.dp.toPx(), y), Offset(col2X - 8.dp.toPx(), y), strokeWidth = 1f)
                drawLine(gridColor, Offset(col2X + 8.dp.toPx(), y), Offset(xOffset + width - 16.dp.toPx(), y), strokeWidth = 1f)
                y += 60.dp.toPx()
            }
        }
        "music" -> {
            val staffSpacing = 10.dp.toPx()
            val groupSpacing = 40.dp.toPx()
            var currentY = yOffset + 50.dp.toPx()
            
            while (currentY + 4 * staffSpacing < yOffset + height - 30.dp.toPx()) {
                for (lineIdx in 0 until 5) {
                    val lineY = currentY + lineIdx * staffSpacing
                    drawLine(gridColor, Offset(xOffset + 24.dp.toPx(), lineY), Offset(xOffset + width - 24.dp.toPx(), lineY), strokeWidth = 1.2f)
                }
                currentY += 4 * staffSpacing + groupSpacing
            }
        }
        "storyboard" -> {
            val accentColor = if (isDarkPaper) Color(0xFF60A5FA).copy(alpha = 0.45f) else Color(0xFF3B82F6).copy(alpha = 0.35f)
            val panelGap = 16.dp.toPx()
            val panelH = (height - 4 * panelGap) / 3f
            val panelW = width - 32.dp.toPx()
            val panelX = xOffset + 16.dp.toPx()

            for (i in 0 until 3) {
                val panelY = yOffset + panelGap + i * (panelH + panelGap)
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(panelX, panelY),
                    size = androidx.compose.ui.geometry.Size(panelW, panelH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                )
                val captionY = panelY + panelH - 28.dp.toPx()
                drawLine(gridColor, Offset(panelX + 12.dp.toPx(), captionY), Offset(panelX + panelW - 12.dp.toPx(), captionY), strokeWidth = 1f)
            }
        }
        // Phase 223: drafting grids — perspective (one/two point) and isometric.
        // All line families come from PerspectiveGridPolicy (pure JVM, unit-tested
        // vanishing-point/isometric math); this pass only converts its returned
        // (start, end) pairs into drawLine calls with the shared gridColor.
        "perspective_1pt" -> {
            // Phase 223 review fix: honour the phase-219 line-spacing override
            // (spacing chips 24/28/36dp) — the drafting line families scale their
            // step fractions by spacingDp/28 so denser/sparser spacing actually
            // takes effect instead of being a silent no-op.
            val stepFactor = (templateOverrides.lineSpacingDp ?: 28f) / 28f
            val g = com.authorss81.noteflow.services.PerspectiveGridPolicy.onePoint(width, height)
            for (l in com.authorss81.noteflow.services.PerspectiveGridPolicy.depthLines(width, height, g.horizonY, stepFactor)) {
                drawLine(gridColor, Offset(l.first.first + xOffset, l.first.second + yOffset), Offset(l.second.first + xOffset, l.second.second + yOffset), strokeWidth = 1f)
            }
            for (l in com.authorss81.noteflow.services.PerspectiveGridPolicy.onePointRays(g, stepFactor)) {
                drawLine(gridColor, Offset(l.first.first + xOffset, l.first.second + yOffset), Offset(l.second.first + xOffset, l.second.second + yOffset), strokeWidth = 1f)
            }
            // Emphasise the horizon line so the vanishing row reads clearly.
            drawLine(gridColor.copy(alpha = gridAlpha), Offset(xOffset, g.horizonY + yOffset), Offset(xOffset + width, g.horizonY + yOffset), strokeWidth = 1.5f)
        }
        "perspective_2pt" -> {
            val stepFactor = (templateOverrides.lineSpacingDp ?: 28f) / 28f
            val g = com.authorss81.noteflow.services.PerspectiveGridPolicy.twoPoint(width, height)
            for (l in com.authorss81.noteflow.services.PerspectiveGridPolicy.depthLines(width, height, g.horizonY, stepFactor)) {
                drawLine(gridColor, Offset(l.first.first + xOffset, l.first.second + yOffset), Offset(l.second.first + xOffset, l.second.second + yOffset), strokeWidth = 1f)
            }
            for (l in com.authorss81.noteflow.services.PerspectiveGridPolicy.twoPointRays(g, stepFactor)) {
                val d = kotlin.math.abs(l.first.first - l.second.first) + kotlin.math.abs(l.first.second - l.second.second)
                if (d > 1f) {
                    drawLine(gridColor, Offset(l.first.first + xOffset, l.first.second + yOffset), Offset(l.second.first + xOffset, l.second.second + yOffset), strokeWidth = 1f)
                }
            }
            drawLine(gridColor.copy(alpha = gridAlpha), Offset(xOffset, g.horizonY + yOffset), Offset(xOffset + width, g.horizonY + yOffset), strokeWidth = 1.5f)
        }
        "isometric" -> {
            val stepFactor = (templateOverrides.lineSpacingDp ?: 28f) / 28f
            val g = com.authorss81.noteflow.services.PerspectiveGridPolicy.isometric(width, height)
            for (l in com.authorss81.noteflow.services.PerspectiveGridPolicy.isometricDiagonals(g, stepFactor)) {
                drawLine(gridColor, Offset(l.first.first + xOffset, l.first.second + yOffset), Offset(l.second.first + xOffset, l.second.second + yOffset), strokeWidth = 1f)
            }
        }
    }
}

/**
 * Phase 178: renders the per-page reference-image underlay (dim, below the ink).
 *
 * Drawn AFTER the paper/template and BEFORE the background bitmap + stroke pass,
 * so strokes render OVER it and can never modify it (it is a plain DrawScope
 * drawImage in the canvas body — it has no inking target, no touch target and no
 * layer bitmap). Opacity is range-gated by ReferenceImagePolicy so a corrupted
 * stored value can never render a full-strength underlay. [pageTopY] offsets the
 * stored page-relative y into the paginated world rect.
 */
private fun DrawScope.drawReferenceImage(
    image: ImageBitmap?,
    opacity: Float,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    pageTopY: Float = 0f
) {
    if (image == null) return
    if (width <= 0f || height <= 0f) return
    val alpha = com.authorss81.noteflow.services.ReferenceImagePolicy.clampOpacity(opacity)
    drawImage(
        image = image,
        dstOffset = IntOffset(x.toInt(), (y + pageTopY).toInt()),
        dstSize = IntSize(width.toInt(), height.toInt()),
        alpha = alpha
    )
}

private fun convertToInkStroke(stroke: Stroke, context: android.content.Context? = null, vibrancy: Float = 0f): InkStroke? {
    if (stroke.points.isEmpty()) return null
    if (stroke.tool == StrokeTool.CALLIGRAPHIC || stroke.tool == StrokeTool.CHISEL_MARKER) return null
    try {
        val family = ProtobufBrushLoader.getBrushFamilyForTool(context, stroke.tool)
        val isSolidTool = (stroke.tool == StrokeTool.PEN ||
                          stroke.tool == StrokeTool.FOUNTAIN_PEN ||
                          stroke.tool == StrokeTool.FINELINER ||
                          stroke.tool == StrokeTool.CALLIGRAPHIC ||
                          stroke.tool == StrokeTool.CHISEL_MARKER ||
                          stroke.tool == StrokeTool.DOTTED ||
                          stroke.tool == StrokeTool.LINE ||
                          stroke.tool.isShapeTool) &&
                          !com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(stroke.tool)

        // Phase 19: render-time vibrancy applies to the advanced ink path too
        // (pure render; stored colorInt is never touched).
        val renderColorInt = if (vibrancy > 0f) {
            com.authorss81.noteflow.services.ColorVibrancy.boostColorInt(stroke.colorInt, vibrancy)
        } else {
            stroke.colorInt
        }
        val brushColorInt = if (isSolidTool) Color(renderColorInt).copy(alpha = 1.0f).toArgb() else renderColorInt
        val brush = InkBrush.createWithColorIntArgb(
            family,
            brushColorInt,
            stroke.width,
            0.1f
        )
        val inputBatch = MutableStrokeInputBatch()
        stroke.points.forEachIndexed { index, pt ->
            val tMs = pt.timestampMs ?: (index * 5L)
            inputBatch.add(
                InputToolType.STYLUS,
                pt.x,
                pt.y,
                tMs,
                pt.pressure ?: 0.5f,
                pt.tilt ?: 0f,
                0f
            )
        }
        return InkStroke(brush, inputBatch.toImmutable())
    } catch (e: Throwable) {
        return null
    }
}

// Phase 225 (review fix): upper bound on a reference photo's on-disk size before
// the eye-dropper's UNCACHED sampling fallback (dims not threaded in) bails to
// the layer/paper path instead of doing a synchronous bounds-only decode of a
// large file on the UI thread. The cached-dims hot path passes this check because
// the file's dimensions were already resolved offline once at read time.
private const val MAX_REFERENCE_SAMPLING_FILE_BYTES: Long = 8L * 1024L * 1024L

private val layerBlendPorterDuff: Map<String, android.graphics.PorterDuff.Mode> = mapOf(
    "NORMAL" to android.graphics.PorterDuff.Mode.SRC_OVER,
    "MULTIPLY" to android.graphics.PorterDuff.Mode.MULTIPLY,
    "SCREEN" to android.graphics.PorterDuff.Mode.SCREEN,
    "OVERLAY" to android.graphics.PorterDuff.Mode.OVERLAY,
    "DARKEN" to android.graphics.PorterDuff.Mode.DARKEN,
    "LIGHTEN" to android.graphics.PorterDuff.Mode.LIGHTEN
)

private val layerBlendApiQ: Map<String, android.graphics.BlendMode>? by lazy {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        mapOf(
            "NORMAL" to android.graphics.BlendMode.SRC_OVER,
            "MULTIPLY" to android.graphics.BlendMode.MULTIPLY,
            "SCREEN" to android.graphics.BlendMode.SCREEN,
            "OVERLAY" to android.graphics.BlendMode.OVERLAY,
            "DARKEN" to android.graphics.BlendMode.DARKEN,
            "LIGHTEN" to android.graphics.BlendMode.LIGHTEN,
            "COLOR_DODGE" to android.graphics.BlendMode.COLOR_DODGE,
            "COLOR_BURN" to android.graphics.BlendMode.COLOR_BURN,
            "HARD_LIGHT" to android.graphics.BlendMode.HARD_LIGHT,
            "SOFT_LIGHT" to android.graphics.BlendMode.SOFT_LIGHT,
            "DIFFERENCE" to android.graphics.BlendMode.DIFFERENCE,
            "EXCLUSION" to android.graphics.BlendMode.EXCLUSION
        )
    } else {
        null
    }
}

private fun android.graphics.Paint.applyLayerBlend(modeStr: String) {
    val blendApiQ = layerBlendApiQ
    if (blendApiQ != null) {
        blendMode = blendApiQ[modeStr.uppercase()] ?: android.graphics.BlendMode.SRC_OVER
    } else {
        xfermode = android.graphics.PorterDuffXfermode(
            layerBlendPorterDuff[modeStr.uppercase()] ?: android.graphics.PorterDuff.Mode.SRC_OVER
        )
    }
}

private fun DrawScope.drawCompositedLayersStrokes(
    strokes: List<Stroke>,
    previewStroke: Stroke?,
    layers: List<com.authorss81.noteflow.data.model.LayerEntity>,
    activeLayerId: String?,
    offsetY: Float,
    isDarkPaper: Boolean,
    inkRenderer: CanvasStrokeRenderer?,
    layerBitmapCache: com.authorss81.noteflow.ui.components.LayerBitmapLruCache? = null,
    canvasDrawScope: androidx.compose.ui.graphics.drawscope.CanvasDrawScope? = null,
    density: androidx.compose.ui.unit.Density? = null,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection? = null,
    pageIdx: Int = 0,
    pageWidth: Float = 0f,
    pageHeight: Float = 0f,
    pageTopY: Float = 0f,
    graphicsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    wetMixingEffect: AgslShaders.WetMixingEffect? = null,
    currentTool: StrokeTool? = null,
    currentColor: Color? = null,
    currentWidth: Float = 1f,
    activePoints: List<PointF> = emptyList(),
    activeStart: PointF? = null,
    wetCanvasEngine: com.authorss81.noteflow.services.WetCanvasEngine? = null,
    wetBrushEngine: com.authorss81.noteflow.services.WetBrushEngine? = null,
    gpuWetBrushesEnabled: Boolean = true,
    // Phase 213: per-stroke drop-shadow gate = the resolved user setting
    // (low-end tier policy applied upstream by EditorScreen's device effect).
    strokeShadowEnabled: Boolean = false,
    symmetryMode: SymmetryMode = SymmetryMode.OFF,
    symmetryCenterX: Float = 0f,
    symmetryCenterY: Float = 0f,
    strokeRenderOpts: StrokeRenderOpts = StrokeRenderOpts(),
    liveStrokeSeed: Float = 0f,
    vibrancyBoost: Float = 0f,
    // Phase 220: pro brush controls.
    blenderStrengthPercent: Int = 85,
    scatterAmountPercent: Int = 0,
    // Phase 227: texture-strength dial threaded into the per-layer wet pass.
    paperTextureStrength: Int = com.authorss81.noteflow.services.PaperTextureStrengthPolicy.DEFAULT,
    // Phase 222: per-layer alpha-lock (set of layer ids with alpha-lock on).
    alphaLockLayerIds: Set<String> = emptySet(),
    // Phase 222: per-layer clipping mask (set of layer ids with clipping mask on).
    clippingMaskLayerIds: Set<String> = emptySet()
) {
    // capture time (see SymmetryCommitPolicy): a stroke drawn while a mode was
    // active persisted BOTH rows (original + mirrored twin), so re-mirroring
    // committed strokes here retroactively duplicated old ink on enable and
    // "deleted" it on disable — the user-reported flip-flop. The ONLY remaining
    // view-time mirror is the LIVE in-progress preview below, so the user still
    // sees the symmetric effect while drawing, before lift-off.
    fun DrawScope.drawCommittedStrokeOnce(stroke: Stroke, offsetY: Float) {
        drawSingleStroke(stroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost, shadowEnabled = strokeShadowEnabled, scatterAmountPercent = scatterAmountPercent)
    }

    // Live preview only: draws [stroke] plus its mirror when [sMode] is active.
    // TEXT strokes are never mirrored (text cannot sensibly reflect).
    fun DrawScope.drawLivePreviewWithSymmetry(stroke: Stroke, offsetY: Float, sMode: SymmetryMode, centerX: Float = symmetryCenterX, centerY: Float = symmetryCenterY) {
        drawSingleStroke(stroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost, shadowEnabled = strokeShadowEnabled, scatterAmountPercent = scatterAmountPercent)
        if (sMode != SymmetryMode.OFF && stroke.tool != StrokeTool.TEXT) {
            drawSingleStroke(
                stroke.copy(
                    points = stroke.points.map { p ->
                        val m = SymmetryHelper.mirrorPoint(p.x, p.y, sMode, centerX, centerY)
                        p.copy(x = m.x, y = m.y)
                    },
                    start = stroke.start?.let { p ->
                        val m = SymmetryHelper.mirrorPoint(p.x, p.y, sMode, centerX, centerY)
                        p.copy(x = m.x, y = m.y)
                    },
                    end = stroke.end?.let { p ->
                        val m = SymmetryHelper.mirrorPoint(p.x, p.y, sMode, centerX, centerY)
                        p.copy(x = m.x, y = m.y)
                    }
                ),
                offsetY,
                isDarkPaper = isDarkPaper,
                inkRenderer = inkRenderer,
                renderOpts = strokeRenderOpts,
                vibrancy = vibrancyBoost,
                shadowEnabled = strokeShadowEnabled,
                scatterAmountPercent = scatterAmountPercent
            )
        }
    }

    if (layers.isEmpty()) {
        // No layers yet (e.g. first frames before the page's layer list loads, or
        // a page whose layer row was never created). Phase 08: route committed
        // strokes through the same page-local bitmap cache as the layer path so we
        // do NOT re-vectorize every committed stroke every frame. The cache is
        // keyed per page (phase 203, review fixes: the symmetry mode is OUT of
        // the key — committed ink renders identically in every mode) and cleared
        // whenever `strokes` changes (the LaunchedEffect(strokes, layers) above),
        // so this stays pixel-identical.
        if (layerBitmapCache != null && canvasDrawScope != null && density != null && layoutDirection != null &&
            pageWidth > 0f && pageHeight > 0f
        ) {
            val defaultLayerId = "layer_default"
            // Phase 213: the shadow flag is IN the key — toggling "Paper
            // elevation" (or a tier change) orphans the old rasters instead of
            // serving stale flat/shadowed ink.
            val cacheKey = "${pageIdx}_${defaultLayerId}_v${vibrancyBoost}_s${if (strokeShadowEnabled) 1 else 0}"
            val strokesHash = strokes.hashCode()
            var cache = layerBitmapCache.get(cacheKey)
            val pw = pageWidth.toInt().coerceAtLeast(1)
            val ph = pageHeight.toInt().coerceAtLeast(1)
            if (cache == null || cache.bitmap.width != pw || cache.bitmap.height != ph) {
                val androidBmp = com.authorss81.noteflow.utils.BitmapPool.acquire(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
                val bmp = androidBmp.asImageBitmap()
                cache = com.authorss81.noteflow.ui.components.LayerBitmapCache(bmp, androidx.compose.ui.graphics.Canvas(bmp))
                // R2-b2b4-DOS-02 (phase-150): put() evicts least-recently-used
                // entries (released to the pool) when the resident byte budget is
                // busted; it also releases any bitmap already cached at this key.
                layerBitmapCache.put(cacheKey, cache)
            }

            if (cache.hash != strokesHash || cache.hash == 0) {
                cache.canvas.nativeCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                canvasDrawScope.draw(
                    density = density,
                    layoutDirection = layoutDirection,
                    canvas = cache.canvas,
                    size = androidx.compose.ui.geometry.Size(pageWidth, pageHeight)
                ) {
                    for (stroke in strokes) {
                        // Phase 203: committed strokes render ONCE — the mirrored
                        // twin is a real stroke row baked at capture time (world
                        // coordinates; see SymmetryCommitPolicy), so no view-time
                        // second pass happens here anymore.
                        drawCommittedStrokeOnce(stroke, offsetY - pageTopY)
                    }
                }
                cache.hash = strokesHash
            }

            drawContext.canvas.nativeCanvas.drawBitmap(cache.bitmap.asAndroidBitmap(), 0f, pageTopY, null)
            if (previewStroke != null) {
                drawLivePreviewWithSymmetry(previewStroke, offsetY, symmetryMode)
            }
        } else {
            for (stroke in strokes) {
                drawCommittedStrokeOnce(stroke, offsetY)
            }
            if (previewStroke != null) {
                drawLivePreviewWithSymmetry(previewStroke, offsetY, symmetryMode)
            }
        }
        return
    }

    val sortedLayers = layers.sortedBy { it.zOrder }
    val layerIds = sortedLayers.map { it.id }.toSet()
    val firstLayerId = sortedLayers.firstOrNull()?.id ?: "layer_default"
    val strokesByLayer = strokes.groupBy { stroke ->
        val lid = stroke.layerId
        if (lid != null && lid in layerIds) lid else firstLayerId
    }

    // Phase-201 REVIEW FIX (LOW): the wet carrier RenderNode is a SINGLE reusable
    // instance (AgslShaders.WetMixingEffect.renderNode), and Canvas.drawRenderNode
    // stores a live REFERENCE to it — recording into the node again would
    // retroactively rewrite any earlier composite of the same node in this frame.
    // Only one wet pass per frame may claim it; every other layer takes the plain
    // path. (Today only the preview-host layer can even reach the pass, so this
    // is an explicit guard, not a behavior change.)
    var gpuWetCarrierClaimed = false

    // Phase 222: previous layer bitmap for clipping-mask (DST_IN) compositing.
    var prevLayerBitmap: android.graphics.Bitmap? = null

    for (layer in sortedLayers) {
        if (!layer.visible) continue

        val layerStrokes = strokesByLayer[layer.id] ?: emptyList()
        val isPreviewOnThisLayer = previewStroke != null && (activeLayerId ?: "layer_default") == layer.id

        if (layerStrokes.isEmpty() && !isPreviewOnThisLayer) continue

        val isWetTool = currentTool != null && com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(currentTool!!)
        val isWetLayer = (isWetTool && isPreviewOnThisLayer) || layerStrokes.any { com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(it.tool) }
        // Gate the wet pass on an ACTIVE stroke on this layer: the shader's
        // renderEffect is only meaningful while a brush is moving, and the dirty
        // scoping below only covers that region. When idle (no preview on this
        // layer) the wet layer renders through the normal path — pixel-identical
        // but with ZERO shader/saveLayer work instead of a full-page per-frame
        // offscreen passes (phase-04 audit item 3).
        // Phase 201 (PERF 2.7): the tier gate reads ShaderCapabilityHelper (the
        // single decision table): AGSL RuntimeShader→RenderEffect compositing is
        // API 33+ only; API 26-32 fall through to the vector/CPU paths below.
        val useAgslWetMixing = ShaderCapabilityHelper.isAgslSupported &&
                gpuWetBrushesEnabled &&
                graphicsLayer != null &&
                wetBrushEngine != null &&
                !wetBrushEngine.useVectorFallback &&
                symmetryMode == SymmetryMode.OFF &&
                isWetLayer &&
                isPreviewOnThisLayer

        if (useAgslWetMixing && graphicsLayer != null && wetBrushEngine != null && !gpuWetCarrierClaimed) {
            gpuWetCarrierClaimed = true
            drawWetLayerPass(
                layerStrokes = layerStrokes,
                previewStroke = previewStroke,
                layer = layer,
                isPreviewOnThisLayer = isPreviewOnThisLayer,
                offsetY = pageTopY,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                isDarkPaper = isDarkPaper,
                inkRenderer = inkRenderer,
                graphicsLayer = graphicsLayer,
                wetMixingEffect = wetMixingEffect,
                currentTool = currentTool ?: StrokeTool.PEN,
                currentColor = currentColor ?: Color.Black,
                currentWidth = currentWidth,
                activePoints = activePoints,
                activeStart = activeStart,
                wetCanvasEngine = wetCanvasEngine ?: com.authorss81.noteflow.services.WetCanvasEngine(),
                wetBrushEngine = wetBrushEngine,
                canvasDrawScope = canvasDrawScope,
                density = density,
                layoutDirection = layoutDirection,
                strokeRenderOpts = strokeRenderOpts,
                liveStrokeSeed = liveStrokeSeed,
                vibrancyBoost = vibrancyBoost,
                strokeShadowEnabled = strokeShadowEnabled,
                blenderStrengthPercent = blenderStrengthPercent,
                scatterAmountPercent = scatterAmountPercent,
                paperTextureStrength = paperTextureStrength
            )
            continue
        }

        // Phase 213: `_s<flag>` rides in the key for the same reason as the
        // no-layers path above — a shadow-toggle change must invalidate rasters.
        val cacheKey = "${pageIdx}_${layer.id}_v${vibrancyBoost}_s${if (strokeShadowEnabled) 1 else 0}"
        val strokesHash = layerStrokes.hashCode()

        // Phase 203 (review fixes): symmetry is fully OUT of the committed-layer
        // cache key — committed strokes are baked rows that render identically in
        // every mode, so a toggle must NOT invalidate these bitmaps anymore.
        // (LayerRenderBudgetPolicy.pageKeyOf keys off the leading page token and
        // is unaffected by the shorter format.)
        if (layerBitmapCache != null && canvasDrawScope != null && density != null && layoutDirection != null && pageWidth > 0f && pageHeight > 0f) {
            var cache = layerBitmapCache.get(cacheKey)
            val pw = pageWidth.toInt().coerceAtLeast(1)
            val ph = pageHeight.toInt().coerceAtLeast(1)
            if (cache == null || cache.bitmap.width != pw || cache.bitmap.height != ph) {
                // R2-b2b4-DOS-02 (phase-150): the LRU put releases a same-key
                // stale bitmap AND evicts least-recently-used entries back to the
                // pool when the resident byte budget would be exceeded.
                val androidBmp = com.authorss81.noteflow.utils.BitmapPool.acquire(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
                val bmp = androidBmp.asImageBitmap()
                cache = com.authorss81.noteflow.ui.components.LayerBitmapCache(bmp, androidx.compose.ui.graphics.Canvas(bmp))
                layerBitmapCache.put(cacheKey, cache)
            }
            
            if (cache.hash != strokesHash || cache.hash == 0) {
                cache.canvas.nativeCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                canvasDrawScope.draw(
                    density = density,
                    layoutDirection = layoutDirection,
                    canvas = cache.canvas,
                    size = androidx.compose.ui.geometry.Size(pageWidth, pageHeight)
                ) {
                    for (stroke in layerStrokes) {
                        // Phase 203: committed strokes render ONCE — twins are real
                        // rows baked at capture time; no view-time mirror here.
                        drawCommittedStrokeOnce(stroke, offsetY - pageTopY)
                    }
                }
                cache.hash = strokesHash
            }
            
            val paint = cache.paint
            paint.alpha = (layer.opacity * 255f).coerceIn(0f, 255f).toInt()
            paint.applyLayerBlend(layer.blendMode)

            // Phase 222: clipping-mask — clip layer N to layer N-1 alpha via DST_IN.
            val layerBmp = cache.bitmap.asAndroidBitmap()
            val isClippingMask = layer.id in clippingMaskLayerIds && prevLayerBitmap != null
            if (isClippingMask) {
                val nativeCanvas = drawContext.canvas.nativeCanvas
                val bounds = android.graphics.RectF(0f, 0f, size.width, size.height)
                val clipPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    alpha = paint.alpha
                    applyLayerBlend(layer.blendMode)
                }
                val clipSaveCount = nativeCanvas.saveLayer(bounds, clipPaint)
                try {
                    nativeCanvas.drawBitmap(layerBmp, 0f, pageTopY, null)
                    // DST_IN: keep only where previous layer has alpha.
                    val maskPaint = android.graphics.Paint().apply {
                        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                    }
                    nativeCanvas.drawBitmap(prevLayerBitmap!!, 0f, pageTopY, maskPaint)
                } finally {
                    nativeCanvas.restoreToCount(clipSaveCount)
                }
            } else {
                drawContext.canvas.nativeCanvas.drawBitmap(
                    layerBmp,
                    0f,
                    pageTopY,
                    paint
                )
            }

            // Alpha-lock: preview stroke clipped to existing layer content via DST_IN.
            if (isPreviewOnThisLayer && previewStroke != null) {
                if (layer.id in alphaLockLayerIds) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val bounds = android.graphics.RectF(0f, 0f, size.width, size.height)
                    val lockSaveCount = nativeCanvas.saveLayer(bounds, null)
                    try {
                        // Draw existing layer content as alpha mask.
                        nativeCanvas.drawBitmap(layerBmp, 0f, pageTopY, null)
                        // SRC_IN: draw new stroke only where mask exists.
                        val lockPaint = android.graphics.Paint().apply {
                            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                        }
                        val innerSaveCount = nativeCanvas.saveLayer(bounds, lockPaint)
                        try {
                            drawLivePreviewWithSymmetry(previewStroke, offsetY, symmetryMode)
                        } finally {
                            nativeCanvas.restoreToCount(innerSaveCount)
                        }
                    } finally {
                        nativeCanvas.restoreToCount(lockSaveCount)
                    }
                } else {
                    drawLivePreviewWithSymmetry(previewStroke, offsetY, symmetryMode)
                }
            }
        } else {
            val isNormal = layer.blendMode.equals("NORMAL", ignoreCase = true)
            val isOpaque = layer.opacity >= 0.99f

            val isAlphaLock = layer.id in alphaLockLayerIds
            val isClippingMask = layer.id in clippingMaskLayerIds && prevLayerBitmap != null
            val needsSaveLayer = isAlphaLock || isClippingMask || !isNormal || !isOpaque

            if (!needsSaveLayer) {
                for (stroke in layerStrokes) {
                    drawCommittedStrokeOnce(stroke, offsetY)
                }
                if (isPreviewOnThisLayer && previewStroke != null) {
                    drawLivePreviewWithSymmetry(previewStroke, offsetY, symmetryMode)
                }
            } else {
                val nativeCanvas = drawContext.canvas.nativeCanvas
                val bounds = android.graphics.RectF(0f, 0f, size.width, size.height)
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    alpha = (layer.opacity * 255f).coerceIn(0f, 255f).toInt()
                    applyLayerBlend(layer.blendMode)
                }
                val saveCount = nativeCanvas.saveLayer(bounds, paint)
                try {
                    // Alpha-lock: draw existing layer content as alpha mask first.
                    if (isAlphaLock) {
                        // Retrieve cached bitmap if available, else render strokes into a temp.
                        val layerBmpTmp = layerBitmapCache?.get(
                            "${pageIdx}_${layer.id}_v${vibrancyBoost}_s${if (strokeShadowEnabled) 1 else 0}"
                        )?.bitmap?.asAndroidBitmap()
                        if (layerBmpTmp != null) {
                            nativeCanvas.drawBitmap(layerBmpTmp, 0f, pageTopY, null)
                            val lockPaint = android.graphics.Paint().apply {
                                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                            }
                            val innerSaveCount = nativeCanvas.saveLayer(bounds, lockPaint)
                            try {
                                for (stroke in layerStrokes) {
                                    drawCommittedStrokeOnce(stroke, offsetY)
                                }
                                if (isPreviewOnThisLayer && previewStroke != null) {
                                    drawLivePreviewWithSymmetry(previewStroke, offsetY, symmetryMode)
                                }
                            } finally {
                                nativeCanvas.restoreToCount(innerSaveCount)
                            }
                        } else {
                            // No bitmap cache available — draw strokes directly (no alpha-lock).
                            for (stroke in layerStrokes) {
                                drawCommittedStrokeOnce(stroke, offsetY)
                            }
                            if (isPreviewOnThisLayer && previewStroke != null) {
                                drawLivePreviewWithSymmetry(previewStroke, offsetY, symmetryMode)
                            }
                        }
                    } else {
                        for (stroke in layerStrokes) {
                            drawCommittedStrokeOnce(stroke, offsetY)
                        }
                        if (isPreviewOnThisLayer && previewStroke != null) {
                            drawLivePreviewWithSymmetry(previewStroke, offsetY, symmetryMode)
                        }
                    }

                    // Clipping-mask: DST_IN against previous layer's alpha.
                    if (isClippingMask) {
                        val maskPaint = android.graphics.Paint().apply {
                            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                        }
                        nativeCanvas.drawBitmap(prevLayerBitmap!!, 0f, pageTopY, maskPaint)
                    }
                } finally {
                    nativeCanvas.restoreToCount(saveCount)
                }
            }
        }

        // Phase 222: track previous layer bitmap for clipping-mask compositing.
        if (layer.id in clippingMaskLayerIds) {
            prevLayerBitmap = if (layerBitmapCache != null) {
                layerBitmapCache.get("${pageIdx}_${layer.id}_v${vibrancyBoost}_s${if (strokeShadowEnabled) 1 else 0}")?.bitmap?.asAndroidBitmap()
            } else {
                null
            }
        }
    }
}

// Phase 201 (PERF 2.7): AGSL-only body — every RenderEffect/RuntimeShader
// reference below is guarded by this annotation AND the caller-side
// ShaderCapabilityHelper.isAgslSupported gate.
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawWetLayerPass(
    layerStrokes: List<Stroke>,
    previewStroke: Stroke?,
    layer: com.authorss81.noteflow.data.model.LayerEntity,
    isPreviewOnThisLayer: Boolean,
    offsetY: Float,
    pageWidth: Float,
    pageHeight: Float,
    isDarkPaper: Boolean,
    inkRenderer: CanvasStrokeRenderer?,
    graphicsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer?,
    wetMixingEffect: AgslShaders.WetMixingEffect?,
    currentTool: StrokeTool,
    currentColor: Color,
    currentWidth: Float,
    activePoints: List<PointF>,
    activeStart: PointF?,
    wetCanvasEngine: com.authorss81.noteflow.services.WetCanvasEngine,
    wetBrushEngine: com.authorss81.noteflow.services.WetBrushEngine,
    canvasDrawScope: androidx.compose.ui.graphics.drawscope.CanvasDrawScope? = null,
    density: androidx.compose.ui.unit.Density? = null,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection? = null,
    strokeRenderOpts: StrokeRenderOpts = StrokeRenderOpts(),
    liveStrokeSeed: Float = 0f,
    vibrancyBoost: Float = 0f,
    strokeShadowEnabled: Boolean = false,
    // Phase 220: pro brush controls — blender strength + scatter.
    blenderStrengthPercent: Int = 85,
    scatterAmountPercent: Int = 0,
    // Phase 227: texture-strength dial mapped into `uPaperGrain`.
    paperTextureStrength: Int = com.authorss81.noteflow.services.PaperTextureStrengthPolicy.DEFAULT
) {
    if (ShaderCapabilityHelper.isAgslSupported && wetMixingEffect != null) {
        val brushPos = activePoints.lastOrNull() ?: activeStart
        val prevPos = if (activePoints.size >= 2) activePoints[activePoints.size - 2] else activeStart

        val nativeCanvas = drawContext.canvas.nativeCanvas
        val plainPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            alpha = (layer.opacity * 255f).coerceIn(0f, 255f).toInt()
            applyLayerBlend(layer.blendMode)
        }
        val pageBounds = android.graphics.RectF(0f, offsetY, pageWidth, offsetY + pageHeight)

        // Phase-201 REVIEW FIXES (HIGH + MEDIUM coordinate spaces):
        //  - MEDIUM: the dirty rect is built in CANVAS space now. Strokes are
        //    drawn at (x, y + offsetY), but the old rect mixed PAGE-LOCAL segment
        //    coords with the canvas-space pageBounds, so `intersect` degenerated
        //    on every page past the first and silently disabled the GPU path
        //    there.
        //  - HIGH: a RenderNode-carried RenderEffect evaluates the AGSL shader in
        //    NODE-LOCAL coordinates (the space the recording below creates via
        //    translate(-origin)). Feeding it raw page/canvas coords made
        //    distToSegment(coord, uPrevPos, uBrushPos) exceed uBrushRadius for
        //    every pixel, so the shader early-outed to a plain contents.eval()
        //    pass-through and wet mixing stayed silently invisible. The positional
        //    uniforms are therefore re-expressed relative to the EXACT int origin
        //    the node records with — one shared (nodeOriginX, nodeOriginY) pair
        //    keeps uniforms and recording in the same space.
        var hasEffect = false
        var dirty: android.graphics.RectF? = null
        var nodeOriginX = 0
        var nodeOriginY = 0
        if (brushPos != null && isPreviewOnThisLayer) {
            val preset = AgslShaders.PRESETS[currentTool] ?: AgslShaders.ToolPreset(0.5f, 0.5f, 0.5f, 0f, 0.5f)

            val brushX = brushPos.x
            val brushY = brushPos.y + offsetY
            val segBaseX = prevPos?.x ?: brushPos.x
            val segBaseY = (prevPos?.y ?: brushPos.y) + offsetY
            val pad = currentWidth * 1.5f + 8f
            val candidate = android.graphics.RectF(
                minOf(segBaseX, brushX) - pad,
                minOf(segBaseY, brushY) - pad,
                maxOf(segBaseX, brushX) + pad,
                maxOf(segBaseY, brushY) + pad
            )
            if (candidate.intersect(pageBounds)) {
                nodeOriginX = candidate.left.toInt()
                nodeOriginY = candidate.top.toInt()
                wetMixingEffect.update(
                    prevX = segBaseX - nodeOriginX,
                    prevY = segBaseY - nodeOriginY,
                    brushX = brushX - nodeOriginX,
                    brushY = brushY - nodeOriginY,
                    radius = currentWidth * 1.5f,
                    color = currentColor,
                    wetness = preset.wetness,
                    pigmentLoad = preset.pigmentLoad,
                    // Phase 220: SMUDGE uses the user blender-strength slider;
                    // all other tools keep the ToolPreset mixStrength.
                    mixStrength = if (currentTool == com.authorss81.noteflow.data.model.StrokeTool.SMUDGE) {
                        blenderStrengthPercent.coerceIn(0, 100) / 100f
                    } else {
                        preset.mixStrength
                    },
                    impasto = preset.impasto,
                    hardness = preset.hardness,
                    // Phase 227: the texture-strength dial scales the wet brush's OWN
                    // per-preset granulation, anchored at exactly 1.0 for the default
                    // 50 so stock wet strokes keep the pre-227 `uPaperGrain`.
                    paperGrain = (
                        wetCanvasEngine.brushParams.paperGrain *
                            com.authorss81.noteflow.services.PaperTextureStrengthPolicy.shaderGain(paperTextureStrength)
                        ).coerceIn(0f, 1f),
                    seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(
                        previewStroke?.id ?: "preview"
                    ),
                    strokeSeed = liveStrokeSeed,
                    brushStyle = preset.brushStyle,
                    vibrancy = vibrancyBoost
                )
                hasEffect = true
                dirty = candidate
            }
        }

        // Phase 201 (PERF 2.7) — GPU carrier FIX. The pre-201 code attached the
        // RenderEffect through a reflective lookup on android.graphics.Paint, but
        // Paint has NO such method at ANY API level (verified against the API-36
        // android.jar: zero RenderEffect references in Paint.class; the only
        // public carriers are View/RenderNode). That lookup threw
        // NoSuchMethodException every frame and the catch swallowed it, so
        // "effectPaint" was a plain copy and the AGSL shader NEVER ran on any
        // device. The effect now rides a reusable RenderNode
        // (RenderNode.setRenderEffect, API 31+) whose display list is composited
        // by the hardware canvas — the actual RenderNode GPU path.
        val canUseGpuEffect = hasEffect &&
            nativeCanvas.isHardwareAccelerated &&
            wetMixingEffect != null &&
            canvasDrawScope != null &&
            density != null &&
            layoutDirection != null

        fun drawStrokes() {
            for (stroke in layerStrokes) {
                drawSingleStroke(stroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost, shadowEnabled = strokeShadowEnabled, scatterAmountPercent = scatterAmountPercent)
            }
            if (previewStroke != null) {
                drawSingleStroke(previewStroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost, shadowEnabled = strokeShadowEnabled, scatterAmountPercent = scatterAmountPercent)
            }
        }

        if (!canUseGpuEffect || dirty == null || wetMixingEffect == null) {
            // Idle fallback / software canvas: keep the existing full-page plain
            // layer so layer opacity/blend still apply unchanged.
            val saveCount = nativeCanvas.saveLayer(pageBounds, plainPaint)
            try {
                drawStrokes()
            } finally {
                nativeCanvas.restoreToCount(saveCount)
            }
        } else {
            val node = wetMixingEffect.renderNode
            // Same int origin the uniforms were rebased to above — single source
            // of truth so shader coords and recorded pixels stay aligned.
            val left = nodeOriginX
            val top = nodeOriginY
            val right = kotlin.math.ceil(dirty.right).toInt().coerceAtLeast(left + 1)
            val bottom = kotlin.math.ceil(dirty.bottom).toInt().coerceAtLeast(top + 1)

            node.setPosition(left, top, right, bottom)
            val recordingCanvas = node.beginRecording()
            try {
                val composeNodeCanvas = androidx.compose.ui.graphics.Canvas(recordingCanvas)
                composeNodeCanvas.translate(-left.toFloat(), -top.toFloat())
                canvasDrawScope.draw(
                    density = density!!,
                    layoutDirection = layoutDirection!!,
                    canvas = composeNodeCanvas,
                    size = androidx.compose.ui.geometry.Size(pageWidth, pageHeight)
                ) {
                    drawStrokes()
                }
            } finally {
                node.endRecording()
            }
            // Attach the AGSL RuntimeShader RenderEffect to the NODE — the real
            // RenderNode GPU-compositing carrier (API 31+).
            node.setRenderEffect(wetMixingEffect.androidEffect)

            // 1) Full-layer plain pass with the dirty (effect) region punched out,
            //    so the effect pass below supplies the ONLY pixels there and the
            //    stroke alpha is not double-blended.
            val baseSave = nativeCanvas.saveLayer(pageBounds, plainPaint)
            try {
                nativeCanvas.save()
                nativeCanvas.clipOutRect(dirty)
                drawStrokes()
                nativeCanvas.restore()
            } finally {
                nativeCanvas.restoreToCount(baseSave)
            }

            // 2) Effect pass sized to the dirty rect only: the hardware canvas
            //    composites the RenderNode's shader-applied output inside this
            //    saveLayer, whose paint still applies the layer opacity/blend to
            //    the final result exactly as before.
            val effectSave = nativeCanvas.saveLayer(dirty, plainPaint)
            try {
                nativeCanvas.drawRenderNode(node)
            } finally {
                nativeCanvas.restoreToCount(effectSave)
            }
        }
    } else {
        for (stroke in layerStrokes) {
            drawSingleStroke(stroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost, shadowEnabled = strokeShadowEnabled, scatterAmountPercent = scatterAmountPercent)
        }
        if (previewStroke != null) {
            drawSingleStroke(previewStroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost, shadowEnabled = strokeShadowEnabled, scatterAmountPercent = scatterAmountPercent)
        }
    }
}

/**
 * Phase 27: smooth nib-style ribbon rendering for CALLIGRAPHIC / CHISEL_MARKER.
 * Each segment quad is extended [RibbonJoinMath.QUAD_OVERLAP] of the nib half-width
 * PAST every interior vertex, a cap circle of [RibbonJoinMath.VERTEX_CAP_FACTOR] *
 * half-width is stamped at each interior vertex, and round caps (half-width circles)
 * close both ends. Together these eliminate the concave inside-notch and needle
 * outside-corner that adjacent nib quads used to pinch at sharp turns (the coverage
 * invariant is unit-tested in RibbonJoinMathTest). [perPointColor] supplies a
 * per-segment color for the multi-color modes; when null every quad uses [color].
 */
private fun DrawScope.drawRibbonStroke(
    pts: List<PointF>,
    offsetY: Float,
    nibHalfX: Float,
    nibHalfY: Float,
    color: Color,
    perPointColor: ((Int) -> Color)? = null
) {
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        drawCircle(
            perPointColor?.invoke(0) ?: color,
            radius = sqrt(nibHalfX * nibHalfX + nibHalfY * nibHalfY),
            center = Offset(pts.first().x, pts.first().y + offsetY)
        )
        return
    }
    val hw = sqrt(nibHalfX * nibHalfX + nibHalfY * nibHalfY)
    if (hw < 1e-3f) return
    val overlap = com.authorss81.noteflow.services.RibbonJoinMath.QUAD_OVERLAP * hw
    val capR = com.authorss81.noteflow.services.RibbonJoinMath.VERTEX_CAP_FACTOR * hw
    val colFor: (Int) -> Color = { i ->
        perPointColor?.invoke(i) ?: color
    }
    for (i in 1 until pts.size) {
        val p0 = pts[i - 1]
        val p1 = pts[i]
        val start = com.authorss81.noteflow.services.RibbonJoinMath.extendBeyond(p1.x, p1.y, p0.x, p0.y, overlap)
        val end = com.authorss81.noteflow.services.RibbonJoinMath.extendBeyond(p0.x, p0.y, p1.x, p1.y, overlap)
        val path = Path().apply {
            moveTo(start.first - nibHalfX, start.second - nibHalfY + offsetY)
            lineTo(end.first - nibHalfX, end.second - nibHalfY + offsetY)
            lineTo(end.first + nibHalfX, end.second + nibHalfY + offsetY)
            lineTo(start.first + nibHalfX, start.second + nibHalfY + offsetY)
            close()
        }
        drawPath(path = path, color = colFor(i))
    }
    for (i in 1 until pts.size - 1) {
        drawCircle(colFor(i), radius = capR, center = Offset(pts[i].x, pts[i].y + offsetY))
    }
    drawCircle(colFor(0), radius = hw, center = Offset(pts[0].x, pts[0].y + offsetY))
    drawCircle(colFor(pts.size - 1), radius = hw, center = Offset(pts.last().x, pts.last().y + offsetY))
}

private fun DrawScope.drawSingleStroke(
    stroke: Stroke,
    offsetY: Float,
    isDarkPaper: Boolean = false,
    inkRenderer: CanvasStrokeRenderer? = null,
    renderOpts: StrokeRenderOpts = StrokeRenderOpts(),
    vibrancy: Float = 0f,
    // Phase 213: composition-level gate threaded from the canvas (user setting
    // AND non-low-end tier). The per-stroke decision still runs through
    // BrushShadowPolicy.plan below, which can still return null per tool.
    shadowEnabled: Boolean = false,
    // Phase 220: scatter amount for SPLATTER/SMUDGE bitmap stamps.
    scatterAmountPercent: Int = 0
) {
    // Phase 213 ("Paper elevation"): soft drop-shadow UNDER the ink, drawn
    // BEFORE both render paths (the androidx.ink advanced path returns early
    // below) so every tool lifts off the paper consistently. The decision table
    // lives in BrushShadowPolicy — null means zero draw work (setting off or a
    // utility tool), keeping old strokes byte-identical.
    val shadowPlan = com.authorss81.noteflow.services.BrushShadowPolicy.plan(
        tool = stroke.tool,
        widthPx = stroke.width,
        isDarkPaper = isDarkPaper,
        settingEnabled = shadowEnabled,
        pxPerDp = density
    )
    if (shadowPlan != null) {
        StrokeShadowRenderer.drawStrokeShadow(
            canvas = drawContext.canvas.nativeCanvas,
            stroke = stroke,
            offsetY = offsetY,
            plan = shadowPlan,
            isDarkPaper = isDarkPaper
        )
    }
    // Mask-based true partial for wet (single raster + Clear punch, no fragments)
    val wetMask = stroke.eraseMask
    val hasWetMask = !wetMask.isNullOrEmpty() && com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(stroke.tool)
    // Phase 228 review-fix: use a layer bounded to the stroke ink + its punch
    // circles instead of a full-page layer. Per-frame saveLayer is proportional
    // to the masked stroke, not the canvas (BlendMode.Clear is confined to it).
    var maskedStrokeLayer = false
    if (hasWetMask) {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (p in stroke.points) {
            if (p.x < left) left = p.x
            if (p.y + offsetY < top) top = p.y + offsetY
            if (p.x > right) right = p.x
            if (p.y + offsetY > bottom) bottom = p.y + offsetY
        }
        stroke.start?.let {
            left = minOf(left, it.x)
            right = maxOf(right, it.x)
            top = minOf(top, it.y + offsetY)
            bottom = maxOf(bottom, it.y + offsetY)
        }
        stroke.end?.let {
            left = minOf(left, it.x)
            right = maxOf(right, it.x)
            top = minOf(top, it.y + offsetY)
            bottom = maxOf(bottom, it.y + offsetY)
        }
        // Include every punch circle's full extent (centers can lie up to a mask
        // radius beyond the centerline — see StrokeSegmenter.coverageRadiusFor).
        for (m in wetMask!!) {
            val cx = m.x
            val cy = m.y + offsetY
            left = minOf(left, cx - m.radius)
            right = maxOf(right, cx + m.radius)
            top = minOf(top, cy - m.radius)
            bottom = maxOf(bottom, cy + m.radius)
        }
        // Ink nibs extend stroke.width/2 beyond the centerline.
        val pad = stroke.width / 2f
        val bounded = androidx.compose.ui.geometry.Rect(
            left - pad, top - pad, right + pad, bottom + pad
        ).intersect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
        if (!bounded.isEmpty) {
            maskedStrokeLayer = true
            drawContext.canvas.saveLayer(bounded, androidx.compose.ui.graphics.Paint())
        }
    }
    if (stroke.isAdvanced && inkRenderer != null) {
        try {
            val inkStroke = convertToInkStroke(stroke, vibrancy = vibrancy)
            if (inkStroke != null) {
                val nativeCanvas = drawContext.canvas.nativeCanvas
                val matrix = android.graphics.Matrix()
                if (offsetY != 0f) {
                    matrix.postTranslate(0f, offsetY)
                }
                inkRenderer.draw(nativeCanvas, inkStroke, matrix)
                if (hasWetMask && maskedStrokeLayer) {
                    for (m in wetMask!!) {
                        drawCircle(
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            radius = m.radius,
                            center = Offset(m.x, m.y + offsetY),
                            blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                        )
                    }
                    drawContext.canvas.restore()
                }
                return
            }
        } catch (e: Throwable) {
            // Safely catch any native library/alpha API rendering exception and fall back to Compose path drawing
        }
    }

    // Phase 19: render-time vibrancy/saturation boost. NEVER written back to the
    // stored stroke — saved colorInt stays exactly as picked.
    val baseColor: Color = if (vibrancy > 0f) {
        Color(com.authorss81.noteflow.services.ColorVibrancy.boostColorInt(stroke.color.toArgb(), vibrancy))
    } else {
        stroke.color
    }
    var rawColor = baseColor
    if (isDarkPaper && stroke.tool != StrokeTool.HIGHLIGHTER) {
        val lum = 0.299f * rawColor.red + 0.587f * rawColor.green + 0.114f * rawColor.blue
        if (lum < 0.2f) {
            rawColor = Color(0xFFF8FAFC)
        }
    }
    val isSolidTool = (stroke.tool == StrokeTool.PEN ||
                      stroke.tool == StrokeTool.FOUNTAIN_PEN ||
                      stroke.tool == StrokeTool.FINELINER ||
                      stroke.tool == StrokeTool.CALLIGRAPHIC ||
                      stroke.tool == StrokeTool.CHISEL_MARKER ||
                      stroke.tool == StrokeTool.DOTTED ||
                      stroke.tool == StrokeTool.LINE ||
                      stroke.tool.isShapeTool) &&
                      !com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(stroke.tool)

    val color = when {
        stroke.tool == StrokeTool.HIGHLIGHTER -> baseColor.copy(alpha = 0.35f)
        isSolidTool -> rawColor.copy(alpha = 1.0f)
        else -> rawColor
    }
    val strokeWidth = stroke.width

    // Phase 222: tilt shading — average tilt from stroke points → width/alpha factors.
    val tiltWidthMul: Float
    val tiltAlphaMul: Float
    if (renderOpts.tiltShadingEnabled && stroke.points.isNotEmpty()) {
        val avgTilt = stroke.points.mapNotNull { it.tilt }.average().toFloat()
        tiltWidthMul = com.authorss81.noteflow.services.TiltShadingPolicy.widthFactor(avgTilt)
        tiltAlphaMul = com.authorss81.noteflow.services.TiltShadingPolicy.alphaFactor(avgTilt)
    } else {
        tiltWidthMul = 1f
        tiltAlphaMul = 1f
    }

    // Phase 27: multi-color render-time color effects. The per-point color is
    // DERIVED from the stroke's stored colorMode + seed (+ optional gradient end
    // color) via BrushColorModeMath — never stored per point. The derivations
    // below share the same helpers the AGSL wet path uses so both render paths
    // agree. Textured tools (pencil, airbrush, watercolor, …) cannot carry a
    // per-point sweep through a single-color BitmapShader today; for those the
    // derived color is sampled at the stroke's mid-progress (honest limitation —
    // see docs/phase-27.md). Vibrancy boosts the base/end colors only; the
    // per-point sweep is never re-boosted.
    val isMultiColor = stroke.colorMode.isMultiColor
    val gradientEndArgb = stroke.gradientToColorInt
        ?: com.authorss81.noteflow.services.BrushColorModeMath.complementaryArgb(baseColor.toArgb())
    val colorAlphaMul = when (stroke.tool) {
        StrokeTool.HIGHLIGHTER -> 0.35f
        StrokeTool.MARKER -> 0.42f
        StrokeTool.PENCIL -> 0.82f
        StrokeTool.AIRBRUSH -> 0.35f
        StrokeTool.SPLATTER, StrokeTool.SMUDGE -> 0.65f
        else -> 1f
    }
    fun derivedColorAt(progress: Float): Color {
        val argb = com.authorss81.noteflow.services.BrushColorModeMath.colorForProgress(
            stroke.colorMode, baseColor.toArgb(), progress, stroke.colorSeed, gradientEndArgb
        )
        val c = Color(argb)
        return if (colorAlphaMul < 1f) c.copy(alpha = c.alpha * colorAlphaMul) else c
    }
    fun derivedColorAtPoint(points: List<PointF>, i: Int): Color =
        derivedColorAt(com.authorss81.noteflow.services.BrushColorModeMath.strokeProgress(points, i))

    when (stroke.tool) {
        StrokeTool.PEN, StrokeTool.HIGHLIGHTER -> {
            if (renderOpts.velocityModulated && stroke.tool == StrokeTool.PEN && stroke.points.size > 1) {
                // Phase 18: velocity-based width modulation — fast -> thin, slow -> thick.
                // Segment-level so the modulation is visible along a single stroke.
                val pts = stroke.points
                for (i in 0 until pts.size - 1) {
                    val p1 = pts[i]
                    val p2 = pts[i + 1]
                    val vel = com.authorss81.noteflow.services.BrushStrokeMath.segmentVelocity(p1, p2)
                    // Phase 222: per-segment tilt shading for width.
                    val segTiltW = if (renderOpts.tiltShadingEnabled) {
                        val t1 = p1.tilt ?: 0f
                        val t2 = p2.tilt ?: 0f
                        (com.authorss81.noteflow.services.TiltShadingPolicy.widthFactor(t1) +
                            com.authorss81.noteflow.services.TiltShadingPolicy.widthFactor(t2)) / 2f
                    } else 1f
                    val dynamicWidth = (strokeWidth * com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(vel, renderOpts.velocityIntensity) * segTiltW).coerceAtLeast(0.75f)
                    // Phase 222: per-segment tilt shading for alpha.
                    val segTiltA = if (renderOpts.tiltShadingEnabled) {
                        val t1 = p1.tilt ?: 0f
                        val t2 = p2.tilt ?: 0f
                        (com.authorss81.noteflow.services.TiltShadingPolicy.alphaFactor(t1) +
                            com.authorss81.noteflow.services.TiltShadingPolicy.alphaFactor(t2)) / 2f
                    } else 1f
                    val segColor = if (segTiltA < 1f) color.copy(alpha = color.alpha * segTiltA) else color
                    drawLine(
                        color = if (isMultiColor) derivedColorAtPoint(pts, i + 1) else segColor,
                        start = Offset(p1.x, p1.y + offsetY),
                        end = Offset(p2.x, p2.y + offsetY),
                        strokeWidth = dynamicWidth,
                        cap = StrokeCap.Round
                    )
                }
            } else if (stroke.points.size > 1) {
                // Quadratic Bezier Curve Path Smoothing for silky smooth pen ink
                if (isMultiColor) {
                    // Phase 27: a per-point sweep needs segment-level color, so the
                    // multi-color modes render as dense round-capped segments instead
                    // of a single smoothed path. Round caps keep it visually smooth.
                    val pts = stroke.points
                    for (i in 0 until pts.size - 1) {
                        drawLine(
                            color = derivedColorAtPoint(pts, i + 1),
                            start = Offset(pts[i].x, pts[i].y + offsetY),
                            end = Offset(pts[i + 1].x, pts[i + 1].y + offsetY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                } else {
                    val path = Path().apply {
                        val pts = stroke.points
                        moveTo(pts[0].x, pts[0].y + offsetY)
                        if (pts.size == 2) {
                            lineTo(pts[1].x, pts[1].y + offsetY)
                        } else {
                            val firstMidX = (pts[0].x + pts[1].x) / 2f
                            val firstMidY = (pts[0].y + pts[1].y) / 2f + offsetY
                            lineTo(firstMidX, firstMidY)
                            for (i in 1 until pts.size - 1) {
                                val p1 = pts[i]
                                val p2 = pts[i + 1]
                                val midX = (p1.x + p2.x) / 2f
                                val midY = (p1.y + p2.y) / 2f + offsetY
                                quadraticTo(p1.x, p1.y + offsetY, midX, midY)
                            }
                            lineTo(pts.last().x, pts.last().y + offsetY)
                        }
                    }
                    val tiltStrokeWidth = strokeWidth * tiltWidthMul
                    val tiltColor = if (tiltAlphaMul < 1f) color.copy(alpha = color.alpha * tiltAlphaMul) else color
                    drawPath(
                        path = path,
                        color = tiltColor,
                        style = DrawStrokeStyle(width = tiltStrokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            } else if (stroke.points.size == 1) {
                val dotColor = if (tiltAlphaMul < 1f) color.copy(alpha = color.alpha * tiltAlphaMul) else color
                drawCircle(if (isMultiColor) derivedColorAt(1f) else dotColor, radius = (strokeWidth * tiltWidthMul) / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.FOUNTAIN_PEN -> {
            if (stroke.points.size > 1) {
                val pts = stroke.points
                for (i in 0 until pts.size - 1) {
                    val p1 = pts[i]
                    val p2 = pts[i + 1]
                    val dx = p2.x - p1.x
                    val dy = p2.y - p1.y
                    val dist = sqrt(dx * dx + dy * dy)
                    // Velocity modulation: fast -> thin, slow -> thick flourishes.
                    // Phase 18: the new velocity helper (units px/ms from timestamps, or
                    // px/16ms spacing when timestamps are absent) drives it when enabled.
                    val dynamicWidth = if (renderOpts.velocityModulated) {
                        (strokeWidth * com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(
                            com.authorss81.noteflow.services.BrushStrokeMath.segmentVelocity(p1, p2),
                            renderOpts.velocityIntensity
                        )).coerceAtLeast(1f)
                    } else {
                        (strokeWidth * (1.7f - (dist / 14f).coerceIn(0f, 1.1f))).coerceAtLeast(1f)
                    }
                    drawLine(
                        color = if (isMultiColor) derivedColorAtPoint(pts, i) else color,
                        start = Offset(p1.x, p1.y + offsetY),
                        end = Offset(p2.x, p2.y + offsetY),
                        strokeWidth = dynamicWidth,
                        cap = StrokeCap.Round
                    )
                }
            } else if (stroke.points.size == 1) {
                drawCircle(if (isMultiColor) derivedColorAt(1f) else color, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.PENCIL -> {
            val pencilColor = if (isMultiColor) derivedColorAt(0.5f) else color.copy(alpha = 0.82f * tiltAlphaMul)
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawTexturedStrokePath(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth * tiltWidthMul,
                color = pencilColor,
                textureType = BrushTextureEngine.TextureType.PENCIL_GRAPHITE
            )
        }
        StrokeTool.AIRBRUSH -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            // Phase 18: dwell density — holding the brush still deposits a denser
            // cloud. Points carry timestamps; when a long dwell gap exists, extra
            // stamps are forced around the held point (existing stamp engine only).
            var stampPoints = stroke.points
            var forceDense = false
            if (stroke.points.size >= 2 && stroke.points.any { it.timestampMs != null }) {
                val dense = buildAirbrushDensePoints(stroke.points)
                if (dense.size > stroke.points.size) {
                    stampPoints = dense
                    forceDense = true
                }
            }
            BrushTextureEngine.drawBitmapStampSequence(
                nativeCanvas = nativeCanvas,
                points = stampPoints,
                offsetY = offsetY,
                baseSize = strokeWidth * 2.5f,
                color = if (isMultiColor) derivedColorAt(0.5f) else color.copy(alpha = 0.35f),
                textureType = BrushTextureEngine.TextureType.AIRBRUSH_SPRAY,
                spacingFactor = 0.2f,
                scatterFactor = 0.35f,
                forceStampEvery = forceDense
            )
        }
        StrokeTool.OIL_PAINT -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            // Phase 18: pressure-driven bristle spread — higher pressure -> wider
            // contact patch + more pigment. Identity at full pressure (touch devices).
            val oPressure = meanPointPressure(stroke.points)
            val oSpread = com.authorss81.noteflow.services.BrushStrokeMath.bristleSpreadFactor(oPressure, 1f)
            val oPigment = com.authorss81.noteflow.services.BrushStrokeMath.pigmentFromPressure(oPressure)
            val oColor = if (isMultiColor) derivedColorAt(0.5f) else color
            BrushTextureEngine.drawTexturedStrokePath(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth * 1.3f * oSpread,
                color = oColor.copy(alpha = (oColor.alpha * 0.9f * oPigment).coerceIn(0.6f, 1.0f)),
                textureType = BrushTextureEngine.TextureType.CANVAS_WEAVE,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(stroke.id)
            )
        }
        StrokeTool.WATERCOLOR -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            // Phase 18: pressure-driven bristle spread + per-stroke grain seed.
            val wPressure = meanPointPressure(stroke.points)
            val wSpread = com.authorss81.noteflow.services.BrushStrokeMath.bristleSpreadFactor(wPressure, 1f)
            val wPigment = com.authorss81.noteflow.services.BrushStrokeMath.pigmentFromPressure(wPressure)
            val wColor = if (isMultiColor) derivedColorAt(0.5f) else color
            BrushTextureEngine.drawTexturedStrokePath(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth * 1.5f * wSpread,
                color = wColor.copy(alpha = (wColor.alpha * 0.60f * (0.75f + 0.25f * wPigment)).coerceIn(0.2f, 0.85f)),
                textureType = BrushTextureEngine.TextureType.WATERCOLOR_PAPER,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(stroke.id)
            )
        }
        StrokeTool.SPLATTER, StrokeTool.SMUDGE -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            // Phase 220: scatter-amount lerp — at 0 the legacy tight defaults
            // are preserved; at 1 stamps spread wide and far.
            val scatter = scatterAmountPercent.coerceIn(0, 100) / 100f
            BrushTextureEngine.drawBitmapStampSequence(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                baseSize = strokeWidth * 3.0f,
                color = if (isMultiColor) derivedColorAt(0.5f) else color.copy(alpha = 0.65f),
                textureType = BrushTextureEngine.TextureType.SPLATTER_DROPS,
                spacingFactor = 0.45f + (0.12f - 0.45f) * scatter,
                scatterFactor = 0.15f + (0.55f - 0.15f) * scatter
            )
        }
        // Phase 18: six NEW brush families — each renders visibly distinct. These are
        // honest vector approximations of their AGSL shader styles (see docs/brush-styles.md).
        StrokeTool.CHARCOAL -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val charcoalColor = if (tiltAlphaMul < 1f) {
                val c = if (isMultiColor) derivedColorAt(0.5f) else color
                c.copy(alpha = c.alpha * tiltAlphaMul)
            } else {
                if (isMultiColor) derivedColorAt(0.5f) else color
            }
            BrushTextureEngine.drawCharcoalStroke(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth * tiltWidthMul,
                color = charcoalColor,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(stroke.id)
            )
        }
        StrokeTool.OIL_PASTEL -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val pastelColor = if (isMultiColor) derivedColorAt(0.5f) else color
            BrushTextureEngine.drawTexturedStrokePath(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth * 1.1f,
                color = pastelColor.copy(alpha = (pastelColor.alpha * 0.92f).coerceIn(0f, 1f)),
                textureType = BrushTextureEngine.TextureType.OIL_PASTEL_STREAK,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(stroke.id)
            )
            if (stroke.points.size == 1) {
                drawCircle(pastelColor, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.INK_WASH -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawInkWashStroke(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth,
                color = if (isMultiColor) derivedColorAt(0.5f) else color,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(stroke.id)
            )
        }
        StrokeTool.GOUACHE -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val gouacheColor = if (isMultiColor) derivedColorAt(0.5f) else color
            BrushTextureEngine.drawTexturedStrokePath(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth,
                color = gouacheColor.copy(alpha = (gouacheColor.alpha * 0.98f).coerceIn(0f, 1f)),
                textureType = BrushTextureEngine.TextureType.GOUACHE_MATTE,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(stroke.id)
            )
            if (stroke.points.size == 1) {
                drawCircle(gouacheColor, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.DRY_BRUSH -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawDryBrushStroke(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth,
                color = if (isMultiColor) derivedColorAt(0.5f) else color,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(stroke.id)
            )
        }
        StrokeTool.PALETTE_KNIFE -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawPaletteKnifeStroke(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth,
                color = if (isMultiColor) derivedColorAt(0.5f) else color,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(stroke.id)
            )
        }
        StrokeTool.MARKER -> {
            val markerColor = if (isMultiColor) derivedColorAt(0.5f) else color.copy(alpha = 0.42f)
            if (stroke.points.size > 1) {
                val pts = stroke.points
                if (isMultiColor) {
                    for (i in 0 until pts.size - 1) {
                        drawLine(
                            color = derivedColorAtPoint(pts, i + 1),
                            start = Offset(pts[i].x, pts[i].y + offsetY),
                            end = Offset(pts[i + 1].x, pts[i + 1].y + offsetY),
                            strokeWidth = strokeWidth * 1.5f,
                            cap = StrokeCap.Round
                        )
                    }
                } else {
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y + offsetY)
                        for (i in 1 until pts.size) {
                            lineTo(pts[i].x, pts[i].y + offsetY)
                        }
                    }
                    drawPath(
                        path = path,
                        color = markerColor,
                        style = DrawStrokeStyle(width = strokeWidth * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            } else if (stroke.points.size == 1) {
                drawCircle(markerColor, radius = strokeWidth, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.CALLIGRAPHIC -> {
            if (stroke.points.size > 1) {
                val pts = stroke.points
                // Phase 18: nib angle is user-adjustable (Brush Studio). Default 45° keeps
                // the classic look; velocity modulation optionally thins the nib per stroke.
                val angle = Math.toRadians(renderOpts.nibAngleDeg.toDouble())
                val nibScale = if (renderOpts.velocityModulated) {
                    var velSum = 0f
                    var count = 0
                    for (i in 1 until pts.size) {
                        velSum += com.authorss81.noteflow.services.BrushStrokeMath.segmentVelocity(pts[i - 1], pts[i])
                        count++
                    }
                    com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(if (count > 0) velSum / count else 1f, renderOpts.velocityIntensity)
                } else 1f
                val dx = (cos(angle) * strokeWidth / 1.5f * nibScale).toFloat()
                val dy = (sin(angle) * strokeWidth / 1.5f * nibScale).toFloat()
                // Phase 27: overlapping quads + interior vertex caps + round end caps
                // (RibbonJoinMath) remove the concave notch / needle corner that sharp
                // turns used to produce between adjacent nib quads.
                drawRibbonStroke(
                    pts = pts,
                    offsetY = offsetY,
                    nibHalfX = dx,
                    nibHalfY = dy,
                    color = color,
                    perPointColor = if (isMultiColor) { i -> derivedColorAtPoint(pts, i) } else null
                )
            } else if (stroke.points.size == 1) {
                drawCircle(color, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.DOTTED -> {
            if (stroke.points.size > 1) {
                val pts = stroke.points
                val dashIntervals = floatArrayOf(strokeWidth * 2f, strokeWidth * 2.5f)
                if (isMultiColor) {
                    for (i in 0 until pts.size - 1) {
                        drawLine(
                            color = derivedColorAtPoint(pts, i + 1),
                            start = Offset(pts[i].x, pts[i].y + offsetY),
                            end = Offset(pts[i + 1].x, pts[i + 1].y + offsetY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(dashIntervals, 0f)
                        )
                    }
                } else {
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y + offsetY)
                        for (i in 1 until pts.size) {
                            lineTo(pts[i].x, pts[i].y + offsetY)
                        }
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = DrawStrokeStyle(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(dashIntervals, 0f)
                        )
                    )
                }
            } else if (stroke.points.size == 1) {
                drawCircle(color, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.NEON -> {
            val neonColor = if (isMultiColor) derivedColorAt(0.5f) else color
            if (stroke.points.size > 1) {
                val pts = stroke.points
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y + offsetY)
                    for (i in 1 until pts.size - 1) {
                        val p1 = pts[i]
                        val p2 = pts[i + 1]
                        val midX = (p1.x + p2.x) / 2f
                        val midY = (p1.y + p2.y) / 2f + offsetY
                        quadraticTo(p1.x, p1.y + offsetY, midX, midY)
                    }
                    lineTo(pts.last().x, pts.last().y + offsetY)
                }
                drawPath(
                    path = path,
                    color = neonColor.copy(alpha = 0.25f),
                    style = DrawStrokeStyle(width = strokeWidth * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = neonColor.copy(alpha = 0.85f),
                    style = DrawStrokeStyle(width = strokeWidth * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.95f),
                    style = DrawStrokeStyle(width = strokeWidth * 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            } else if (stroke.points.size == 1) {
                val center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY)
                drawCircle(neonColor.copy(alpha = 0.25f), radius = strokeWidth * 1.6f, center = center)
                drawCircle(neonColor.copy(alpha = 0.85f), radius = strokeWidth * 0.8f, center = center)
                drawCircle(Color.White, radius = strokeWidth * 0.3f, center = center)
            }
        }
        StrokeTool.FINELINER -> {
            if (renderOpts.velocityModulated && stroke.points.size > 1) {
                // Phase 18: velocity-based width modulation (fineliner).
                val pts = stroke.points
                for (i in 0 until pts.size - 1) {
                    val p1 = pts[i]
                    val p2 = pts[i + 1]
                    val vel = com.authorss81.noteflow.services.BrushStrokeMath.segmentVelocity(p1, p2)
                    val dynamicWidth = (strokeWidth.coerceAtLeast(1.2f) * com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(vel, renderOpts.velocityIntensity)).coerceAtLeast(0.8f)
                    drawLine(
                        color = if (isMultiColor) derivedColorAtPoint(pts, i + 1).copy(alpha = 0.95f) else color.copy(alpha = 0.95f),
                        start = Offset(p1.x, p1.y + offsetY),
                        end = Offset(p2.x, p2.y + offsetY),
                        strokeWidth = dynamicWidth,
                        cap = StrokeCap.Round
                    )
                }
            } else if (stroke.points.size > 1) {
                val pts = stroke.points
                if (isMultiColor) {
                    for (i in 0 until pts.size - 1) {
                        drawLine(
                            color = derivedColorAtPoint(pts, i + 1).copy(alpha = 0.95f),
                            start = Offset(pts[i].x, pts[i].y + offsetY),
                            end = Offset(pts[i + 1].x, pts[i + 1].y + offsetY),
                            strokeWidth = strokeWidth.coerceAtLeast(1.2f),
                            cap = StrokeCap.Round
                        )
                    }
                } else {
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y + offsetY)
                        for (i in 1 until pts.size) {
                            lineTo(pts[i].x, pts[i].y + offsetY)
                        }
                    }
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.95f),
                        style = DrawStrokeStyle(width = strokeWidth.coerceAtLeast(1.2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            } else if (stroke.points.size == 1) {
                drawCircle(
                    color = if (isMultiColor) derivedColorAt(1f) else color,
                    radius = strokeWidth / 2f,
                    center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY)
                )
            }
        }
        StrokeTool.CHISEL_MARKER -> {
            if (stroke.points.size > 1) {
                val pts = stroke.points
                // Phase 18: chisel nib angle is user-adjustable (Brush Studio). Default 30°.
                val angle = Math.toRadians(renderOpts.chiselNibAngleDeg.toDouble())
                val dx = (cos(angle) * strokeWidth * 0.9f).toFloat()
                val dy = (sin(angle) * strokeWidth * 0.9f).toFloat()
                // Phase 27: overlapping quads + interior vertex caps + round end caps
                // (RibbonJoinMath) remove the concave notch / needle corner that sharp
                // turns used to produce between adjacent chisel nib quads.
                drawRibbonStroke(
                    pts = pts,
                    offsetY = offsetY,
                    nibHalfX = dx,
                    nibHalfY = dy,
                    color = color.copy(alpha = 0.88f),
                    perPointColor = if (isMultiColor) { i -> derivedColorAtPoint(pts, i).copy(alpha = 0.88f) } else null
                )
            } else if (stroke.points.size == 1) {
                val pt = stroke.points.first()
                drawCircle(color, radius = strokeWidth, center = Offset(pt.x, pt.y + offsetY))
            }
        }
        StrokeTool.LASER -> {
            // Phase 205: fade envelope from LaserTrailPolicy — the SAME 1800 ms
            // budget and boundary the batched expiry wave uses, so a trail is
            // never removed while still visibly drawing (or kept after reaching
            // alpha 0). This branch re-executes every frame while trails exist:
            // the main draw pass subscribes to laserFadeTick (see the draw-phase
            // subscription at the top of the canvas block).
            val fadeFactor = com.authorss81.noteflow.services.LaserTrailPolicy.fadeFraction(
                stroke.timestampMs,
                System.currentTimeMillis()
            )
            val baseLaserColor = if (color == Color.Unspecified || color == Color.Transparent) Color(0xFFFF0044) else color
            val laserColor = baseLaserColor.copy(alpha = baseLaserColor.alpha * fadeFactor)

            if (fadeFactor > 0.01f) {
                if (stroke.points.size > 1) {
                    val pts = stroke.points
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y + offsetY)
                        for (i in 1 until pts.size) {
                            lineTo(pts[i].x, pts[i].y + offsetY)
                        }
                    }
                    drawPath(
                        path = path,
                        color = laserColor.copy(alpha = 0.35f * fadeFactor),
                        style = DrawStrokeStyle(width = strokeWidth * 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = path,
                        color = laserColor.copy(alpha = 0.9f * fadeFactor),
                        style = DrawStrokeStyle(width = strokeWidth * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.95f * fadeFactor),
                        style = DrawStrokeStyle(width = strokeWidth * 0.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    val lastPt = pts.last()
                    val tipOffset = Offset(lastPt.x, lastPt.y + offsetY)
                    drawCircle(laserColor.copy(alpha = 0.4f * fadeFactor), radius = strokeWidth * 2.8f, center = tipOffset)
                    drawCircle(laserColor.copy(alpha = laserColor.alpha), radius = strokeWidth * 1.4f, center = tipOffset)
                    drawCircle(Color.White.copy(alpha = fadeFactor), radius = strokeWidth * 0.6f, center = tipOffset)
                } else if (stroke.points.size == 1) {
                    val tipOffset = Offset(stroke.points.first().x, stroke.points.first().y + offsetY)
                    drawCircle(laserColor.copy(alpha = 0.4f * fadeFactor), radius = strokeWidth * 3.0f, center = tipOffset)
                    drawCircle(laserColor.copy(alpha = laserColor.alpha), radius = strokeWidth * 1.5f, center = tipOffset)
                    drawCircle(Color.White.copy(alpha = fadeFactor), radius = strokeWidth * 0.7f, center = tipOffset)
                }
            }
        }
        StrokeTool.LINE -> {
            if (stroke.start != null && stroke.end != null) {
                val start = Offset(stroke.start.x, stroke.start.y + offsetY)
                val end = Offset(stroke.end.x, stroke.end.y + offsetY)
                if (isMultiColor) {
                    // Phase 27: LINE carries a full along-the-line sweep via a linear
                    // gradient brush from the derived start color to the derived end color.
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(derivedColorAt(0f), derivedColorAt(1f)),
                            start = start,
                            end = end
                        ),
                        start = start,
                        end = end,
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                } else {
                    drawLine(color, start, end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                }
            }
        }
        StrokeTool.RECTANGLE -> {
            if (stroke.start != null && stroke.end != null) {
                val topLeft = Offset(minOf(stroke.start.x, stroke.end.x), minOf(stroke.start.y + offsetY, stroke.end.y + offsetY))
                val rectSize = Size(abs(stroke.end.x - stroke.start.x), abs(stroke.end.y - stroke.start.y))
                drawRect(if (isMultiColor) derivedColorAt(0.5f) else color, topLeft, rectSize, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
            }
        }
        StrokeTool.ELLIPSE -> {
            if (stroke.start != null && stroke.end != null) {
                val topLeft = Offset(minOf(stroke.start.x, stroke.end.x), minOf(stroke.start.y + offsetY, stroke.end.y + offsetY))
                val ovalSize = Size(abs(stroke.end.x - stroke.start.x), abs(stroke.end.y - stroke.start.y))
                drawOval(if (isMultiColor) derivedColorAt(0.5f) else color, topLeft, ovalSize, style = DrawStrokeStyle(width = strokeWidth))
            }
        }
        StrokeTool.TRIANGLE -> {
            if (stroke.start != null && stroke.end != null) {
                val p1 = Offset((stroke.start.x + stroke.end.x) / 2f, minOf(stroke.start.y, stroke.end.y) + offsetY)
                val p2 = Offset(minOf(stroke.start.x, stroke.end.x), maxOf(stroke.start.y, stroke.end.y) + offsetY)
                val p3 = Offset(maxOf(stroke.start.x, stroke.end.x), maxOf(stroke.start.y, stroke.end.y) + offsetY)
                val path = Path().apply {
                    moveTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    lineTo(p3.x, p3.y)
                    close()
                }
                drawPath(path = path, color = if (isMultiColor) derivedColorAt(0.5f) else color, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
            }
        }
        StrokeTool.STAR -> {
            if (stroke.start != null && stroke.end != null) {
                val cx = (stroke.start.x + stroke.end.x) / 2f
                val cy = (stroke.start.y + stroke.end.y) / 2f + offsetY
                val rx = abs(stroke.end.x - stroke.start.x) / 2f
                val ry = abs(stroke.end.y - stroke.start.y) / 2f
                val path = Path()
                for (i in 0 until 10) {
                    val angle = i * Math.PI / 5 - Math.PI / 2
                    val r = if (i % 2 == 0) 1f else 0.4f
                    val x = cx + rx * r * cos(angle).toFloat()
                    val y = cy + ry * r * sin(angle).toFloat()
                    if (i == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                path.close()
                drawPath(path = path, color = if (isMultiColor) derivedColorAt(0.5f) else color, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
            }
        }
        StrokeTool.PENTAGON -> {
            if (stroke.start != null && stroke.end != null) {
                val cx = (stroke.start.x + stroke.end.x) / 2f
                val cy = (stroke.start.y + stroke.end.y) / 2f + offsetY
                val rx = abs(stroke.end.x - stroke.start.x) / 2f
                val ry = abs(stroke.end.y - stroke.start.y) / 2f
                val path = Path()
                for (i in 0 until 5) {
                    val angle = i * 2 * Math.PI / 5 - Math.PI / 2
                    val x = cx + rx * cos(angle).toFloat()
                    val y = cy + ry * sin(angle).toFloat()
                    if (i == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                path.close()
                drawPath(path = path, color = if (isMultiColor) derivedColorAt(0.5f) else color, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
            }
        }
        StrokeTool.HEXAGON -> {
            if (stroke.start != null && stroke.end != null) {
                val cx = (stroke.start.x + stroke.end.x) / 2f
                val cy = (stroke.start.y + stroke.end.y) / 2f + offsetY
                val rx = abs(stroke.end.x - stroke.start.x) / 2f
                val ry = abs(stroke.end.y - stroke.start.y) / 2f
                val path = Path()
                for (i in 0 until 6) {
                    val angle = i * 2 * Math.PI / 6 - Math.PI / 2
                    val x = cx + rx * cos(angle).toFloat()
                    val y = cy + ry * sin(angle).toFloat()
                    if (i == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                path.close()
                drawPath(path = path, color = if (isMultiColor) derivedColorAt(0.5f) else color, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
            }
        }
        StrokeTool.ARROW -> {
            if (stroke.start != null && stroke.end != null) {
                val p1 = Offset(stroke.start.x, stroke.start.y + offsetY)
                val p2 = Offset(stroke.end.x, stroke.end.y + offsetY)
                drawLine(color, p1, p2, strokeWidth = strokeWidth, cap = StrokeCap.Round)

                val angle = atan2(p2.y - p1.y, p2.x - p1.x)
                val arrowSize = 24f
                val arrowAngle = Math.toRadians(30.0)

                val x1 = p2.x - arrowSize * cos(angle - arrowAngle).toFloat()
                val y1 = p2.y - arrowSize * sin(angle - arrowAngle).toFloat()
                val x2 = p2.x - arrowSize * cos(angle + arrowAngle).toFloat()
                val y2 = p2.y - arrowSize * sin(angle + arrowAngle).toFloat()

                drawLine(color, p2, Offset(x1, y1), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color, p2, Offset(x2, y2), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }
        }
        StrokeTool.TEXT -> {
            if (stroke.start != null) {
                if (stroke.text.isNotEmpty()) {
                    val parsedTextResult = CanvasTextStyle.parse(stroke.text)
                    val textStyle = parsedTextResult.first
                    val plainText = parsedTextResult.second
                    val lines = plainText.split("\n")
                    val paint = android.graphics.Paint().apply {
                        this.color = baseColor.toArgb()
                        textSize = textStyle.fontSizeSp * 1.5f
                        isAntiAlias = true
                        typeface = when (textStyle.fontStyle) {
                            "SERIF" -> android.graphics.Typeface.SERIF
                            "MONO" -> android.graphics.Typeface.MONOSPACE
                            "BOLD" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                            "ITALIC" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
                            "SCRIPT" -> android.graphics.Typeface.create("sans-serif-cursive", android.graphics.Typeface.NORMAL)
                            else -> android.graphics.Typeface.SANS_SERIF
                        }
                        textAlign = when (textStyle.align) {
                            "CENTER" -> android.graphics.Paint.Align.CENTER
                            "RIGHT" -> android.graphics.Paint.Align.RIGHT
                            else -> android.graphics.Paint.Align.LEFT
                        }
                    }

                    val lineHeight = paint.textSize * 1.25f
                    val startX = stroke.start.x
                    val startY = stroke.start.y + offsetY

                    if (!textStyle.bgHex.isNullOrBlank()) {
                        val maxLineWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 50f
                        val bgWidth = maxLineWidth + 24f
                        val bgHeight = lines.size * lineHeight + 12f
                        val bgPaint = android.graphics.Paint().apply {
                            this.color = try { android.graphics.Color.parseColor(textStyle.bgHex) } catch (e: Exception) { android.graphics.Color.YELLOW }
                            this.style = android.graphics.Paint.Style.FILL
                        }
                        val leftX = when (textStyle.align) {
                            "CENTER" -> startX - bgWidth / 2f
                            "RIGHT" -> startX - bgWidth + 12f
                            else -> startX - 12f
                        }
                        val bgRect = android.graphics.RectF(leftX, startY - paint.textSize, leftX + bgWidth, startY - paint.textSize + bgHeight)
                        drawContext.canvas.nativeCanvas.drawRoundRect(bgRect, 16f, 16f, bgPaint)
                    }

                    lines.forEachIndexed { i, line ->
                        drawContext.canvas.nativeCanvas.drawText(
                            line,
                            startX,
                            startY + (i * lineHeight),
                            paint
                        )
                    }
                } else {
                    drawCircle(color, radius = 8f, center = Offset(stroke.start.x, stroke.start.y + offsetY))
                }
            }
        }
        StrokeTool.FILL -> {
            if (stroke.points.size >= 3) {
                val path = Path().apply {
                    moveTo(stroke.points[0].x, stroke.points[0].y + offsetY)
                    for (i in 1 until stroke.points.size) {
                        lineTo(stroke.points[i].x, stroke.points[i].y + offsetY)
                    }
                    close()
                }
                drawPath(path = path, color = color, style = DrawStrokeStyle(width = 1f))
            } else if (stroke.points.size == 1) {
                val pt = stroke.points.first()
                drawCircle(color, radius = 3f, center = Offset(pt.x, pt.y + offsetY))
            }
        }
        StrokeTool.GRADIENT -> {
            if (stroke.start != null && stroke.end != null) {
                val topLeft = Offset(stroke.start.x, stroke.start.y + offsetY)
                val size = Size(
                    kotlin.math.abs(stroke.end.x - stroke.start.x),
                    kotlin.math.abs(stroke.end.y - stroke.start.y)
                )
                if (size.width > 0f && size.height > 0f) {
                    val fromArgb = stroke.colorInt
                    val toArgb = stroke.gradientToColorInt
                        ?: com.authorss81.noteflow.services.BrushColorModeMath.complementaryArgb(fromArgb)
                    val fromColor = Color(fromArgb)
                    val toColor = Color(toArgb)
                    val gradientStart: Offset
                    val gradientEnd: Offset
                    if (stroke.points.size >= 2) {
                        val p0 = stroke.points[0]
                        val p1 = stroke.points[1]
                        gradientStart = Offset(p0.x, p0.y + offsetY)
                        gradientEnd = Offset(p1.x, p1.y + offsetY)
                    } else {
                        gradientStart = Offset(topLeft.x, topLeft.y)
                        gradientEnd = Offset(topLeft.x + size.width, topLeft.y)
                    }
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(fromColor, toColor),
                            start = gradientStart,
                            end = gradientEnd
                        ),
                        topLeft = topLeft,
                        size = size
                    )
                }
            }
        }
        else -> {}
    }
    if (hasWetMask && maskedStrokeLayer) {
        for (m in wetMask!!) {
            drawCircle(
                color = androidx.compose.ui.graphics.Color.Transparent,
                radius = m.radius,
                center = Offset(m.x, m.y + offsetY),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
            )
        }
        drawContext.canvas.restore()
    }
}

/**
 * Phase 221 review fix #4: minimal stroke renderer for [android.graphics.Canvas]
 * used by the fill tool to render active-layer strokes into a temp sampling bitmap.
 * Only handles the subset of tools that produce visible pixels (freehand + shapes).
 */
private fun drawSingleStrokeToCanvas(
    canvas: android.graphics.Canvas,
    stroke: Stroke,
    inkRenderer: CanvasStrokeRenderer?
) {
    if (stroke.isAdvanced && inkRenderer != null) {
        try {
            val inkStroke = convertToInkStroke(stroke)
            if (inkStroke != null) {
                inkRenderer.draw(canvas, inkStroke, android.graphics.Matrix())
                punchEraseMasks(canvas, stroke)
                return
            }
        } catch (_: Throwable) { }
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke.colorInt
        strokeWidth = stroke.width.coerceAtLeast(1f)
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    when (stroke.tool) {
        StrokeTool.FILL -> {
            if (stroke.points.size >= 3) {
                paint.style = android.graphics.Paint.Style.FILL
                val path = android.graphics.Path()
                path.moveTo(stroke.points[0].x, stroke.points[0].y)
                for (i in 1 until stroke.points.size) path.lineTo(stroke.points[i].x, stroke.points[i].y)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        StrokeTool.GRADIENT -> {
            if (stroke.start != null && stroke.end != null) {
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawRect(
                    minOf(stroke.start.x, stroke.end.x),
                    minOf(stroke.start.y, stroke.end.y),
                    maxOf(stroke.start.x, stroke.end.x),
                    maxOf(stroke.start.y, stroke.end.y),
                    paint
                )
            }
        }
        StrokeTool.TEXT -> {
            if (stroke.start != null && stroke.text.isNotEmpty()) {
                val parsed = CanvasTextStyle.parse(stroke.text)
                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = stroke.colorInt
                    textSize = parsed.first.fontSizeSp * 1.5f
                }
                canvas.drawText(parsed.second, stroke.start.x, stroke.start.y, textPaint)
            }
        }
        else -> {
            if (stroke.points.size > 1) {
                val path = android.graphics.Path()
                path.moveTo(stroke.points[0].x, stroke.points[0].y)
                for (i in 1 until stroke.points.size) {
                    path.lineTo(stroke.points[i].x, stroke.points[i].y)
                }
                canvas.drawPath(path, paint)
            } else if (stroke.points.size == 1) {
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2f, paint)
            }
            if (stroke.start != null && stroke.end != null && stroke.tool.isShapeTool) {
                paint.style = android.graphics.Paint.Style.STROKE
                canvas.drawRect(
                    minOf(stroke.start.x, stroke.end.x),
                    minOf(stroke.start.y, stroke.end.y),
                    maxOf(stroke.start.x, stroke.end.x),
                    maxOf(stroke.start.y, stroke.end.y),
                    paint
                )
            }
            punchEraseMasks(canvas, stroke)
        }
    }
}

/**
 * Phase 228 review-fix: applies a wet stroke's recorded [Stroke.eraseMask] as
 * CLEAR punches on an android graphics canvas, so the fill tool's temp sampling
 * bitmap matches the on-screen end-state (an erased wet stroke must not feed
 * visible color into the flood-fill region scan). No-op for non-wet tools and
 * strokes without masks. CLEAR punches fully return the sampled pixels to
 * transparent, mirroring the on-screen BlendMode.Clear punch.
 */
private fun punchEraseMasks(canvas: android.graphics.Canvas, stroke: Stroke) {
    val masks = stroke.eraseMask
    if (masks.isNullOrEmpty() || stroke.points.size <= 1) return
    if (!com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(stroke.tool)) return
    val punch = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    for (m in masks) canvas.drawCircle(m.x, m.y, m.radius, punch)
}

private fun strokeContainsPoint(stroke: Stroke, point: Offset): Boolean {
    val threshold = (stroke.width + 18f)
    for (p in stroke.points) {
        val dx = p.x - point.x
        val dy = p.y - point.y
        if (dx * dx + dy * dy <= threshold * threshold) return true
    }
    // Phase 124: the anchor hit check previously covered only `start` — shape
    // strokes (rect/arrow/ellipse) keep geometry in `start`/`end`, so a tap on
    // the far tip/anchor of a shape could miss. Test both anchors now.
    stroke.start?.let {
        val dx = it.x - point.x
        val dy = it.y - point.y
        if (dx * dx + dy * dy <= threshold * threshold) return true
    }
    stroke.end?.let {
        val dx = it.x - point.x
        val dy = it.y - point.y
        if (dx * dx + dy * dy <= threshold * threshold) return true
    }
    return false
}

@Composable
private fun DraggableStickyNoteCard(
    note: CanvasStickyNote,
    zoomScale: Float,
    panOffset: Offset,
    isContinuousMode: Boolean,
    pdfPageFilter: Int,
    stickyNotes: List<CanvasStickyNote>,
    activeStickyNoteList: androidx.compose.runtime.snapshots.SnapshotStateList<CanvasStickyNote>,
    onStickyNotesChanged: (List<CanvasStickyNote>) -> Unit
) {
    val currentNote by rememberUpdatedState(note)
    val currentZoom by rememberUpdatedState(zoomScale)
    val currentPan by rememberUpdatedState(panOffset)
    val currentOnStickyNotesChanged by rememberUpdatedState(onStickyNotesChanged)

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    var resizeWidth by remember(currentNote.width) { mutableFloatStateOf(if (currentNote.width > 0) currentNote.width else 200f) }
    var resizeHeight by remember(currentNote.height) { mutableFloatStateOf(if (currentNote.height > 0) currentNote.height else 160f) }

    // Phase 13: live rotation while the handle is being dragged; committed to
    // the model (and DB) only on drag end, like the drag/resize commit pattern.
    var liveRotation by remember(currentNote.rotationDegrees) { mutableFloatStateOf(currentNote.rotationDegrees) }
    val commitRotation: (Float) -> Unit = { newRot ->
        val updated = currentNote.copy(rotationDegrees = newRot)
        val index = activeStickyNoteList.indexOfFirst { it.id == currentNote.id }
        if (index != -1) {
            activeStickyNoteList[index] = updated
        }
        val otherNotes = if (isContinuousMode) emptyList() else stickyNotes.filter { it.pdfPage != pdfPageFilter }
        currentOnStickyNotesChanged(otherNotes + activeStickyNoteList.toList())
    }

    var showEditDialog by remember { mutableStateOf(false) }
    var dialogTextState by remember(currentNote.text) { mutableStateOf(currentNote.text) }
    var dialogColorHexState by remember(currentNote.colorHex) { mutableStateOf(currentNote.colorHex) }

    val screenX = (currentNote.x + dragOffsetX) * currentZoom + currentPan.x
    val screenY = (currentNote.y + dragOffsetY) * currentZoom + currentPan.y
    val noteColor = remember(currentNote.colorHex) {
        try { Color(android.graphics.Color.parseColor(currentNote.colorHex)) }
        catch (e: Exception) { Color(0xFFFEF08A) }
    }

    val cardHeightDp = if (currentNote.isCollapsed) (38 * currentZoom).dp else (resizeHeight * currentZoom).dp

    val scaleAnim = remember { androidx.compose.animation.core.Animatable(0.7f) }
    val stickyReduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current
    LaunchedEffect(currentNote.id) {
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = if (stickyReduceMotion) {
                androidx.compose.animation.core.snap()
            } else {
                com.authorss81.noteflow.theme.MotionSystem.SpringReveal
            }
        )
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Sticky Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = dialogTextState,
                        onValueChange = { dialogTextState = it },
                        label = { Text("Note content") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Text("Sticky Note Color", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val stickyColors = listOf(
                            "#FEF08A" to "Yellow",
                            "#A7F3D0" to "Mint",
                            "#DDD6FE" to "Lavender",
                            "#FFEDD5" to "Peach",
                            "#BAE6FD" to "Sky Blue"
                        )
                        for ((hex, label) in stickyColors) {
                            val c = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(32.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (dialogColorHexState == hex) 3.dp else 1.dp,
                                        color = if (dialogColorHexState == hex) MaterialTheme.colorScheme.primary else Color.Gray,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                                    .clickable { dialogColorHexState = hex }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = currentNote.copy(text = dialogTextState, colorHex = dialogColorHexState)
                        val index = activeStickyNoteList.indexOfFirst { it.id == currentNote.id }
                        if (index != -1) {
                            activeStickyNoteList[index] = updated
                        }
                        val otherNotes = if (isContinuousMode) emptyList() else stickyNotes.filter { it.pdfPage != pdfPageFilter }
                        currentOnStickyNotesChanged(otherNotes + activeStickyNoteList.toList())
                        showEditDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val actualWidth = if (currentNote.isCollapsed) 48f else resizeWidth

    // Phase 193: resize handles are INVISIBLE at rest and appear only while
    // the item is being touched/dragged/resized. One shared policy for every
    // item type; the observation below reveals on touch-down and hides on
    // pointer-up/cancel, while each drag gesture also toggles the flag so a
    // resize/rotation started on the (still-composed) handle hit-box keeps the
    // handles visible for the duration of the gesture.
    var interacting by remember(currentNote.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.toInt(), screenY.toInt()) }
            .width((actualWidth * currentZoom).dp)
            .height(cardHeightDp)
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                // Phase 13: rotation around the item centre (cheap transform,
                // no full redraw) — also rotates the touch area so hit-testing
                // and drag deltas arrive in the rotated local frame.
                rotationZ = liveRotation
            }
            .semantics { contentDescription = "Sticky note" }
            .pointerInput(currentNote.id) {
                // Observes touches WITHOUT consuming them: any touch-down on
                // the item reveals the resize handles; the pointer going up or
                // a cancelled gesture hides them again.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    interacting = true
                    waitForUpOrCancellation()
                    interacting = false
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(4.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(noteColor)
                .border(1.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(currentNote.id) {
                            detectDragGestures(
                                onDragStart = {
                                    dragOffsetX = 0f
                                    dragOffsetY = 0f
                                    interacting = true
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    // Phase 13: dragAmount arrives in the card's
                                    // rotated local frame; un-rotate it so the
                                    // note follows the finger in world space.
                                    val rad = Math.toRadians((-liveRotation).toDouble())
                                    val cosA = cos(rad).toFloat()
                                    val sinA = sin(rad).toFloat()
                                    dragOffsetX += (dragAmount.x * cosA - dragAmount.y * sinA) / currentZoom
                                    dragOffsetY += (dragAmount.x * sinA + dragAmount.y * cosA) / currentZoom
                                },
                                onDragEnd = {
                                    val finalX = currentNote.x + dragOffsetX
                                    val finalY = currentNote.y + dragOffsetY
                                    dragOffsetX = 0f
                                    dragOffsetY = 0f
                                    interacting = false

                                    val updated = currentNote.copy(x = finalX, y = finalY, width = resizeWidth, height = resizeHeight)
                                    val index = activeStickyNoteList.indexOfFirst { it.id == currentNote.id }
                                    if (index != -1) {
                                        activeStickyNoteList[index] = updated
                                    }
                                    val otherNotes = if (isContinuousMode) emptyList() else stickyNotes.filter { it.pdfPage != pdfPageFilter }
                                    currentOnStickyNotesChanged(otherNotes + activeStickyNoteList.toList())
                                },
                                onDragCancel = {
                                    dragOffsetX = 0f
                                    dragOffsetY = 0f
                                    interacting = false
                                }
                            )
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.StickyNote2,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.Black.copy(alpha = 0.7f)
                        )
                        if (currentNote.isCollapsed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentNote.text.ifBlank { "Sticky Note" },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.Black.copy(alpha = 0.85f),
                                    fontSize = (11 * currentZoom).sp
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                dialogTextState = currentNote.text
                                dialogColorHexState = currentNote.colorHex
                                showEditDialog = true
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Edit Sticky Note",
                                tint = Color.Black.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        IconButton(
                            onClick = {
                                val updated = currentNote.copy(isCollapsed = !currentNote.isCollapsed)
                                val index = activeStickyNoteList.indexOfFirst { it.id == currentNote.id }
                                if (index != -1) {
                                    activeStickyNoteList[index] = updated
                                }
                                val otherNotes = if (isContinuousMode) emptyList() else stickyNotes.filter { it.pdfPage != pdfPageFilter }
                                currentOnStickyNotesChanged(otherNotes + activeStickyNoteList.toList())
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                if (currentNote.isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                                contentDescription = if (currentNote.isCollapsed) "Expand Note" else "Collapse Note",
                                tint = Color.Black.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        IconButton(
                            onClick = {
                                activeStickyNoteList.remove(currentNote)
                                val otherNotes = if (isContinuousMode) emptyList() else stickyNotes.filter { it.pdfPage != pdfPageFilter }
                                currentOnStickyNotesChanged(otherNotes + activeStickyNoteList.toList())
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Delete Sticky Note",
                                tint = Color.Black.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                if (!currentNote.isCollapsed) {
                    Spacer(modifier = Modifier.height(4.dp))
                    var noteTextState by remember(currentNote.id, currentNote.text) { mutableStateOf(currentNote.text) }

                    androidx.compose.foundation.text.BasicTextField(
                        value = noteTextState,
                        onValueChange = { newText ->
                            noteTextState = newText
                            val updated = currentNote.copy(text = newText)
                            val index = activeStickyNoteList.indexOfFirst { it.id == currentNote.id }
                            if (index != -1) {
                                activeStickyNoteList[index] = updated
                            }
                            val otherNotes = if (isContinuousMode) emptyList() else stickyNotes.filter { it.pdfPage != pdfPageFilter }
                            currentOnStickyNotesChanged(otherNotes + activeStickyNoteList.toList())
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 12.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.Black.copy(alpha = 0.9f),
                            fontSize = (12 * currentZoom).sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                        ),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (noteTextState.isEmpty()) {
                                    Text(
                                        text = "Type sticky note...",
                                        style = androidx.compose.ui.text.TextStyle(
                                            color = Color.Black.copy(alpha = 0.4f),
                                            fontSize = (12 * currentZoom).sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        }

        // Phase 13: push-pin accent (peeks above the note) + dog-ear fold.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-10).dp)
                .size(16.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
        )
        Canvas(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
        ) {
            val fold = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width * 0.45f, size.height * 0.55f)
                lineTo(size.width * 0.55f, 0f)
                close()
            }
            drawPath(fold, Color.Black.copy(alpha = 0.10f))
            drawLine(
                Color.Black.copy(alpha = 0.20f),
                start = Offset(size.width * 0.55f, 0f),
                end = Offset(size.width * 0.45f, size.height * 0.55f),
                strokeWidth = 1.2f
            )
        }

        // Resize Handle (Bottom-Right) — Phase 193: hidden at rest, revealed
        // while the item is being touched/dragged/resized (shared policy).
        if (!currentNote.isCollapsed) {
            val handleVisible = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.shouldShow(
                interacting = interacting,
                collapsed = currentNote.isCollapsed
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(currentNote.id) {
                        detectDragGestures(
                            onDragStart = { interacting = true },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                resizeWidth = (resizeWidth + dragAmount.x / currentZoom).coerceAtLeast(120f)
                                resizeHeight = (resizeHeight + dragAmount.y / currentZoom).coerceAtLeast(80f)
                            },
                            onDragEnd = {
                                val updated = currentNote.copy(width = resizeWidth, height = resizeHeight)
                                val index = activeStickyNoteList.indexOfFirst { it.id == currentNote.id }
                                if (index != -1) {
                                    activeStickyNoteList[index] = updated
                                }
                                val otherNotes = if (isContinuousMode) emptyList() else stickyNotes.filter { it.pdfPage != pdfPageFilter }
                                currentOnStickyNotesChanged(otherNotes + activeStickyNoteList.toList())
                                interacting = false
                            },
                            onDragCancel = { interacting = false }
                        )
                    }
                    .graphicsLayer {
                        alpha = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.handleAlpha(handleVisible)
                    }
                    .background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AspectRatio,
                    contentDescription = "Resize Sticky Note",
                    tint = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Phase 13: rotation handle (top-center, above the note). Phase 193:
        // gated by the shared resize-handle visibility policy like the corners.
        if (!currentNote.isCollapsed) {
            RotationHandle(
                modifier = Modifier.align(Alignment.TopCenter),
                cardWidthPx = with(LocalDensity.current) { (actualWidth * currentZoom).dp.toPx() },
                cardHeightPx = with(LocalDensity.current) { cardHeightDp.toPx() },
                rotationDegrees = liveRotation,
                zoomScale = currentZoom,
                onRotationChange = { liveRotation = it },
                onRotationCommit = commitRotation,
                visible = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.shouldShow(
                    interacting = interacting,
                    collapsed = currentNote.isCollapsed
                ),
                onInteractionChange = { interacting = it }
            )
        }
    }
}

@Composable
private fun DraggableMediaEmbedCard(
    embed: CanvasMediaEmbed,
    zoomScale: Float,
    panOffset: Offset,
    isContinuousMode: Boolean,
    pdfPageFilter: Int,
    mediaEmbeds: List<CanvasMediaEmbed>,
    activeMediaEmbedList: androidx.compose.runtime.snapshots.SnapshotStateList<CanvasMediaEmbed>,
    activeStrokeList: List<Stroke>,
    isPlayingVoice: Boolean,
    activeVoicePlaybackFilePath: String?,
    activeVoicePositionMsProvider: () -> Long,
    activeVoiceSpeed: Float,
    onMediaEmbedsChanged: (List<CanvasMediaEmbed>) -> Unit,
    onToggleVoicePlay: (String) -> Unit,
    onVoiceSeekTo: (Long) -> Unit,
    onVoiceSpeedChange: (Float) -> Unit,
    onExtractOcr: ((String) -> Unit)? = null
) {
    val currentEmbed by rememberUpdatedState(embed)
    val currentZoom by rememberUpdatedState(zoomScale)
    val currentPan by rememberUpdatedState(panOffset)
    val currentOnMediaEmbedsChanged by rememberUpdatedState(onMediaEmbedsChanged)

    // Phase 217: haptic tick on handle grab (same pattern as AnnotationCanvas line 264).
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    var resizeWidth by remember(currentEmbed.width, currentEmbed.isCollapsed) {
        mutableFloatStateOf(
            if (currentEmbed.isCollapsed) 220f else (if (currentEmbed.width > 0) currentEmbed.width else 320f)
        )
    }
    var resizeHeight by remember(currentEmbed.height, currentEmbed.isCollapsed) {
        mutableFloatStateOf(
            if (currentEmbed.isCollapsed) {
                54f
            } else {
                if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) {
                    if (currentEmbed.height in 90f..280f) currentEmbed.height else 135f
                } else {
                    if (currentEmbed.height > 0) currentEmbed.height else 240f
                }
            }
        )
    }

    // Phase 217: PHOTO aspect-lock — when locked, width/height drag respects
    // the initial aspect ratio (rawW/rawH from PhotoEmbedCard decode).
    var photoAspectLocked by remember(currentEmbed.id) { mutableStateOf(false) }
    val photoAspect by remember(currentEmbed.width, currentEmbed.height) {
        mutableFloatStateOf(
            if (currentEmbed.width > 0f && currentEmbed.height > 0f) {
                currentEmbed.width / currentEmbed.height
            } else 4f / 3f
        )
    }

    // Phase 217: min/max boundary feedback — fires once per boundary hit during resize.
    var lastMinMaxFeedback by remember(currentEmbed.id) { mutableStateOf("") }

    // Phase 13: live rotation during handle drag; committed on drag end.
    var liveRotation by remember(currentEmbed.rotationDegrees) { mutableFloatStateOf(currentEmbed.rotationDegrees) }
    val commitRotation: (Float) -> Unit = { newRot ->
        val updated = currentEmbed.copy(rotationDegrees = newRot)
        val index = activeMediaEmbedList.indexOfFirst { it.id == currentEmbed.id }
        if (index != -1) {
            activeMediaEmbedList[index] = updated
        }
        val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
        currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
    }

    val screenX = (currentEmbed.x + dragOffsetX) * currentZoom + currentPan.x
    val screenY = (currentEmbed.y + dragOffsetY) * currentZoom + currentPan.y
    val actualWidth = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE && currentEmbed.isCollapsed) 48f else resizeWidth
    val actualHeight = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE && currentEmbed.isCollapsed) 48f else resizeHeight

    // Phase 193: resize handles are INVISIBLE at rest and appear only while
    // the item is being touched/dragged/resized (shared policy). The
    // observation reveals on touch-down and hides on pointer-up/cancel; each
    // drag gesture also toggles the flag so a resize/rotation started on the
    // still-composed handle hit-box keeps the handles visible during the drag.
    var interacting by remember(currentEmbed.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.toInt(), screenY.toInt()) }
            .width((actualWidth * currentZoom).dp)
            .height((actualHeight * currentZoom).dp)
            .graphicsLayer {
                // Phase 13: rotation around the item centre; also rotates the
                // touch area, so drag deltas arrive in the rotated local frame.
                rotationZ = liveRotation
            }
            .pointerInput(currentEmbed.id) {
                // Observes touches WITHOUT consuming them: any touch-down on
                // the item reveals the resize handles; pointer-up/cancel hides.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    interacting = true
                    waitForUpOrCancellation()
                    interacting = false
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentEmbed.id) {
                    detectDragGestures(
                        onDragStart = {
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            interacting = true
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // Phase 13: dragAmount is in the card's rotated local
                            // frame; un-rotate it so the item follows the finger.
                            val rad = Math.toRadians((-liveRotation).toDouble())
                            val cosA = cos(rad).toFloat()
                            val sinA = sin(rad).toFloat()
                            dragOffsetX += (dragAmount.x * cosA - dragAmount.y * sinA) / currentZoom
                            dragOffsetY += (dragAmount.x * sinA + dragAmount.y * cosA) / currentZoom
                        },
                        onDragEnd = {
                            val finalX = currentEmbed.x + dragOffsetX
                            val finalY = currentEmbed.y + dragOffsetY
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            interacting = false

                            val updated = currentEmbed.copy(x = finalX, y = finalY, width = resizeWidth, height = resizeHeight)
                            val index = activeMediaEmbedList.indexOfFirst { it.id == currentEmbed.id }
                            if (index != -1) {
                                activeMediaEmbedList[index] = updated
                            }
                            val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                            currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
                        },
                        onDragCancel = {
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            interacting = false
                        }
                    )
                }
        ) {
            when (currentEmbed.type) {
                MediaEmbedType.PHOTO -> {
                    PhotoEmbedCard(
                        modifier = Modifier.fillMaxSize(),
                        embed = currentEmbed,
                        zoomScale = currentZoom,
                        onUpdateCaption = { newCaption ->
                            val updated = currentEmbed.copy(textContent = newCaption, width = resizeWidth, height = resizeHeight)
                            val index = activeMediaEmbedList.indexOfFirst { it.id == currentEmbed.id }
                            if (index != -1) {
                                activeMediaEmbedList[index] = updated
                                val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                                currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
                            }
                        },
                        onDelete = {
                            activeMediaEmbedList.remove(currentEmbed)
                            val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                            currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
                        },
                        onExtractOcr = onExtractOcr
                    )
                }
                MediaEmbedType.CODE_BLOCK -> {
                    CodeBlockCard(
                        modifier = Modifier.fillMaxSize(),
                        embed = currentEmbed,
                        zoomScale = currentZoom,
                        onUpdateCode = { newCode, newLang ->
                            val updated = currentEmbed.copy(textContent = newCode, codeLanguage = newLang, width = resizeWidth, height = resizeHeight)
                            val index = activeMediaEmbedList.indexOfFirst { it.id == currentEmbed.id }
                            if (index != -1) {
                                activeMediaEmbedList[index] = updated
                                val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                                currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
                            }
                        },
                        onDelete = {
                            activeMediaEmbedList.remove(currentEmbed)
                            val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                            currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
                        }
                    )
                }
                MediaEmbedType.AUDIO_NOTE -> {
                    val syncedCount = activeStrokeList.count { it.timestampMs != null }
                    AudioPlaybackCard(
                        modifier = Modifier.fillMaxSize(),
                        embed = currentEmbed,
                        syncedStrokesCount = syncedCount,
                        isPlaying = isPlayingVoice && activeVoicePlaybackFilePath == currentEmbed.contentUrlOrPath,
                        currentPositionMsProvider = { if (activeVoicePlaybackFilePath == currentEmbed.contentUrlOrPath) activeVoicePositionMsProvider() else 0L },
                        playbackSpeed = activeVoiceSpeed,
                        onTogglePlay = {
                            currentEmbed.contentUrlOrPath?.let { onToggleVoicePlay(it) }
                        },
                        onSeekTo = onVoiceSeekTo,
                        onSpeedChange = onVoiceSpeedChange,
                        onToggleCollapse = {
                            val updated = currentEmbed.copy(isCollapsed = !currentEmbed.isCollapsed)
                            val index = activeMediaEmbedList.indexOfFirst { it.id == currentEmbed.id }
                            if (index != -1) {
                                activeMediaEmbedList[index] = updated
                                val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                                currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
                            }
                        },
                        onDelete = {
                            activeMediaEmbedList.remove(currentEmbed)
                            val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                            currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
                        }
                    )
                }
                MediaEmbedType.STICKY_NOTE -> {
                    // Handled via DraggableStickyNoteCard
                }
                MediaEmbedType.STICKER -> {
                    // Phase 13: emoji sticker rendered with the platform font
                    // (offline, zero image assets). Scales with the card size.
                    val sticker = com.authorss81.noteflow.services.StickerCatalog.byId(currentEmbed.contentUrlOrPath)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                            .border(1.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sticker?.emoji ?: "\u2728",
                            fontSize = (min(actualWidth, actualHeight) * currentZoom * 0.62f).coerceAtLeast(8f).sp,
                            maxLines = 1
                        )
                        // Small delete affordance so placements are never stuck.
                        IconButton(
                            onClick = {
                                activeMediaEmbedList.remove(currentEmbed)
                                val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                                currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(22.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Delete Sticker",
                                tint = Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                MediaEmbedType.REFERENCE_IMAGE -> {
                    // Phase 178: never a draggable card — the underlay is filtered
                    // out of the editor's embed set and drawn in the canvas body.
                }
            }
        }

        if (!(currentEmbed.type == MediaEmbedType.AUDIO_NOTE && currentEmbed.isCollapsed)) {
            // Phase 193: the four corner resize handles are INVISIBLE at rest
            // (shared ResizeHandleVisibilityPolicy); they reveal while the item
            // is being touched/dragged/resized. The hit-boxes stay composed so
            // a resize gesture can still START on a corner.
            val cornerVisible = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.shouldShow(
                interacting = interacting,
                collapsed = currentEmbed.isCollapsed
            )
            val minW = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) 220f else 120f
            val maxW = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) 420f else 2000f
            val minH = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) 100f else 80f
            val maxH = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) 280f else 2000f

            val saveCurrentEmbedState = {
                val finalX = currentEmbed.x + dragOffsetX
                val finalY = currentEmbed.y + dragOffsetY
                dragOffsetX = 0f
                dragOffsetY = 0f
                interacting = false
                val updated = currentEmbed.copy(x = finalX, y = finalY, width = resizeWidth, height = resizeHeight)
                val index = activeMediaEmbedList.indexOfFirst { it.id == currentEmbed.id }
                if (index != -1) {
                    activeMediaEmbedList[index] = updated
                }
                val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(currentEmbed.id) {
                        detectDragGestures(
                            onDragStart = {
                                interacting = true
                                // Phase 217: haptic tick on handle grab.
                                if (!reduceMotion) {
                                    hapticFeedback.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                                    )
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val prevW = resizeWidth
                                val prevH = resizeHeight
                                var newW = (resizeWidth + dragAmount.x / currentZoom).coerceIn(minW, maxW)
                                var newH = (resizeHeight + dragAmount.y / currentZoom).coerceIn(minH, maxH)
                                // Phase 217: PHOTO aspect-lock enforcement.
                                if (photoAspectLocked && currentEmbed.type == MediaEmbedType.PHOTO) {
                                    newH = (newW / photoAspect).coerceIn(minH, maxH)
                                    newW = (newH * photoAspect).coerceIn(minW, maxW)
                                }
                                resizeWidth = newW
                                resizeHeight = newH
                                // Phase 217: min/max boundary detection (fires once per hit).
                                val feedback = when {
                                    newW <= minW -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MIN_WIDTH_TOAST
                                    newW >= maxW -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MAX_WIDTH_TOAST
                                    newH <= minH -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MIN_HEIGHT_TOAST
                                    newH >= maxH -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MAX_HEIGHT_TOAST
                                    else -> ""
                                }
                                if (feedback.isNotEmpty() && feedback != lastMinMaxFeedback) {
                                    lastMinMaxFeedback = feedback
                                    if (!reduceMotion) {
                                        hapticFeedback.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                        )
                                    }
                                } else if (feedback.isEmpty()) {
                                    lastMinMaxFeedback = ""
                                }
                            },
                            onDragEnd = saveCurrentEmbedState,
                            onDragCancel = { interacting = false }
                        )
                    }
                    .graphicsLayer {
                        alpha = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.handleAlpha(cornerVisible)
                    }
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(topStart = 8.dp, bottomEnd = 12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(topStart = 8.dp, bottomEnd = 12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AspectRatio,
                    contentDescription = "Resize Item",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(24.dp)
                    .pointerInput(currentEmbed.id) {
                        detectDragGestures(
                            onDragStart = {
                                interacting = true
                                if (!reduceMotion) {
                                    hapticFeedback.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                                    )
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / currentZoom
                                val dy = dragAmount.y / currentZoom
                                var newW = (resizeWidth - dx).coerceAtLeast(minW)
                                var newH = (resizeHeight + dy).coerceAtLeast(minH)
                                if (photoAspectLocked && currentEmbed.type == MediaEmbedType.PHOTO) {
                                    newH = (newW / photoAspect).coerceIn(minH, maxH)
                                    newW = (newH * photoAspect).coerceAtLeast(minW)
                                }
                                dragOffsetX += (resizeWidth - newW)
                                resizeWidth = newW
                                resizeHeight = newH
                                val feedback = when {
                                    newW <= minW -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MIN_WIDTH_TOAST
                                    newW >= maxW -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MAX_WIDTH_TOAST
                                    newH <= minH -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MIN_HEIGHT_TOAST
                                    newH >= maxH -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MAX_HEIGHT_TOAST
                                    else -> ""
                                }
                                if (feedback.isNotEmpty() && feedback != lastMinMaxFeedback) {
                                    lastMinMaxFeedback = feedback
                                    if (!reduceMotion) {
                                        hapticFeedback.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                        )
                                    }
                                } else if (feedback.isEmpty()) {
                                    lastMinMaxFeedback = ""
                                }
                            },
                            onDragEnd = saveCurrentEmbedState,
                            onDragCancel = { interacting = false }
                        )
                    }
                    .graphicsLayer {
                        alpha = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.handleAlpha(cornerVisible)
                    }
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(topEnd = 8.dp, bottomStart = 12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(topEnd = 8.dp, bottomStart = 12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AspectRatio,
                    contentDescription = "Resize Item",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .pointerInput(currentEmbed.id) {
                        detectDragGestures(
                            onDragStart = {
                                interacting = true
                                if (!reduceMotion) {
                                    hapticFeedback.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                                    )
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / currentZoom
                                val dy = dragAmount.y / currentZoom
                                var newW = (resizeWidth + dx).coerceAtLeast(minW)
                                var newH = (resizeHeight - dy).coerceAtLeast(minH)
                                if (photoAspectLocked && currentEmbed.type == MediaEmbedType.PHOTO) {
                                    newH = (newW / photoAspect).coerceAtLeast(minH)
                                    newW = (newH * photoAspect).coerceAtLeast(minW)
                                }
                                val newOffsetY = resizeHeight - newH
                                dragOffsetY += newOffsetY
                                resizeWidth = newW
                                resizeHeight = newH
                                val feedback = when {
                                    newW <= minW -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MIN_WIDTH_TOAST
                                    newW >= maxW -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MAX_WIDTH_TOAST
                                    newH <= minH -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MIN_HEIGHT_TOAST
                                    newH >= maxH -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MAX_HEIGHT_TOAST
                                    else -> ""
                                }
                                if (feedback.isNotEmpty() && feedback != lastMinMaxFeedback) {
                                    lastMinMaxFeedback = feedback
                                    if (!reduceMotion) {
                                        hapticFeedback.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                        )
                                    }
                                } else if (feedback.isEmpty()) {
                                    lastMinMaxFeedback = ""
                                }
                            },
                            onDragEnd = saveCurrentEmbedState,
                            onDragCancel = { interacting = false }
                        )
                    }
                    .graphicsLayer {
                        alpha = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.handleAlpha(cornerVisible)
                    }
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(bottomStart = 8.dp, topEnd = 12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 8.dp, topEnd = 12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AspectRatio,
                    contentDescription = "Resize Item",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(24.dp)
                    .pointerInput(currentEmbed.id) {
                        detectDragGestures(
                            onDragStart = {
                                interacting = true
                                if (!reduceMotion) {
                                    hapticFeedback.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                                    )
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / currentZoom
                                val dy = dragAmount.y / currentZoom
                                var newW = (resizeWidth - dx).coerceAtLeast(minW)
                                var newH = (resizeHeight - dy).coerceAtLeast(minH)
                                if (photoAspectLocked && currentEmbed.type == MediaEmbedType.PHOTO) {
                                    newH = (newW / photoAspect).coerceAtLeast(minH)
                                    newW = (newH * photoAspect).coerceAtLeast(minW)
                                }
                                val newOffsetX = resizeWidth - newW
                                val newOffsetY = resizeHeight - newH
                                dragOffsetX += newOffsetX
                                dragOffsetY += newOffsetY
                                resizeWidth = newW
                                resizeHeight = newH
                                val feedback = when {
                                    newW <= minW -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MIN_WIDTH_TOAST
                                    newW >= maxW -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MAX_WIDTH_TOAST
                                    newH <= minH -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MIN_HEIGHT_TOAST
                                    newH >= maxH -> com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.RESIZE_MAX_HEIGHT_TOAST
                                    else -> ""
                                }
                                if (feedback.isNotEmpty() && feedback != lastMinMaxFeedback) {
                                    lastMinMaxFeedback = feedback
                                    if (!reduceMotion) {
                                        hapticFeedback.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                        )
                                    }
                                } else if (feedback.isEmpty()) {
                                    lastMinMaxFeedback = ""
                                }
                            },
                            onDragEnd = saveCurrentEmbedState,
                            onDragCancel = { interacting = false }
                        )
                    }
                    .graphicsLayer {
                        alpha = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.handleAlpha(cornerVisible)
                    }
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(bottomEnd = 8.dp, topStart = 12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(bottomEnd = 8.dp, topStart = 12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AspectRatio,
                    contentDescription = "Resize Item",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Phase 13: rotation handle (top-center, above the item). Phase 193:
        // gated by the shared resize-handle visibility policy like the corners.
        if (!(currentEmbed.type == MediaEmbedType.AUDIO_NOTE && currentEmbed.isCollapsed)) {
            RotationHandle(
                modifier = Modifier.align(Alignment.TopCenter),
                cardWidthPx = with(LocalDensity.current) { (actualWidth * currentZoom).dp.toPx() },
                cardHeightPx = with(LocalDensity.current) { (actualHeight * currentZoom).dp.toPx() },
                rotationDegrees = liveRotation,
                zoomScale = currentZoom,
                onRotationChange = { liveRotation = it },
                onRotationCommit = commitRotation,
                visible = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.shouldShow(
                    interacting = interacting,
                    collapsed = currentEmbed.isCollapsed
                ),
                onInteractionChange = { interacting = it }
            )
        }
    }
}

/**
 * Phase 13: rotation handle rendered above the card's top-centre. Dragging it
 * computes the item's new absolute rotation from the pointer position (see
 * [com.authorss81.noteflow.services.CanvasItemRotationMath.rotationFromHandleDrag]).
 * The card's graphicsLayer rotation also rotates this handle's hit area, which
 * the math accounts for via [currentDegrees].
 */
@Composable
private fun RotationHandle(
    cardWidthPx: Float,
    cardHeightPx: Float,
    rotationDegrees: Float,
    zoomScale: Float,
    modifier: Modifier = Modifier,
    onRotationChange: (Float) -> Unit,
    onRotationCommit: (Float) -> Unit,
    handleSizeDp: androidx.compose.ui.unit.Dp = 26.dp,
    gapDp: androidx.compose.ui.unit.Dp = 8.dp,
    visible: Boolean = true,
    onInteractionChange: (Boolean) -> Unit = {}
) {
    val density = LocalDensity.current
    val handlePx = with(density) { handleSizeDp.toPx() }
    val gapPx = with(density) { gapDp.toPx() }
    var dragHasMoved by remember { mutableStateOf(false) }

    // Read through updated-state so a mid-drag rotation change on the parent
    // recomposes without restarting (cancelling) the active gesture.
    val currentRotation by rememberUpdatedState(rotationDegrees)
    val currentCardHeightPx by rememberUpdatedState(cardHeightPx)
    val currentZoom by rememberUpdatedState(zoomScale)
    val currentOnRotationChange by rememberUpdatedState(onRotationChange)
    val currentOnRotationCommit by rememberUpdatedState(onRotationCommit)
    val currentVisible by rememberUpdatedState(visible)
    val currentOnInteractionChange by rememberUpdatedState(onInteractionChange)

    Box(
        modifier = modifier
            .offset(y = -(handleSizeDp + gapDp))
            .size(handleSizeDp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragHasMoved = false
                        currentOnInteractionChange(true)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragHasMoved = true
                        val newRotation = com.authorss81.noteflow.services.CanvasItemRotationMath.rotationFromHandleDrag(
                            handleCenterRelCardCenterX = 0f,
                            handleCenterRelCardCenterY = -(gapPx + handlePx / 2f + currentCardHeightPx / 2f),
                            pointerRelHandleCenterX = change.position.x - handlePx / 2f,
                            pointerRelHandleCenterY = change.position.y - handlePx / 2f,
                            zoom = currentZoom,
                            currentDegrees = currentRotation
                        )
                        currentOnRotationChange(newRotation)
                    },
                    onDragEnd = {
                        if (dragHasMoved) currentOnRotationCommit(currentRotation)
                        dragHasMoved = false
                        currentOnInteractionChange(false)
                    },
                    onDragCancel = {
                        dragHasMoved = false
                        currentOnInteractionChange(false)
                    }
                )
            }
            .graphicsLayer {
                alpha = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.handleAlpha(currentVisible)
            }
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.RotateRight,
            contentDescription = "Rotate Item",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(13.dp)
        )
    }
}

/**
 * AIRBRUSH dwell density (Phase 18): points with long timestamp gaps mean the
 * brush was held still — return a point list with a few extra deposited stamps
 * around the held point. Uses the existing stamp engine (no new engine).
 */
private fun buildAirbrushDensePoints(points: List<com.authorss81.noteflow.data.model.PointF>): List<com.authorss81.noteflow.data.model.PointF> {
    val out = mutableListOf<com.authorss81.noteflow.data.model.PointF>()
    out.add(points.first())
    var i = 0
    while (i < points.size - 1) {
        val cur = points[i]
        val nxt = points[i + 1]
        val dt = (nxt.timestampMs ?: 0L) - (cur.timestampMs ?: 0L)
        if (dt > 120L) {
            val extra = ((dt - 120L) / 60L).toInt().coerceIn(1, 6)
            for (k in 0 until extra) {
                val jx = ((k % 3) - 1) * 2.5f
                val jy = ((k % 2) * 2 - 1) * 2.5f
                out.add(
                    com.authorss81.noteflow.data.model.PointF(
                        cur.x + jx, cur.y + jy, cur.pressure, cur.tilt, cur.timestampMs
                    )
                )
            }
        }
        out.add(nxt)
        i++
    }
    return out
}

/** Average captured pressure of a stroke (defaults to full pressure for legacy strokes). */
private fun meanPointPressure(points: List<com.authorss81.noteflow.data.model.PointF>): Float {
    if (points.isEmpty()) return 1f
    val pressures = points.mapNotNull { it.pressure }
    return if (pressures.isEmpty()) 1f else pressures.average().toFloat()
}

/**
 * Phase 18 render options for [drawSingleStroke]: velocity width modulation and
 * nib angles for the chiselled/calligraphic tools. Defaults reproduce the classic
 * look exactly (velocity off, 45deg/30deg nibs).
 */
private data class StrokeRenderOpts(
    val velocityModulated: Boolean = false,
    val velocityIntensity: Float = 1f,
    val nibAngleDeg: Float = 45f,
    val chiselNibAngleDeg: Float = 30f,
    // Phase 222: stylus tilt → width/alpha modulation.
    val tiltShadingEnabled: Boolean = false
)
