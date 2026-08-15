@file:android.annotation.SuppressLint("RestrictedApi", "NewApi")
package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStrokeStyle
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.CanvasMediaEmbed
import com.authorss81.noteflow.services.BrushTextureEngine
import com.authorss81.noteflow.services.PressureCurve
import com.authorss81.noteflow.services.PressureCurveHelper
import com.authorss81.noteflow.services.ProtobufBrushLoader
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    onStrokesChanged: (List<Stroke>) -> Unit,
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
    shapeAutoSnapEnabled: Boolean = true,
    hapticsEnabled: Boolean = true,
    stabilizerEnabled: Boolean = false,
    pressureCurve: PressureCurve = PressureCurve.LINEAR,
    symmetryMode: SymmetryMode = SymmetryMode.OFF,
    // Phase 13: rich canvas content.
    selectedStickerId: String? = null,
    onPlaceSticker: (Offset, Int) -> Unit = { _, _ -> },
    activeBrushPresetId: String? = null,
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
    currentGradientToColor: Color = Color(0xFF1B365D)
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

    val showPageIndicatorState = remember { mutableStateOf(false) }
    var showPageIndicator by showPageIndicatorState
    LaunchedEffect(internalPanOffset, internalZoomScale) {
        showPageIndicator = true
        kotlinx.coroutines.delay(2000)
        showPageIndicator = false
    }

    val hasLaserStrokes = strokes.any { it.tool == StrokeTool.LASER }
    LaunchedEffect(hasLaserStrokes, strokes.size) {
        if (hasLaserStrokes) {
            while (true) {
                val now = System.currentTimeMillis()
                val expiredIds = strokes.filter { it.tool == StrokeTool.LASER && it.timestampMs != null && (now - it.timestampMs) >= 1800L }.map { it.id }.toSet()
                if (expiredIds.isNotEmpty()) {
                    val remaining = strokes.filterNot { it.id in expiredIds }
                    onStrokesChanged(remaining)
                }
                kotlinx.coroutines.delay(40L)
            }
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
    val eraseSamples = remember { mutableStateListOf<androidx.compose.ui.geometry.Offset>() }

    // Phase 07: stroke stabilizer (one filter instance per continuous stroke).
    val stabilizerFilter = remember { StrokeStabilizer.create() }

    // Eyedropper Magnifying Loupe State
    var sampledColorPreview by remember { mutableStateOf<Color?>(null) }
    var eyedropperPosition by remember { mutableStateOf<Offset?>(null) }

    var showTextInputDialog by remember { mutableStateOf(false) }
    var textInputOffset by remember { mutableStateOf<Offset?>(null) }
    var textValue by remember { mutableStateOf("") }
    var textFontStyle by remember { mutableStateOf("SANS") }
    var textFontSizeSp by remember { mutableFloatStateOf(20f) }
    var textBgHex by remember { mutableStateOf<String?>(null) }
    var textAlign by remember { mutableStateOf("LEFT") }
    var textSelectedColorInt by remember { mutableIntStateOf(currentColor.toArgb()) }

    // Floating Sticky Note Creation Dialog
    var showStickyNoteDialog by remember { mutableStateOf(false) }
    var stickyNoteOffset by remember { mutableStateOf<Offset?>(null) }
    var stickyNoteText by remember { mutableStateOf("") }
    var stickyNoteColorHex by remember { mutableStateOf("#FEF08A") } // Default Yellow

    // Minimap Collapsible State
    var minimapExpanded by remember { mutableStateOf(true) }

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

    // Color sampling helper for Eyedropper tool
    // Phase 27: samples the ACTUAL rendered pixel (stroked ink composited over the
    // page background) instead of guessing via a loose point-in-+18px radius. The
    // inverse screen->canvas transform (divide by zoom) lives in
    // EyedropperSamplingMath so tests can prove it round-trips exactly.
    fun sampleColorAt(canvasOffset: Offset, targetPage: Int): Color {
        val pageTopY = calculatePageYOffset(targetPage)
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
                    // ignore and fall back to paper
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

    val layerBitmapCache = remember { mutableMapOf<String, com.authorss81.noteflow.ui.components.LayerBitmapCache>() }
    // Phase 19: vibrancy is baked into the layer bitmaps, so a vibe change
    // invalidates the cache the same way a stroke/layer change does.
    LaunchedEffect(strokes, layers, vibrancyBoost) {
        layerBitmapCache.values.forEach {
            com.authorss81.noteflow.utils.BitmapPool.release(it.bitmap.asAndroidBitmap())
        }
        layerBitmapCache.clear()
    }
    val wetMixingEffect = remember {
        if (ShaderCapabilityHelper.isAgslSupported) AgslShaders.WetMixingEffect() else null
    }
    val wetCanvasEngine = remember { com.authorss81.noteflow.services.WetCanvasEngine() }
    val wetBrushEngine = remember { com.authorss81.noteflow.services.WetBrushEngine() }
    val graphicsLayer = rememberGraphicsLayer()

    // Phase 13: when a ready-made brush preset is active, pre-fill the wet
    // engine's BrushStudio params. With no preset (null) the engine keeps its
    // default/manual params, so classic brush rendering is unchanged.
    LaunchedEffect(activeBrushPresetId) {
        val preset = activeBrushPresetId?.let { com.authorss81.noteflow.services.BrushPresetPack.byId(it) }
        if (preset != null) {
            wetCanvasEngine.brushParams = preset.brushParams
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    // Phase 18: brush-physics render settings — velocity width modulation, nib angle.
    // Persisted via the existing SettingsManager (SharedPreferences) path; NO DB schema change.
    val brushRenderSettings = remember(context) { com.authorss81.noteflow.services.SettingsManager(context) }
    var velocityModulated by remember { mutableStateOf(brushRenderSettings.velocityModulationEnabled) }
    var velocityIntensity by remember { mutableFloatStateOf(brushRenderSettings.velocityModulationIntensity) }
    var nibAngleDeg by remember { mutableFloatStateOf(brushRenderSettings.calligraphicNibAngleDeg) }
    var chiselNibAngleDeg by remember { mutableFloatStateOf(brushRenderSettings.chiselNibAngleDeg) }
    val strokeRenderOpts = com.authorss81.noteflow.ui.components.StrokeRenderOpts(
        velocityModulated = velocityModulated,
        velocityIntensity = velocityIntensity,
        nibAngleDeg = nibAngleDeg,
        chiselNibAngleDeg = chiselNibAngleDeg
    )
    // Per-stroke texture seed — refreshed on drag start so each live stroke owns a
    // fresh grain/bristle phase (the committed copy gets one from its stroke id).
    var currentStrokeSeed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val choreographer = android.view.Choreographer.getInstance()
            var lastFrameTimeNanos = 0L
            val frameCallback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (lastFrameTimeNanos != 0L) {
                        val elapsedMs = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000f
                        wetBrushEngine.recordFrameTime(elapsedMs)
                    }
                    lastFrameTimeNanos = frameTimeNanos

                    val thermalStatus = ThermalSanityHelper.getCurrentThermalStatus(context)
                    wetBrushEngine.updateTierAndFallback(
                        isAgslSupported = ShaderCapabilityHelper.isAgslSupported,
                        thermalStatus = thermalStatus,
                        manualOverrideEnabled = gpuWetBrushesEnabled,
                        currentTimeMs = System.currentTimeMillis()
                    )

                    choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(frameCallback)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            layerBitmapCache.values.forEach {
                com.authorss81.noteflow.utils.BitmapPool.release(it.bitmap.asAndroidBitmap())
            }
            layerBitmapCache.clear()
        }
    }
    var showBrushStudio by remember { mutableStateOf(false) }
    val canvasDrawScope = remember { androidx.compose.ui.graphics.drawscope.CanvasDrawScope() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
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
                false
            }
            // 1. Two-finger Zoom and Pan Gestures
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
            // 2. Tap Gestures for Text Input, Eyedropper & Sticky Notes
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

                        // 4.1 Check if user tapped a time-synced stroke to jump audio
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
                        }
                    }
                )
            }

            // 3. Drawing / Eyedropper / Single-Finger Pan Gestures
            .pointerInput(currentTool, currentColor, currentWidth, pdfPageFilter, isContinuousMode, activeRawBitmapMap, isLayerLocked, symmetryMode, stabilizerEnabled, eraserMode) {
                // Phase 07: with a view-time mirror active, erasing a stroke must
                // also work through the mirrored copy — the user sees a mirrored
                // stroke and expects to erase it in place. Uses the SAME axis as the
                // renderer (symmetryCenterFor), so the hit-test and the visual
                // mirror always agree.
                val erasesStroke: (Stroke, Offset) -> Boolean = { stroke, offset ->
                    if (symmetryMode == SymmetryMode.OFF) {
                        strokeContainsPoint(stroke, offset)
                    } else {
                        val c = symmetryCenterFor(size.width.toFloat(), offset.y)
                        val m = SymmetryHelper.mirrorPoint(offset.x, offset.y, symmetryMode, c.x, c.y)
                        strokeContainsPoint(stroke, offset) || strokeContainsPoint(stroke, Offset(m.x, m.y))
                    }
                }
                // Phase 19: shared eraser handler. STROKE mode keeps the classic
                // remove-whole-stroke behaviour. PARTIAL mode segments every hit
                // polyline (freehand tools with >= 2 points) into surviving runs
                // using the full accumulated erase path; non-polyline strokes
                // (text, shapes) fall back to whole-stroke removal — an honest
                // gate, matching the classic eraser, not a fake "partial".
                fun applyEraser(canvasOffset: Offset) {
                    val partial = eraserMode == com.authorss81.noteflow.services.EraserMode.PARTIAL
                    val samples = eraseSamples.map {
                        com.authorss81.noteflow.services.StrokeSegmenter.ErasePoint(it.x, it.y)
                    }
                    val newList = mutableListOf<Stroke>()
                    var changed = false
                    for (stroke in activeStrokeList) {
                        if (erasesStroke(stroke, canvasOffset)) {
                            changed = true
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
                                newList.addAll(result.surviving)
                            }
                            // else: stroke is dropped whole (classic eraser behaviour)
                        } else {
                            newList.add(stroke)
                        }
                    }
                    if (changed) {
                        activeStrokeList.clear()
                        activeStrokeList.addAll(newList)
                        val otherStrokes = if (isContinuousMode) emptyList() else strokes.filter { it.pdfPage != pdfPageFilter }
                        onStrokesChanged(otherStrokes + newList)
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
                                 isDraggingCard = true
                                 return@detectDragGestures
                            }
                            isDraggingCard = false

                            if (currentTool == StrokeTool.SELECT || currentTool == StrokeTool.PAN) {
                                // SELECT and PAN tools act as smooth single-finger Pan / Hand tool
                                return@detectDragGestures
                            }
                            val targetPage = getPageFromCanvasY(canvasOffset.y)
                            val targetPageYStart = calculatePageYOffset(targetPage)
                            val targetPageYEnd = targetPageYStart + pageHeightPx

                            // Prevent starting a drawing stroke outside the active page bounds
                            val isStartOutsidePage = canvasOffset.x < 0f || canvasOffset.x > pageWidthPx ||
                                    canvasOffset.y < targetPageYStart || canvasOffset.y > targetPageYEnd

                            if (isStartOutsidePage && currentTool != StrokeTool.EYEDROPPER && currentTool != StrokeTool.ERASER) {
                                isPanningBlackSpace = true
                                activeStart = null
                                activeEnd = null
                                activePoints.clear()
                                return@detectDragGestures
                            }
                            isPanningBlackSpace = false

                            activeTargetPage = targetPage
                            // Phase 07: fresh smoothing window + remapped pressure per stroke.
                            // Reset unconditionally so stale smoothing state can never leak into a
                            // new stroke if the toggle changed mid-stroke.
                            stabilizerFilter.reset()
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
                                eraseSamples.add(canvasOffset)
                                applyEraser(canvasOffset)
                            } else {
                                activeStart = startPoint
                                activeEnd = startPoint
                                activePoints.clear()
                                activePoints.add(startPoint)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            if (isDraggingCard) return@detectDragGestures
                            change.consume()
                            
                            val rawCanvasX = (change.position.x - internalPanOffset.x) / internalZoomScale
                            val rawCanvasY = (change.position.y - internalPanOffset.y) / internalZoomScale

                            if (isPanningBlackSpace || currentTool == StrokeTool.SELECT || currentTool == StrokeTool.PAN) {
                                updateZoomAndPan(internalZoomScale, internalPanOffset + dragAmount)
                                return@detectDragGestures
                            }
                            val targetPageYStart = calculatePageYOffset(activeTargetPage)
                            val targetPageYEnd = targetPageYStart + pageHeightPx

                            // Prevent drawing across page boundaries:
                            // If the pointer coordinates move outside the active target page, do not add points!
                            val isOutsidePage = rawCanvasX < 0f || rawCanvasX > pageWidthPx ||
                                    rawCanvasY < targetPageYStart || rawCanvasY > targetPageYEnd

                            if (isOutsidePage && currentTool != StrokeTool.EYEDROPPER && currentTool != StrokeTool.ERASER) {
                                return@detectDragGestures
                            }

                            val currentPressure = PressureCurveHelper.remapPressure(lastPressure, pressureCurve)
                            val currentPoint = PointF(
                                x = rawCanvasX.coerceIn(0f, pageWidthPx),
                                y = rawCanvasY.coerceIn(targetPageYStart, targetPageYEnd),
                                pressure = currentPressure,
                                tilt = lastTilt,
                                timestampMs = lastTimestampMs
                            )

                            if (currentTool == StrokeTool.EYEDROPPER) {
                                val canvasPosition = Offset(rawCanvasX, rawCanvasY)
                                eyedropperPosition = change.position
                                sampledColorPreview = sampleColorAt(canvasPosition, activeTargetPage)
                            } else if (currentTool == StrokeTool.ERASER) {
                                val canvasPosition = Offset(rawCanvasX, rawCanvasY)
                                eraseSamples.add(canvasPosition)
                                applyEraser(canvasPosition)
                            } else if (currentTool.isFreehandTool) {
                                // Phase 07: stabilizer (per-axis EWMA) smooths touch jitter
                                // while staying responsive; disabled => identical behaviour.
                                val drawPoint = if (stabilizerEnabled) {
                                    val s = stabilizerFilter.next(currentPoint.x, currentPoint.y)
                                    PointF(s.x, s.y, currentPoint.pressure, currentPoint.tilt, currentPoint.timestampMs)
                                } else {
                                    currentPoint
                                }
                                // Vector Stroke Smoothing & Touch jitter filtering: add point if distance > 1.5px
                                val last = activePoints.lastOrNull()
                                val lastTime = if (activePoints.size >= 2) System.currentTimeMillis() - 16L else System.currentTimeMillis() - 100L
                                val curTime = System.currentTimeMillis()

                                if (wetBrushEngine.shouldProcessPoint(last?.let { Offset(it.x, it.y) }, Offset(drawPoint.x, drawPoint.y), lastTime, curTime)) {
                                    if (last != null && com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(currentTool)) {
                                        val interpolated = wetBrushEngine.interpolateSegment(
                                            prev = Offset(last.x, last.y),
                                            cur = Offset(drawPoint.x, drawPoint.y),
                                            radius = currentWidth * 1.5f
                                        )
                                        for (interp in interpolated) {
                                            val interpPt = PointF(
                                                x = interp.x,
                                                y = interp.y,
                                                pressure = currentPressure,
                                                tilt = lastTilt,
                                                timestampMs = lastTimestampMs
                                            )
                                            activePoints.add(interpPt)

                                            wetCanvasEngine.markPaintDeposited(currentTool)
                                        }
                                        activeEnd = activePoints.lastOrNull() ?: drawPoint
                                    } else {
                                        activePoints.add(drawPoint)
                                        activeEnd = drawPoint
                                    }
                                }
                            } else {
                                activeEnd = currentPoint
                             }
                        },
                        onDragEnd = {
                            if (isDraggingCard) {
                                isDraggingCard = false
                                return@detectDragGestures
                            }
                            if (currentTool == StrokeTool.EYEDROPPER) {
                                sampledColorPreview?.let { onColorSampled(it) }
                                eyedropperPosition = null
                                sampledColorPreview = null
                            } else if (currentTool != StrokeTool.ERASER && currentTool != StrokeTool.SELECT) {
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

                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
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
                                        val snappedShape = if (shapeAutoSnapEnabled && tool.isFreehandTool && !isWetOrFleeting && !stylePreservingTool) {
                                            com.authorss81.noteflow.services.ShapeRecognitionHelper.trySnapShape(candidateStroke)
                                        } else {
                                            null
                                        }
                                        val newStroke = if (snappedShape != null) {
                                            snappedShape.snappedStroke
                                        } else if (pointsToSimplify.size > 2) {
                                            candidateStroke.copy(
                                                points = com.authorss81.noteflow.utils.RamerDouglasPeucker.simplify(pointsToSimplify, epsilon = 1.3f)
                                            )
                                        } else {
                                            candidateStroke
                                        }
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            activeStrokeList.add(newStroke)
                                            val otherStrokes = if (isContinuousMode) emptyList() else strokes.filter { it.pdfPage != pdfPageFilter }
                                            onStrokesChanged(otherStrokes + activeStrokeList)
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
                                }
                            }
                            activePoints.clear()
                            activeStart = null
                            activeEnd = null
                            onDrawingEnd()
                        },
                        onDragCancel = {
                            isDraggingCard = false
                            eyedropperPosition = null
                            sampledColorPreview = null
                            activePoints.clear()
                            activeStart = null
                            activeEnd = null
                            onDrawingEnd()
                        }
                    )
                }
            }
    ) {
        val viewHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        val calculatedPageCount = remember(isPdf, pdfTotalPages, isContinuousMode, activeStrokeList.size, layoutPanOffset, layoutZoomScale, viewHeightPx) {
            if (isPdf) {
                kotlin.math.max(1, pdfTotalPages)
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
                val maxY = kotlin.math.max(maxStrokeY, visibleBottomY)
                val calculatedPages = (maxY / (pageHeightPx + pageGapPx)).toInt() + 1
                kotlin.math.max(1, calculatedPages)
            } else {
                1
            }
        }
        SideEffect {
            if (dynamicPageCount != calculatedPageCount) {
                dynamicPageCount = calculatedPageCount
            }
        }

        // Windowed Page Calculation for Fast Infinite Canvas Rendering
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

                        // Font Style Selection
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

                        // Size and Alignment Row
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

                        // Background Card / Highlighting Badge
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

                        // Text Color Palette
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
                            val otherStrokes = if (isContinuousMode) emptyList() else strokes.filter { it.pdfPage != pdfPageFilter }
                            onStrokesChanged(otherStrokes + activeStrokeList)
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
                    .graphicsLayer {
                        scaleX = internalZoomScale
                        scaleY = internalZoomScale
                        translationX = internalPanOffset.x
                        translationY = internalPanOffset.y
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    }
            ) {
                val activeInkRenderer = if (advancedBrushesEnabled) inkRenderer else null

                // Phase 27: the AGSL wet-mixing shader takes a single color uniform, so
                // for multi-color modes we feed it the color derived at the CURRENT brush
                // position — the live preview sweeps the rainbow/gradient as it draws,
                // and the committed stroke re-derives its own per-point colors.
                val wetEffectColor = if (currentColorMode.isMultiColor && activePoints.isNotEmpty()) {
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
                    // Single Page Canvas
                    drawPaperCard(0f, 0f, size.width, size.height, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper)
                    drawPaperTemplate(template, 0f, 0f, size.width, size.height, isDarkPaper = isDarkPaper, paperTexture = paperTexture)

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

                    // Render Strokes for single page
                    val previewStroke = if (activePoints.isNotEmpty() || (activeStart != null && activeEnd != null)) {
                        Stroke(
                            id = "preview",
                            tool = currentTool,
                            colorInt = currentColor.toArgb(),
                            width = currentWidth,
                            points = activePoints,
                            start = activeStart,
                            end = activeEnd,
                            pdfPage = pdfPageFilter,
                            isAdvanced = advancedBrushesEnabled,
                            colorMode = currentColorMode,
                            colorSeed = currentColorSeed,
                            gradientToColorInt = currentGradientToColor.toArgb()
                        )
                    } else null
                    drawCompositedLayersStrokes(
                        strokes = activeStrokeList,
                        previewStroke = previewStroke,
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
                        symmetryMode = symmetryMode,
                        symmetryCenterX = symmetryCenterFor(size.width, 0f).x,
                        symmetryCenterY = symmetryCenterFor(size.width, 0f).y,
                        strokeRenderOpts = strokeRenderOpts,
                        liveStrokeSeed = currentStrokeSeed,
                        vibrancyBoost = vibrancyBoost
                    )
                } else if (!divideIntoPages) {
                    // Continuous Infinite Canvas (Seamless, without page division gaps)
                    val (canvasW, infiniteH) = computeCanvasWorld(size.width)

                    drawPaperCard(0f, 0f, canvasW, infiniteH, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper, pageLabel = null)
                    drawPaperTemplate(template, 0f, 0f, canvasW, infiniteH, isDarkPaper = isDarkPaper, paperTexture = paperTexture)

                    val previewStroke = if (activePoints.isNotEmpty() || (activeStart != null && activeEnd != null)) {
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
                    drawCompositedLayersStrokes(
                        strokes = activeStrokeList,
                        previewStroke = previewStroke,
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
                        symmetryMode = symmetryMode,
                        symmetryCenterX = symmetryCenterFor(size.width, 0f).x,
                        symmetryCenterY = symmetryCenterFor(size.width, 0f).y,
                        strokeRenderOpts = strokeRenderOpts,
                        liveStrokeSeed = currentStrokeSeed,
                        vibrancyBoost = vibrancyBoost
                    )
                } else {
                    // Continuous Infinite Canvas with Page Divisions & Page Break Badges
                    val renderPageCount = dynamicPageCount
                    val canvasW = max(size.width, pageWidthPx)

                    for (pageIdx in 0 until renderPageCount) {
                        val pageTopY = pageIdx * (pageHeightPx + pageGapPx)

                        // B2-DOS-01 (phase-50): viewport culling. The graphicsLayer
                        // scales/translates this whole canvas, so the on-screen
                        // rect maps back to world/page coordinates as
                        // (screen - pan) / zoom. A page whose slab does not
                        // intersect that rect is skipped ENTIRELY (paper, template,
                        // page bitmap, stroke filter + layer raster) — a long
                        // document never pays O(strokes) per off-screen page, and
                        // spot-zoom never scales per-frame work by total points.
                        val visibleTop = (0f - internalPanOffset.y) / internalZoomScale
                        val visibleBottom = (size.height - internalPanOffset.y) / internalZoomScale
                        val pageBottomY = pageTopY + pageHeightPx
                        if (pageBottomY < visibleTop || pageTopY > visibleBottom) continue
                        // Horizontal band: when panned/zoomed so the whole world is
                        // off the left/right edges, nothing on this document draws.
                        if (canvasW <= 0f) continue
                        if (((0f - internalPanOffset.x) / internalZoomScale) > canvasW) continue
                        if (((size.width - internalPanOffset.x) / internalZoomScale) < 0f) continue

                        // 1. Differentiated Page Paper Container with Card Shadow & Page Badge
                        drawPaperCard(0f, pageTopY, canvasW, pageHeightPx, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper, pageLabel = "Page ${pageIdx + 1}", showPageLabel = showPageIndicator)
                        drawPaperTemplate(template, 0f, pageTopY, canvasW, pageHeightPx, isDarkPaper = isDarkPaper, paperTexture = paperTexture)

                        // 2. Render Page Bitmap (if in window)
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
                            // Slice tall image across multiple pages if image height exceeds single page height
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

                        // 3. Render Strokes belonging to this page
                        val pageStrokes = activeStrokeList.filter { it.pdfPage == pageIdx }
                        val previewStroke = if (activeTargetPage == pageIdx && (activePoints.isNotEmpty() || (activeStart != null && activeEnd != null))) {
Stroke(
                            id = "preview",
                            tool = currentTool,
                            colorInt = currentColor.toArgb(),
                            width = currentWidth,
                            points = activePoints,
                            start = activeStart,
                            end = activeEnd,
                            pdfPage = pageIdx,
                            isAdvanced = advancedBrushesEnabled,
                            colorMode = currentColorMode,
                            colorSeed = currentColorSeed,
                            gradientToColorInt = currentGradientToColor.toArgb()
                        )
                    } else null
                        drawCompositedLayersStrokes(
                            strokes = pageStrokes,
                            previewStroke = previewStroke,
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
                            symmetryMode = symmetryMode,
                            symmetryCenterX = symmetryCenterFor(size.width, pageTopY).x,
                            symmetryCenterY = symmetryCenterFor(size.width, pageTopY).y,
                            strokeRenderOpts = strokeRenderOpts,
                            liveStrokeSeed = currentStrokeSeed,
                            vibrancyBoost = vibrancyBoost
                        )
                    }
                }
            }

            // Render Floating Draggable Canvas Sticky Notes Overlay
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

            // Render Floating Draggable Canvas Media Embed Cards Overlay
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

            // Eyedropper Magnifying Loupe Overlay
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

            // Canvas Viewport Minimap Widget (Bottom Right)
            if (showMinimap) {
                Surface(
                    tonalElevation = 6.dp,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.width(120.dp)
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
                            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                            val density = LocalDensity.current
                            val minimapWidthPx = with(density) { 120.dp.toPx() }
                            val minimapHeightPx = with(density) { 140.dp.toPx() }
                            val screenW = with(density) { configuration.screenWidthDp.dp.toPx() }
                            val screenH = with(density) { configuration.screenHeightDp.dp.toPx() }

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
                                viewTopLeft.x + screenW / internalZoomScale,
                                viewTopLeft.y + screenH / internalZoomScale
                            )

                            fun zoomCanvasBy(mult: Float) {
                                val newScale = (internalZoomScale * mult).coerceIn(0.5f, 4.0f)
                                val center = Offset(screenW / 2f, screenH / 2f)
                                val canvasPoint = Offset(
                                    (center.x - internalPanOffset.x) / internalZoomScale,
                                    (center.y - internalPanOffset.y) / internalZoomScale
                                )
                                updateZoomAndPan(
                                    newScale,
                                    Offset(center.x - canvasPoint.x * newScale, center.y - canvasPoint.y * newScale)
                                )
                            }

                            Text(
                                text = "Zoom ${smoothZoomPct.value.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Text(
                                text = buildString {
                                    append("Layers ${layers.size}")
                                    if (!activeLayerName.isNullOrBlank()) append(" · ${activeLayerName}")
                                    if (layers.isEmpty()) append(" · base")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Text(
                                text = "View (${viewTopLeft.x.toInt()},${viewTopLeft.y.toInt()})–(${viewBottomRight.x.toInt()},${viewBottomRight.y.toInt()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 4.dp)
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
                            Spacer(modifier = Modifier.height(2.dp))

                            Box(
                                modifier = Modifier
                                    .size(120.dp, 140.dp)
                                    .background(if (isDarkTheme) Color(0xFF1E293B) else Color(0xFFF1F5F9), RoundedCornerShape(6.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                    .pointerInput(isContinuousMode, dynamicPageCount, divideIntoPages, layoutZoomScale, screenW, screenH, pageWidthPx, pageHeightPx) {
                                        val (worldW, worldH) = computeCanvasWorld(screenW)
                                        val safePageW = if (worldW > 0f) worldW else 1000f
                                        val safeCanvasH = if (worldH > 0f) worldH else 1000f

                                        val mapScaleX = minimapWidthPx / safePageW
                                        val mapScaleY = minimapHeightPx / safeCanvasH

                                        val updatePanFromMap = { touchPos: Offset ->
                                            val targetCanvasX = (touchPos.x / mapScaleX).coerceIn(0f, safePageW)
                                            val targetCanvasY = (touchPos.y / mapScaleY).coerceIn(0f, safeCanvasH)

                                            val newPanX = (screenW / 2f) - (targetCanvasX * internalZoomScale)
                                            val newPanY = (screenH / 2f) - (targetCanvasY * internalZoomScale)
                                            updateZoomAndPan(internalZoomScale, Offset(newPanX, newPanY))
                                        }

                                        detectTapGestures { tapOffset ->
                                            updatePanFromMap(tapOffset)
                                        }
                                    }
                                    .pointerInput(isContinuousMode, dynamicPageCount, divideIntoPages, layoutZoomScale, screenW, screenH, pageWidthPx, pageHeightPx) {
                                        val (worldW, worldH) = computeCanvasWorld(screenW)
                                        val safePageW = if (worldW > 0f) worldW else 1000f
                                        val safeCanvasH = if (worldH > 0f) worldH else 1000f

                                        val mapScaleX = minimapWidthPx / safePageW
                                        val mapScaleY = minimapHeightPx / safeCanvasH

                                        detectDragGestures { change, _ ->
                                            change.consume()
                                            val targetCanvasX = (change.position.x / mapScaleX).coerceIn(0f, safePageW)
                                            val targetCanvasY = (change.position.y / mapScaleY).coerceIn(0f, safeCanvasH)

                                            val newPanX = (screenW / 2f) - (targetCanvasX * internalZoomScale)
                                            val newPanY = (screenH / 2f) - (targetCanvasY * internalZoomScale)
                                            updateZoomAndPan(internalZoomScale, Offset(newPanX, newPanY))
                                        }
                                    }
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val (worldW, worldH) = computeCanvasWorld(screenW)
                                    val safePageW = if (worldW > 0f) worldW else 1000f
                                    val safeCanvasH = if (worldH > 0f) worldH else 1000f

                                    val mapScaleX = size.width / safePageW
                                    val mapScaleY = size.height / safeCanvasH

                                    // Draw background paper
                                    drawRect(
                                        color = if (isDarkTheme) Color(0xFF334155) else Color.White,
                                        topLeft = Offset(0f, 0f),
                                        size = Size(safePageW * mapScaleX, safeCanvasH * mapScaleY)
                                    )

                                    // Draw stroke preview thumbnails
                                    val strokeCount = activeStrokeList.size
                                    val step = if (strokeCount > 500) 4 else if (strokeCount > 200) 2 else 1
                                    for (sIdx in 0 until strokeCount step step) {
                                        val stroke = activeStrokeList[sIdx]
                                        if (stroke.points.size > 1) {
                                            val pCount = stroke.points.size
                                            val pStep = if (pCount > 100) 4 else if (pCount > 40) 2 else 1
                                            var prevPt = stroke.points[0]
                                            for (i in pStep until pCount step pStep) {
                                                val nextPt = stroke.points[i]
                                                drawLine(
                                                    color = stroke.color.copy(alpha = 0.7f),
                                                    start = Offset(prevPt.x * mapScaleX, prevPt.y * mapScaleY),
                                                    end = Offset(nextPt.x * mapScaleX, nextPt.y * mapScaleY),
                                                    strokeWidth = 2f
                                                )
                                                prevPt = nextPt
                                            }
                                        } else if (stroke.start != null && stroke.end != null) {
                                            drawLine(
                                                color = stroke.color.copy(alpha = 0.7f),
                                                start = Offset(stroke.start.x * mapScaleX, stroke.start.y * mapScaleY),
                                                end = Offset(stroke.end.x * mapScaleX, stroke.end.y * mapScaleY),
                                                strokeWidth = 2f
                                            )
                                        }
                                    }

                                    // Viewport Box Frame
                                    val viewWOnCanvas = screenW / internalZoomScale
                                    val viewHOnCanvas = screenH / internalZoomScale
                                    val viewXOnCanvas = -internalPanOffset.x / internalZoomScale
                                    val viewYOnCanvas = -internalPanOffset.y / internalZoomScale

                                    val rectX = (viewXOnCanvas * mapScaleX).coerceIn(0f, size.width)
                                    val rectY = (viewYOnCanvas * mapScaleY).coerceIn(0f, size.height)
                                    val rectW = (viewWOnCanvas * mapScaleX).coerceIn(10f, size.width)
                                    val rectH = (viewHOnCanvas * mapScaleY).coerceIn(10f, size.height)

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
 * Phase 35: magnifier loupe for the Eyedropper tool. Draws a 5x5 grid of pixels
 * sampled from the actual rendered page bitmap around the pointer position,
 * magnified ~4x so users can micro-target sub-finger strokes. Pure rendering —
 * the color pick logic stays in [sampleColorAt]/EyedropperSamplingMath.
 */
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
        // Hex label chip pinned below the grid.
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

private fun DrawScope.drawPaperCard(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    paperColor: Color = Color.White,
    isDarkPaper: Boolean = false,
    pageLabel: String? = null,
    showPageLabel: Boolean = true
) {
    val borderColor = if (isDarkPaper) Color(0xFF475569) else Color.LightGray.copy(alpha = 0.6f)

    // Paper Card Background
    drawRoundRect(
        color = paperColor,
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(8f, 8f)
    )
    // Page Border Line
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(8f, 8f),
        style = DrawStrokeStyle(width = 2f)
    )

    // Page Number Header Tag
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

private fun DrawScope.drawPaperTemplate(
    template: String,
    xOffset: Float,
    yOffset: Float,
    width: Float,
    height: Float,
    isDarkPaper: Boolean = false,
    paperTexture: ImageBitmap? = null
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
    val gridColor = if (isDarkPaper) Color(0xFF94A3B8).copy(alpha = 0.35f) else Color.Gray.copy(alpha = 0.22f)
    when (template) {
        "lined" -> {
            val lineSpacing = 36.dp.toPx()
            var y = yOffset + lineSpacing
            while (y < yOffset + height) {
                drawLine(gridColor, Offset(xOffset, y), Offset(xOffset + width, y), strokeWidth = 1.5f)
                y += lineSpacing
            }
        }
        "grid" -> {
            val gridSize = 28.dp.toPx()
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
            val dotSpacing = 28.dp.toPx()
            var x = xOffset + dotSpacing
            while (x < xOffset + width) {
                var y = yOffset + dotSpacing
                while (y < yOffset + height) {
                    drawCircle(gridColor, radius = 2f, center = Offset(x, y))
                    y += dotSpacing
                }
                x += dotSpacing
            }
        }
        "cornell" -> {
            val accentColor = if (isDarkPaper) Color(0xFF38BDF8).copy(alpha = 0.5f) else Color(0xFF0284C7).copy(alpha = 0.4f)
            val lineSpacing = 32.dp.toPx()
            val headerY = yOffset + 100.dp.toPx()
            val summaryY = yOffset + height - 140.dp.toPx()
            val cueX = xOffset + width * 0.30f

            // Title line
            drawLine(accentColor, Offset(xOffset, headerY), Offset(xOffset + width, headerY), strokeWidth = 3.5f)
            // Summary line
            drawLine(accentColor, Offset(xOffset, summaryY), Offset(xOffset + width, summaryY), strokeWidth = 3.5f)
            // Cue column line
            drawLine(accentColor, Offset(cueX, headerY), Offset(cueX, summaryY), strokeWidth = 2.5f)

            // Faint lined grid in notes and cue areas
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

            // Header Box
            drawRect(accentColor.copy(alpha = 0.1f), topLeft = Offset(xOffset + 16.dp.toPx(), yOffset + 16.dp.toPx()), size = androidx.compose.ui.geometry.Size(width - 32.dp.toPx(), 88.dp.toPx()))
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), yOffset + 16.dp.toPx()), Offset(xOffset + width - 16.dp.toPx(), yOffset + 16.dp.toPx()), strokeWidth = 2f)
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), yOffset + 104.dp.toPx()), Offset(xOffset + width - 16.dp.toPx(), yOffset + 104.dp.toPx()), strokeWidth = 2f)

            // Divider line between Discussion Notes and Action Items
            drawLine(accentColor, Offset(splitX, headerY), Offset(splitX, yOffset + height - 16.dp.toPx()), strokeWidth = 2.5f)

            // Lines for Discussion area
            var y = headerY + lineSpacing
            while (y < yOffset + height - 16.dp.toPx()) {
                drawLine(gridColor, Offset(xOffset + 16.dp.toPx(), y), Offset(splitX - 12.dp.toPx(), y), strokeWidth = 1f)
                y += lineSpacing
            }

            // Lines and Checkboxes for Action Items area
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

            // Top Header Line
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), topHeaderY), Offset(xOffset + width - 16.dp.toPx(), topHeaderY), strokeWidth = 3f)
            // Center Column Divider Line
            drawLine(accentColor, Offset(col2X, topHeaderY), Offset(col2X, bottomNotesY), strokeWidth = 2f)
            // Bottom Notes Header Line
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), bottomNotesY), Offset(xOffset + width - 16.dp.toPx(), bottomNotesY), strokeWidth = 3f)

            // Column 1 Tasks
            var y1 = topHeaderY + rowHeight
            while (y1 < bottomNotesY) {
                drawRect(accentColor, topLeft = Offset(xOffset + 20.dp.toPx(), y1 - 18.dp.toPx()), size = androidx.compose.ui.geometry.Size(16.dp.toPx(), 16.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                drawLine(gridColor, Offset(xOffset + 44.dp.toPx(), y1), Offset(col2X - 12.dp.toPx(), y1), strokeWidth = 1f)
                y1 += rowHeight
            }

            // Column 2 Tasks
            var y2 = topHeaderY + rowHeight
            while (y2 < bottomNotesY) {
                drawRect(accentColor, topLeft = Offset(col2X + 16.dp.toPx(), y2 - 18.dp.toPx()), size = androidx.compose.ui.geometry.Size(16.dp.toPx(), 16.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                drawLine(gridColor, Offset(col2X + 40.dp.toPx(), y2), Offset(xOffset + width - 20.dp.toPx(), y2), strokeWidth = 1f)
                y2 += rowHeight
            }

            // Bottom Notes Grid
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
            
            // Header Line
            drawLine(accentColor, Offset(xOffset + 16.dp.toPx(), headerY), Offset(xOffset + width - 16.dp.toPx(), headerY), strokeWidth = 3f)
            // Column Dividers
            drawLine(accentColor, Offset(col1X, headerY), Offset(col1X, yOffset + height - 20.dp.toPx()), strokeWidth = 2f)
            drawLine(accentColor, Offset(col2X, headerY), Offset(col2X, yOffset + height - 20.dp.toPx()), strokeWidth = 2f)
            
            // Horizontal card guides
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
    }
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
    layerBitmapCache: MutableMap<String, com.authorss81.noteflow.ui.components.LayerBitmapCache>? = null,
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
    symmetryMode: SymmetryMode = SymmetryMode.OFF,
    symmetryCenterX: Float = 0f,
    symmetryCenterY: Float = 0f,
    strokeRenderOpts: StrokeRenderOpts = StrokeRenderOpts(),
    liveStrokeSeed: Float = 0f,
    vibrancyBoost: Float = 0f
) {
    // Phase 07: view-time mirror. Symmetry never touches stored point data —
    // committed strokes keep the real points so saved notes stay portable and
    // export correctly. TEXT strokes are never mirrored (text cannot sensibly
    // reflect).
    fun DrawScope.drawStrokeWithSymmetry(stroke: Stroke, offsetY: Float, sMode: SymmetryMode, centerX: Float = symmetryCenterX, centerY: Float = symmetryCenterY) {
        drawSingleStroke(stroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost)
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
                vibrancy = vibrancyBoost
            )
        }
    }

    if (layers.isEmpty()) {
        // No layers yet (e.g. first frames before the page's layer list loads, or
        // a page whose layer row was never created). Phase 08: route committed
        // strokes through the same page-local bitmap cache as the layer path so we
        // do NOT re-vectorize every committed stroke every frame. The cache is
        // keyed per page+mode and cleared whenever `strokes` changes (the
        // LaunchedEffect(strokes, layers) above), so this stays pixel-identical.
        if (layerBitmapCache != null && canvasDrawScope != null && density != null && layoutDirection != null &&
            pageWidth > 0f && pageHeight > 0f
        ) {
            val defaultLayerId = "layer_default"
            val cacheKey = "${pageIdx}_${defaultLayerId}_${symmetryMode}_v${vibrancyBoost}"
            val strokesHash = strokes.hashCode()
            var cache = layerBitmapCache[cacheKey]
            val pw = pageWidth.toInt().coerceAtLeast(1)
            val ph = pageHeight.toInt().coerceAtLeast(1)
            if (cache == null || cache.bitmap.width != pw || cache.bitmap.height != ph) {
                cache?.let { com.authorss81.noteflow.utils.BitmapPool.release(it.bitmap.asAndroidBitmap()) }
                val androidBmp = com.authorss81.noteflow.utils.BitmapPool.acquire(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
                val bmp = androidBmp.asImageBitmap()
                cache = com.authorss81.noteflow.ui.components.LayerBitmapCache(bmp, androidx.compose.ui.graphics.Canvas(bmp))
                layerBitmapCache[cacheKey] = cache
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
                        // Cache-local center: the bitmap is page-local, so the mirror
                        // axis must be shifted by the page offset too.
                        drawStrokeWithSymmetry(stroke, offsetY - pageTopY, symmetryMode, symmetryCenterX, symmetryCenterY - pageTopY)
                    }
                }
                cache.hash = strokesHash
            }

            drawContext.canvas.nativeCanvas.drawBitmap(cache.bitmap.asAndroidBitmap(), 0f, pageTopY, null)
            if (previewStroke != null) {
                drawStrokeWithSymmetry(previewStroke, offsetY, symmetryMode)
            }
        } else {
            for (stroke in strokes) {
                drawStrokeWithSymmetry(stroke, offsetY, symmetryMode)
            }
            if (previewStroke != null) {
                drawStrokeWithSymmetry(previewStroke, offsetY, symmetryMode)
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
        val useAgslWetMixing = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                gpuWetBrushesEnabled &&
                graphicsLayer != null &&
                wetBrushEngine != null &&
                !wetBrushEngine.useVectorFallback &&
                symmetryMode == SymmetryMode.OFF &&
                isWetLayer &&
                isPreviewOnThisLayer

        if (useAgslWetMixing && graphicsLayer != null && wetBrushEngine != null) {
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
                strokeRenderOpts = strokeRenderOpts,
                liveStrokeSeed = liveStrokeSeed,
                vibrancyBoost = vibrancyBoost
            )
            continue
        }

        val cacheKey = "${pageIdx}_${layer.id}_${symmetryMode}_v${vibrancyBoost}"
        val strokesHash = layerStrokes.hashCode()

        // Phase 07: symmetry is a view-time transform, but it does NOT have to be
        // a per-frame cost. The mirrored copy is baked into the layer bitmap and
        // the key includes the symmetry mode, so a mode change invalidates it and
        // the normal cached-blit path is restored. Only the live preview is
        // mirrored per frame; this avoids re-vectorizing every layer every frame
        // while a symmetry mode is active.
        if (layerBitmapCache != null && canvasDrawScope != null && density != null && layoutDirection != null && pageWidth > 0f && pageHeight > 0f) {
            var cache = layerBitmapCache[cacheKey]
            val pw = pageWidth.toInt().coerceAtLeast(1)
            val ph = pageHeight.toInt().coerceAtLeast(1)
            if (cache == null || cache.bitmap.width != pw || cache.bitmap.height != ph) {
                cache?.let { com.authorss81.noteflow.utils.BitmapPool.release(it.bitmap.asAndroidBitmap()) }
                val androidBmp = com.authorss81.noteflow.utils.BitmapPool.acquire(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
                val bmp = androidBmp.asImageBitmap()
                cache = com.authorss81.noteflow.ui.components.LayerBitmapCache(bmp, androidx.compose.ui.graphics.Canvas(bmp))
                layerBitmapCache[cacheKey] = cache
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
                        // Cache-local center: the bitmap is page-local, so the mirror
                        // axis must be shifted by the page offset too.
                        drawStrokeWithSymmetry(stroke, offsetY - pageTopY, symmetryMode, symmetryCenterX, symmetryCenterY - pageTopY)
                    }
                }
                cache.hash = strokesHash
            }
            
            val paint = cache.paint
            paint.alpha = (layer.opacity * 255f).coerceIn(0f, 255f).toInt()
            paint.applyLayerBlend(layer.blendMode)
            
            drawContext.canvas.nativeCanvas.drawBitmap(
                cache.bitmap.asAndroidBitmap(),
                0f,
                pageTopY,
                paint
            )
            
            if (isPreviewOnThisLayer && previewStroke != null) {
                drawStrokeWithSymmetry(previewStroke, offsetY, symmetryMode)
            }
        } else {
            val isNormal = layer.blendMode.equals("NORMAL", ignoreCase = true)
            val isOpaque = layer.opacity >= 0.99f
    
            if (isNormal && isOpaque) {
                for (stroke in layerStrokes) {
                    drawStrokeWithSymmetry(stroke, offsetY, symmetryMode)
                }
                if (isPreviewOnThisLayer && previewStroke != null) {
                    drawStrokeWithSymmetry(previewStroke, offsetY, symmetryMode)
                }
            } else {
                val nativeCanvas = drawContext.canvas.nativeCanvas
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    alpha = (layer.opacity * 255f).coerceIn(0f, 255f).toInt()
                    applyLayerBlend(layer.blendMode)
                }
                val bounds = android.graphics.RectF(0f, 0f, size.width, size.height)
                val saveCount = nativeCanvas.saveLayer(bounds, paint)
                try {
                    for (stroke in layerStrokes) {
                        drawStrokeWithSymmetry(stroke, offsetY, symmetryMode)
                    }
                    if (isPreviewOnThisLayer && previewStroke != null) {
                        drawStrokeWithSymmetry(previewStroke, offsetY, symmetryMode)
                    }
                } finally {
                    nativeCanvas.restoreToCount(saveCount)
                }
            }
        }
    }
}

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
    strokeRenderOpts: StrokeRenderOpts = StrokeRenderOpts(),
    liveStrokeSeed: Float = 0f,
    vibrancyBoost: Float = 0f
) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && wetMixingEffect != null) {
        val brushPos = activePoints.lastOrNull() ?: activeStart
        val prevPos = if (activePoints.size >= 2) activePoints[activePoints.size - 2] else activeStart

        var hasEffect = false
        if (brushPos != null && isPreviewOnThisLayer) {
            val preset = AgslShaders.PRESETS[currentTool] ?: AgslShaders.ToolPreset(0.5f, 0.5f, 0.5f, 0f, 0.5f)
            
            wetMixingEffect.update(
                prevX = prevPos?.x ?: brushPos.x,
                prevY = prevPos?.y ?: brushPos.y,
                brushX = brushPos.x,
                brushY = brushPos.y,
                radius = currentWidth * 1.5f,
                color = currentColor,
                wetness = preset.wetness,
                pigmentLoad = preset.pigmentLoad,
                mixStrength = preset.mixStrength,
                impasto = preset.impasto,
                hardness = preset.hardness,
                paperGrain = wetCanvasEngine.brushParams.paperGrain,
                seed = com.authorss81.noteflow.services.BrushStrokeMath.strokeSeedFromId(
                    previewStroke?.id ?: "preview"
                ),
                strokeSeed = liveStrokeSeed,
                brushStyle = preset.brushStyle,
                vibrancy = vibrancyBoost
            )
            hasEffect = true
        }

        val nativeCanvas = drawContext.canvas.nativeCanvas
        val plainPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            alpha = (layer.opacity * 255f).coerceIn(0f, 255f).toInt()
            applyLayerBlend(layer.blendMode)
        }
        val effectPaint = if (hasEffect && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val p = android.graphics.Paint(plainPaint)
            try {
                val method = android.graphics.Paint::class.java.getMethod("setRenderEffect", android.graphics.RenderEffect::class.java)
                method.invoke(p, wetMixingEffect.androidEffect)
            } catch (_: Exception) {}
            p
        } else {
            null
        }

        val pageBounds = android.graphics.RectF(0f, offsetY, pageWidth, offsetY + pageHeight)

        // Dirty-rect scoping (phase-04 audit item 3): only re-run the AGSL effect
        // over the rect the active brush segment can alter, so the offscreen layer
        // drops from a full-page raster (~1.65M px) to the stroke area. saveLayer
        // keeps the canvas (absolute) coordinate space, so shader/Paint uniforms
        // stay in page coordinates and no translate is required.
        val dirty = if (hasEffect && brushPos != null) {
            val baseX = prevPos?.x ?: brushPos.x
            val baseY = prevPos?.y ?: brushPos.y
            val radius = currentWidth * 1.5f + 8f
            val rect = android.graphics.RectF(
                minOf(baseX, brushPos.x) - radius,
                minOf(baseY, brushPos.y) - radius,
                maxOf(baseX, brushPos.x) + radius,
                maxOf(baseY, brushPos.y) + radius
            )
            if (rect.intersect(pageBounds)) rect else null
        } else {
            null
        }

        fun drawStrokes() {
            for (stroke in layerStrokes) {
                drawSingleStroke(stroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost)
            }
            if (previewStroke != null) {
                drawSingleStroke(previewStroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost)
            }
        }

        if (effectPaint == null || dirty == null) {
            // No active effect region (idle fallback): keep the existing full-page
            // plain layer so layer opacity/blend still apply unchanged.
            val saveCount = nativeCanvas.saveLayer(pageBounds, plainPaint)
            try {
                drawStrokes()
            } finally {
                nativeCanvas.restoreToCount(saveCount)
            }
        } else {
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

            // 2) Effect pass sized to the dirty rect only; shader coords stay
            //    absolute because saveLayer preserves the canvas coordinate space.
            val effectSave = nativeCanvas.saveLayer(dirty, effectPaint)
            try {
                nativeCanvas.save()
                nativeCanvas.clipRect(dirty)
                drawStrokes()
                nativeCanvas.restore()
            } finally {
                nativeCanvas.restoreToCount(effectSave)
            }
        }
    } else {
        for (stroke in layerStrokes) {
            drawSingleStroke(stroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost)
        }
        if (previewStroke != null) {
            drawSingleStroke(previewStroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer, renderOpts = strokeRenderOpts, vibrancy = vibrancyBoost)
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
    vibrancy: Float = 0f
) {
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
                    val dynamicWidth = (strokeWidth * com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(vel, renderOpts.velocityIntensity)).coerceAtLeast(0.75f)
                    drawLine(
                        color = if (isMultiColor) derivedColorAtPoint(pts, i + 1) else color,
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
                    drawPath(
                        path = path,
                        color = color,
                        style = DrawStrokeStyle(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            } else if (stroke.points.size == 1) {
                drawCircle(if (isMultiColor) derivedColorAt(1f) else color, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
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
            val pencilColor = if (isMultiColor) derivedColorAt(0.5f) else color.copy(alpha = 0.82f)
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawTexturedStrokePath(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth,
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
            BrushTextureEngine.drawBitmapStampSequence(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                baseSize = strokeWidth * 3.0f,
                color = if (isMultiColor) derivedColorAt(0.5f) else color.copy(alpha = 0.65f),
                textureType = BrushTextureEngine.TextureType.SPLATTER_DROPS,
                spacingFactor = 0.45f,
                scatterFactor = 0.55f
            )
        }
        // Phase 18: six NEW brush families — each renders visibly distinct. These are
        // honest vector approximations of their AGSL shader styles (see docs/brush-styles.md).
        StrokeTool.CHARCOAL -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawCharcoalStroke(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth,
                color = if (isMultiColor) derivedColorAt(0.5f) else color,
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
            val ageMs = if (stroke.timestampMs != null) (System.currentTimeMillis() - stroke.timestampMs).coerceAtLeast(0L) else 0L
            val fadeFactor = (1.0f - (ageMs / 1800f)).coerceIn(0.0f, 1.0f)
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

                    // Optional background highlight badge
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

                    // Draw text lines
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
        else -> {}
    }
}

private fun strokeContainsPoint(stroke: Stroke, point: Offset): Boolean {
    val threshold = (stroke.width + 18f)
    for (p in stroke.points) {
        val dx = p.x - point.x
        val dy = p.y - point.y
        if (dx * dx + dy * dy <= threshold * threshold) return true
    }
    stroke.start?.let {
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

        // Resize Handle (Bottom-Right)
        if (!currentNote.isCollapsed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(currentNote.id) {
                        detectDragGestures(
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
                            }
                        )
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

        // Phase 13: rotation handle (top-center, above the note).
        if (!currentNote.isCollapsed) {
            RotationHandle(
                modifier = Modifier.align(Alignment.TopCenter),
                cardWidthPx = with(LocalDensity.current) { (actualWidth * currentZoom).dp.toPx() },
                cardHeightPx = with(LocalDensity.current) { cardHeightDp.toPx() },
                rotationDegrees = liveRotation,
                zoomScale = currentZoom,
                onRotationChange = { liveRotation = it },
                onRotationCommit = commitRotation
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
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentEmbed.id) {
                    detectDragGestures(
                        onDragStart = {
                            dragOffsetX = 0f
                            dragOffsetY = 0f
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
            }
        }

        if (!(currentEmbed.type == MediaEmbedType.AUDIO_NOTE && currentEmbed.isCollapsed)) {
            // 4 Corner Resize Handles (Top-Left, Top-Right, Bottom-Left, Bottom-Right)
            val minW = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) 220f else 120f
            val maxW = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) 420f else 2000f
            val minH = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) 100f else 80f
            val maxH = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE) 280f else 2000f

            val saveCurrentEmbedState = {
                val finalX = currentEmbed.x + dragOffsetX
                val finalY = currentEmbed.y + dragOffsetY
                dragOffsetX = 0f
                dragOffsetY = 0f
                val updated = currentEmbed.copy(x = finalX, y = finalY, width = resizeWidth, height = resizeHeight)
                val index = activeMediaEmbedList.indexOfFirst { it.id == currentEmbed.id }
                if (index != -1) {
                    activeMediaEmbedList[index] = updated
                }
                val other = if (isContinuousMode) emptyList() else mediaEmbeds.filter { it.pdfPage != pdfPageFilter }
                currentOnMediaEmbedsChanged(other + activeMediaEmbedList.toList())
            }

            // Bottom-Right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(currentEmbed.id) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                resizeWidth = (resizeWidth + dragAmount.x / currentZoom).coerceIn(minW, maxW)
                                resizeHeight = (resizeHeight + dragAmount.y / currentZoom).coerceIn(minH, maxH)
                            },
                            onDragEnd = saveCurrentEmbedState
                        )
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

            // Bottom-Left
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(24.dp)
                    .pointerInput(currentEmbed.id) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / currentZoom
                                val dy = dragAmount.y / currentZoom
                                val newW = (resizeWidth - dx).coerceAtLeast(minW)
                                dragOffsetX += (resizeWidth - newW)
                                resizeWidth = newW
                                resizeHeight = (resizeHeight + dy).coerceAtLeast(minH)
                            },
                            onDragEnd = saveCurrentEmbedState
                        )
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

            // Top-Right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .pointerInput(currentEmbed.id) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / currentZoom
                                val dy = dragAmount.y / currentZoom
                                resizeWidth = (resizeWidth + dx).coerceAtLeast(minW)
                                val newH = (resizeHeight - dy).coerceAtLeast(minH)
                                dragOffsetY += (resizeHeight - newH)
                                resizeHeight = newH
                            },
                            onDragEnd = saveCurrentEmbedState
                        )
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

            // Top-Left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(24.dp)
                    .pointerInput(currentEmbed.id) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x / currentZoom
                                val dy = dragAmount.y / currentZoom
                                val newW = (resizeWidth - dx).coerceAtLeast(minW)
                                dragOffsetX += (resizeWidth - newW)
                                resizeWidth = newW
                                val newH = (resizeHeight - dy).coerceAtLeast(minH)
                                dragOffsetY += (resizeHeight - newH)
                                resizeHeight = newH
                            },
                            onDragEnd = saveCurrentEmbedState
                        )
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

        // Phase 13: rotation handle (top-center, above the item).
        if (!(currentEmbed.type == MediaEmbedType.AUDIO_NOTE && currentEmbed.isCollapsed)) {
            RotationHandle(
                modifier = Modifier.align(Alignment.TopCenter),
                cardWidthPx = with(LocalDensity.current) { (actualWidth * currentZoom).dp.toPx() },
                cardHeightPx = with(LocalDensity.current) { (actualHeight * currentZoom).dp.toPx() },
                rotationDegrees = liveRotation,
                zoomScale = currentZoom,
                onRotationChange = { liveRotation = it },
                onRotationCommit = commitRotation
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
    gapDp: androidx.compose.ui.unit.Dp = 8.dp
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

    Box(
        modifier = modifier
            .offset(y = -(handleSizeDp + gapDp))
            .size(handleSizeDp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragHasMoved = false },
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
                    },
                    onDragCancel = { dragHasMoved = false }
                )
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
    val chiselNibAngleDeg: Float = 30f
)

