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
import com.authorss81.noteflow.services.ProtobufBrushLoader
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
    gpuWetBrushesEnabled: Boolean = true,
    shapeAutoSnapEnabled: Boolean = true
) {
    var internalZoomScale by remember { mutableFloatStateOf(zoomScale) }
    var internalPanOffset by remember { mutableStateOf(panOffset) }

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
        val hitNote = activeStickyNoteList.any { note ->
            canvasOffset.x >= note.x && canvasOffset.x <= note.x + note.width &&
            canvasOffset.y >= note.y && canvasOffset.y <= note.y + note.height
        }
        if (hitNote) return true

        val hitEmbed = activeMediaEmbedList.any { embed ->
            val w = if (embed.width > 0) embed.width else 340f
            val h = if (embed.height > 0) embed.height else 240f
            canvasOffset.x >= embed.x && canvasOffset.x <= embed.x + w &&
            canvasOffset.y >= embed.y && canvasOffset.y <= embed.y + h
        }
        return hitEmbed
    }

    var isDraggingCard by remember { mutableStateOf(false) }
    fun getPageFromCanvasY(canvasY: Float): Int {
        if (!isContinuousMode || dynamicPageCount <= 1) return pdfPageFilter
        val index = (canvasY / (pageHeightPx + pageGapPx)).toInt()
        return index.coerceIn(0, dynamicPageCount - 1)
    }

    // Color sampling helper for Eyedropper tool
    fun sampleColorAt(canvasOffset: Offset, targetPage: Int): Color {
        val rawBmp = activeRawBitmapMap[targetPage]
        if (rawBmp != null && !rawBmp.isRecycled) {
            val scale = pageWidthPx / rawBmp.width.toFloat()
            val bx = (canvasOffset.x / scale).toInt().coerceIn(0, rawBmp.width - 1)
            val py = canvasOffset.y - calculatePageYOffset(targetPage)
            val by = (py / scale).toInt().coerceIn(0, rawBmp.height - 1)
            try {
                val pixel = rawBmp.getPixel(bx, by)
                return Color(pixel)
            } catch (e: Exception) {
                // Ignore safe bounds fallback
            }
        }
        val hitStroke = activeStrokeList.lastOrNull { strokeContainsPoint(it, canvasOffset) }
        if (hitStroke != null) {
            return hitStroke.color
        }
        return Color(0xFF1B365D)
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
    LaunchedEffect(strokes, layers) {
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

    val context = androidx.compose.ui.platform.LocalContext.current
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
                        }
                    }
                )
            }

            // 3. Drawing / Eyedropper / Single-Finger Pan Gestures
            .pointerInput(currentTool, currentColor, currentWidth, pdfPageFilter, isContinuousMode, activeRawBitmapMap, isLayerLocked) {
                if (currentTool != StrokeTool.TEXT) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (isLayerLocked && currentTool != StrokeTool.SELECT && currentTool != StrokeTool.PAN && currentTool != StrokeTool.EYEDROPPER) {
                                return@detectDragGestures
                            }
                            onDrawingStart()
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
                            val startPoint = PointF(
                                x = canvasOffset.x.coerceIn(0f, pageWidthPx),
                                y = canvasOffset.y.coerceIn(targetPageYStart, targetPageYEnd),
                                pressure = lastPressure,
                                tilt = lastTilt,
                                timestampMs = lastTimestampMs
                            )

                            if (currentTool == StrokeTool.EYEDROPPER) {
                                eyedropperPosition = offset
                                sampledColorPreview = sampleColorAt(canvasOffset, targetPage)
                            } else if (currentTool == StrokeTool.ERASER) {
                                val remaining = activeStrokeList.filterNot { stroke ->
                                    strokeContainsPoint(stroke, canvasOffset)
                                }
                                if (remaining.size != activeStrokeList.size) {
                                    activeStrokeList.clear()
                                    activeStrokeList.addAll(remaining)
                                    val otherStrokes = if (isContinuousMode) emptyList() else strokes.filter { it.pdfPage != pdfPageFilter }
                                    onStrokesChanged(otherStrokes + remaining)
                                }
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

                            val currentPoint = PointF(
                                x = rawCanvasX.coerceIn(0f, pageWidthPx),
                                y = rawCanvasY.coerceIn(targetPageYStart, targetPageYEnd),
                                pressure = lastPressure,
                                tilt = lastTilt,
                                timestampMs = lastTimestampMs
                            )

                            if (currentTool == StrokeTool.EYEDROPPER) {
                                val canvasPosition = Offset(rawCanvasX, rawCanvasY)
                                eyedropperPosition = change.position
                                sampledColorPreview = sampleColorAt(canvasPosition, activeTargetPage)
                            } else if (currentTool == StrokeTool.ERASER) {
                                val canvasPosition = Offset(rawCanvasX, rawCanvasY)
                                val remaining = activeStrokeList.filterNot { stroke ->
                                    strokeContainsPoint(stroke, canvasPosition)
                                }
                                if (remaining.size != activeStrokeList.size) {
                                    activeStrokeList.clear()
                                    activeStrokeList.addAll(remaining)
                                    val otherStrokes = if (isContinuousMode) emptyList() else strokes.filter { it.pdfPage != pdfPageFilter }
                                    onStrokesChanged(otherStrokes + remaining)
                                }
                            } else if (currentTool.isFreehandTool) {
                                // Vector Stroke Smoothing & Touch jitter filtering: add point if distance > 1.5px
                                val last = activePoints.lastOrNull()
                                val lastTime = if (activePoints.size >= 2) System.currentTimeMillis() - 16L else System.currentTimeMillis() - 100L
                                val curTime = System.currentTimeMillis()

                                if (wetBrushEngine.shouldProcessPoint(last?.let { Offset(it.x, it.y) }, Offset(currentPoint.x, currentPoint.y), lastTime, curTime)) {
                                    if (last != null && (currentTool == StrokeTool.WATERCOLOR || currentTool == StrokeTool.OIL_PAINT || currentTool == StrokeTool.SMUDGE || currentTool == StrokeTool.SPLATTER)) {
                                        val interpolated = wetBrushEngine.interpolateSegment(
                                            prev = Offset(last.x, last.y),
                                            cur = Offset(currentPoint.x, currentPoint.y),
                                            radius = currentWidth * 1.5f
                                        )
                                        for (interp in interpolated) {
                                            val interpPt = PointF(
                                                x = interp.x,
                                                y = interp.y,
                                                pressure = lastPressure,
                                                tilt = lastTilt,
                                                timestampMs = lastTimestampMs
                                            )
                                            activePoints.add(interpPt)

                                            wetCanvasEngine.markPaintDeposited(currentTool)
                                        }
                                        activeEnd = activePoints.lastOrNull() ?: currentPoint
                                    } else {
                                        activePoints.add(currentPoint)
                                        activeEnd = currentPoint
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
                                            layerId = actLayerId ?: "layer_default"
                                        )
                                        val isWetOrFleeting = tool == StrokeTool.WATERCOLOR || tool == StrokeTool.OIL_PAINT ||
                                            tool == StrokeTool.SMUDGE || tool == StrokeTool.SPLATTER || tool == StrokeTool.LASER
                                        val stylePreservingTool = tool == StrokeTool.DOTTED || tool == StrokeTool.NEON
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
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.FormatAlignLeft,
                                            contentDescription = "Left",
                                            tint = if (textAlign == "LEFT") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    IconButton(
                                        onClick = { textAlign = "CENTER" },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.FormatAlignCenter,
                                            contentDescription = "Center",
                                            tint = if (textAlign == "CENTER") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    IconButton(
                                        onClick = { textAlign = "RIGHT" },
                                        modifier = Modifier.size(32.dp)
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
                                    modifier = Modifier.size(28.dp)
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

                if (!isContinuousMode) {
                    // Single Page Canvas
                    drawPaperCard(0f, 0f, size.width, size.height, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper)
                    drawPaperTemplate(template, 0f, 0f, size.width, size.height, isDarkPaper = isDarkPaper)

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
                            isAdvanced = advancedBrushesEnabled
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
                        currentColor = currentColor,
                        currentWidth = currentWidth,
                        activePoints = activePoints,
                        activeStart = activeStart,
                        wetCanvasEngine = wetCanvasEngine,
                        wetBrushEngine = wetBrushEngine,
                        gpuWetBrushesEnabled = gpuWetBrushesEnabled
                    )
                } else if (!divideIntoPages) {
                    // Continuous Infinite Canvas (Seamless, without page division gaps)
                    val (canvasW, infiniteH) = computeCanvasWorld(size.width)

                    drawPaperCard(0f, 0f, canvasW, infiniteH, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper, pageLabel = null)
                    drawPaperTemplate(template, 0f, 0f, canvasW, infiniteH, isDarkPaper = isDarkPaper)

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
                            isAdvanced = advancedBrushesEnabled
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
                        currentColor = currentColor,
                        currentWidth = currentWidth,
                        activePoints = activePoints,
                        activeStart = activeStart,
                        wetCanvasEngine = wetCanvasEngine,
                        wetBrushEngine = wetBrushEngine,
                        gpuWetBrushesEnabled = gpuWetBrushesEnabled
                    )
                } else {
                    // Continuous Infinite Canvas with Page Divisions & Page Break Badges
                    val renderPageCount = dynamicPageCount
                    val canvasW = max(size.width, pageWidthPx)

                    for (pageIdx in 0 until renderPageCount) {
                        val pageTopY = pageIdx * (pageHeightPx + pageGapPx)

                        // 1. Differentiated Page Paper Container with Card Shadow & Page Badge
                        drawPaperCard(0f, pageTopY, canvasW, pageHeightPx, paperColor = parsedPaperColor, isDarkPaper = isDarkPaper, pageLabel = "Page ${pageIdx + 1}", showPageLabel = showPageIndicator)
                        drawPaperTemplate(template, 0f, pageTopY, canvasW, pageHeightPx, isDarkPaper = isDarkPaper)

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
                                isAdvanced = advancedBrushesEnabled
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
                            currentColor = currentColor,
                            currentWidth = currentWidth,
                            activePoints = activePoints,
                            activeStart = activeStart,
                            wetCanvasEngine = wetCanvasEngine,
                            wetBrushEngine = wetBrushEngine,
                            gpuWetBrushesEnabled = gpuWetBrushesEnabled
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
                    onVoiceSpeedChange = onVoiceSpeedChange
                )
            }

            // Eyedropper Magnifying Loupe Overlay
            if (currentTool == StrokeTool.EYEDROPPER && eyedropperPosition != null && sampledColorPreview != null) {
                val pos = eyedropperPosition!!
                val sampledColor = sampledColorPreview!!
                val textLight = (0.299f * sampledColor.red + 0.587f * sampledColor.green + 0.114f * sampledColor.blue) < 0.5f
                Box(
                    modifier = Modifier
                        .offset { IntOffset((pos.x - 48.dp.toPx()).toInt(), (pos.y - 100.dp.toPx()).toInt()) }
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(sampledColor)
                        .border(3.dp, Color.White, CircleShape)
                        .border(4.dp, Color.Black.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Colorize,
                            contentDescription = null,
                            tint = if (textLight) Color.White else Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "#${Integer.toHexString(sampledColor.toArgb()).takeLast(6).uppercase()}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (textLight) Color.White else Color.Black,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
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

        if (currentTool == StrokeTool.WATERCOLOR || currentTool == StrokeTool.OIL_PAINT || currentTool == StrokeTool.SMUDGE || currentTool == StrokeTool.SPLATTER || wetCanvasEngine.isCanvasWet) {
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
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Outlined.Palette, contentDescription = "Brush Studio", modifier = Modifier.size(16.dp))
                    }

                    if (wetCanvasEngine.isCanvasWet) {
                        FilledTonalIconButton(
                            onClick = { wetCanvasEngine.dryCanvasSheet() },
                            modifier = Modifier.size(28.dp)
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
                onDismiss = { showBrushStudio = false }
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
    isDarkPaper: Boolean = false
) {
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

private fun convertToInkStroke(stroke: Stroke, context: android.content.Context? = null): InkStroke? {
    if (stroke.points.isEmpty()) return null
    if (stroke.tool == StrokeTool.CALLIGRAPHIC || stroke.tool == StrokeTool.CHISEL_MARKER) return null
    try {
        val family = ProtobufBrushLoader.getBrushFamilyForTool(context, stroke.tool)
        val isSolidTool = stroke.tool == StrokeTool.PEN ||
                          stroke.tool == StrokeTool.FOUNTAIN_PEN ||
                          stroke.tool == StrokeTool.FINELINER ||
                          stroke.tool == StrokeTool.CALLIGRAPHIC ||
                          stroke.tool == StrokeTool.CHISEL_MARKER ||
                          stroke.tool == StrokeTool.DOTTED ||
                          stroke.tool == StrokeTool.LINE ||
                          stroke.tool.isShapeTool

        val brushColorInt = if (isSolidTool) stroke.color.copy(alpha = 1.0f).toArgb() else stroke.colorInt
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
    gpuWetBrushesEnabled: Boolean = true
) {
    if (layers.isEmpty()) {
        for (stroke in strokes) {
            drawSingleStroke(stroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
        }
        if (previewStroke != null) {
            drawSingleStroke(previewStroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
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

        val isWetTool = currentTool == StrokeTool.WATERCOLOR || currentTool == StrokeTool.OIL_PAINT || currentTool == StrokeTool.SMUDGE || currentTool == StrokeTool.SPLATTER
        val isWetLayer = (isWetTool && isPreviewOnThisLayer) || layerStrokes.any { it.tool == StrokeTool.WATERCOLOR || it.tool == StrokeTool.OIL_PAINT || it.tool == StrokeTool.SMUDGE || it.tool == StrokeTool.SPLATTER }
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
                wetBrushEngine = wetBrushEngine
            )
            continue
        }

        val cacheKey = "${pageIdx}_${layer.id}"
        val strokesHash = layerStrokes.hashCode()

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
                        drawSingleStroke(stroke, offsetY - pageTopY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
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
                drawSingleStroke(previewStroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
            }
        } else {
            val isNormal = layer.blendMode.equals("NORMAL", ignoreCase = true)
            val isOpaque = layer.opacity >= 0.99f
    
            if (isNormal && isOpaque) {
                for (stroke in layerStrokes) {
                    drawSingleStroke(stroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
                }
                if (isPreviewOnThisLayer && previewStroke != null) {
                    drawSingleStroke(previewStroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
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
                        drawSingleStroke(stroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
                    }
                    if (isPreviewOnThisLayer && previewStroke != null) {
                        drawSingleStroke(previewStroke, offsetY, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
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
    wetBrushEngine: com.authorss81.noteflow.services.WetBrushEngine
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
                seed = ((pageWidth * 131f) + (pageHeight * 71f) + (offsetY * 29f)) % 1000f
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
                drawSingleStroke(stroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
            }
            if (previewStroke != null) {
                drawSingleStroke(previewStroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
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
            drawSingleStroke(stroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
        }
        if (previewStroke != null) {
            drawSingleStroke(previewStroke, 0f, isDarkPaper = isDarkPaper, inkRenderer = inkRenderer)
        }
    }
}

private fun DrawScope.drawSingleStroke(
    stroke: Stroke,
    offsetY: Float,
    isDarkPaper: Boolean = false,
    inkRenderer: CanvasStrokeRenderer? = null
) {
    if (stroke.isAdvanced && inkRenderer != null) {
        try {
            val inkStroke = convertToInkStroke(stroke)
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

    var rawColor = stroke.color
    if (isDarkPaper && stroke.tool != StrokeTool.HIGHLIGHTER) {
        val lum = 0.299f * rawColor.red + 0.587f * rawColor.green + 0.114f * rawColor.blue
        if (lum < 0.2f) {
            rawColor = Color(0xFFF8FAFC)
        }
    }
    val isSolidTool = stroke.tool == StrokeTool.PEN ||
                      stroke.tool == StrokeTool.FOUNTAIN_PEN ||
                      stroke.tool == StrokeTool.FINELINER ||
                      stroke.tool == StrokeTool.CALLIGRAPHIC ||
                      stroke.tool == StrokeTool.CHISEL_MARKER ||
                      stroke.tool == StrokeTool.DOTTED ||
                      stroke.tool == StrokeTool.LINE ||
                      stroke.tool.isShapeTool

    val color = when {
        stroke.tool == StrokeTool.HIGHLIGHTER -> stroke.color.copy(alpha = 0.35f)
        isSolidTool -> rawColor.copy(alpha = 1.0f)
        else -> rawColor
    }
    val strokeWidth = stroke.width

    when (stroke.tool) {
        StrokeTool.PEN, StrokeTool.HIGHLIGHTER -> {
            if (stroke.points.size > 1) {
                // Quadratic Bezier Curve Path Smoothing for silky smooth pen ink
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
            } else if (stroke.points.size == 1) {
                drawCircle(color, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
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
                    // Velocity modulation: fast -> thin, slow -> thick flourishes
                    val dynamicWidth = (strokeWidth * (1.7f - (dist / 14f).coerceIn(0f, 1.1f))).coerceAtLeast(1f)
                    drawLine(
                        color = color,
                        start = Offset(p1.x, p1.y + offsetY),
                        end = Offset(p2.x, p2.y + offsetY),
                        strokeWidth = dynamicWidth,
                        cap = StrokeCap.Round
                    )
                }
            } else if (stroke.points.size == 1) {
                drawCircle(color, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.PENCIL -> {
            val pencilColor = color.copy(alpha = 0.82f)
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
            BrushTextureEngine.drawBitmapStampSequence(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                baseSize = strokeWidth * 2.5f,
                color = color.copy(alpha = 0.35f),
                textureType = BrushTextureEngine.TextureType.AIRBRUSH_SPRAY,
                spacingFactor = 0.2f,
                scatterFactor = 0.35f
            )
        }
        StrokeTool.OIL_PAINT -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawTexturedStrokePath(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth * 1.3f,
                color = color.copy(alpha = (color.alpha * 0.9f).coerceIn(0.6f, 1.0f)),
                textureType = BrushTextureEngine.TextureType.CANVAS_WEAVE
            )
        }
        StrokeTool.WATERCOLOR -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawTexturedStrokePath(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                strokeWidth = strokeWidth * 1.5f,
                color = color.copy(alpha = (color.alpha * 0.60f).coerceIn(0.2f, 0.85f)),
                textureType = BrushTextureEngine.TextureType.WATERCOLOR_PAPER
            )
        }
        StrokeTool.SPLATTER, StrokeTool.SMUDGE -> {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            BrushTextureEngine.drawBitmapStampSequence(
                nativeCanvas = nativeCanvas,
                points = stroke.points,
                offsetY = offsetY,
                baseSize = strokeWidth * 3.0f,
                color = color.copy(alpha = 0.65f),
                textureType = BrushTextureEngine.TextureType.SPLATTER_DROPS,
                spacingFactor = 0.45f,
                scatterFactor = 0.55f
            )
        }
        StrokeTool.MARKER -> {
            val markerColor = color.copy(alpha = 0.42f)
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
                    color = markerColor,
                    style = DrawStrokeStyle(width = strokeWidth * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            } else if (stroke.points.size == 1) {
                drawCircle(markerColor, radius = strokeWidth, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.CALLIGRAPHIC -> {
            if (stroke.points.size > 1) {
                val pts = stroke.points
                val angle = Math.toRadians(45.0)
                val dx = (cos(angle) * strokeWidth / 1.5f).toFloat()
                val dy = (sin(angle) * strokeWidth / 1.5f).toFloat()
                val path = Path().apply {
                    fillType = PathFillType.NonZero
                }

                for (i in 1 until pts.size) {
                    val p0 = pts[i - 1]
                    val p1 = pts[i]
                    path.moveTo(p0.x - dx, p0.y - dy + offsetY)
                    path.lineTo(p1.x - dx, p1.y - dy + offsetY)
                    path.lineTo(p1.x + dx, p1.y + dy + offsetY)
                    path.lineTo(p0.x + dx, p0.y + dy + offsetY)
                    path.close()
                }
                drawPath(path = path, color = color)
            } else if (stroke.points.size == 1) {
                drawCircle(color, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.DOTTED -> {
            if (stroke.points.size > 1) {
                val pts = stroke.points
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y + offsetY)
                    for (i in 1 until pts.size) {
                        lineTo(pts[i].x, pts[i].y + offsetY)
                    }
                }
                val dashIntervals = floatArrayOf(strokeWidth * 2f, strokeWidth * 2.5f)
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
            } else if (stroke.points.size == 1) {
                drawCircle(color, radius = strokeWidth / 2f, center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY))
            }
        }
        StrokeTool.NEON -> {
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
                    color = color.copy(alpha = 0.25f),
                    style = DrawStrokeStyle(width = strokeWidth * 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.85f),
                    style = DrawStrokeStyle(width = strokeWidth * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.95f),
                    style = DrawStrokeStyle(width = strokeWidth * 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            } else if (stroke.points.size == 1) {
                val center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY)
                drawCircle(color.copy(alpha = 0.25f), radius = strokeWidth * 1.6f, center = center)
                drawCircle(color.copy(alpha = 0.85f), radius = strokeWidth * 0.8f, center = center)
                drawCircle(Color.White, radius = strokeWidth * 0.3f, center = center)
            }
        }
        StrokeTool.FINELINER -> {
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
                    color = color.copy(alpha = 0.95f),
                    style = DrawStrokeStyle(width = strokeWidth.coerceAtLeast(1.2f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            } else if (stroke.points.size == 1) {
                drawCircle(
                    color = color,
                    radius = strokeWidth / 2f,
                    center = Offset(stroke.points.first().x, stroke.points.first().y + offsetY)
                )
            }
        }
        StrokeTool.CHISEL_MARKER -> {
            if (stroke.points.size > 1) {
                val pts = stroke.points
                val angle = Math.toRadians(30.0)
                val dx = (cos(angle) * strokeWidth * 0.9f).toFloat()
                val dy = (sin(angle) * strokeWidth * 0.9f).toFloat()
                val path = Path().apply {
                    fillType = PathFillType.NonZero
                }
                for (i in 1 until pts.size) {
                    val p0 = pts[i - 1]
                    val p1 = pts[i]
                    path.moveTo(p0.x - dx, p0.y - dy + offsetY)
                    path.lineTo(p1.x - dx, p1.y - dy + offsetY)
                    path.lineTo(p1.x + dx, p1.y + dy + offsetY)
                    path.lineTo(p0.x + dx, p0.y + dy + offsetY)
                    path.close()
                }
                drawPath(path = path, color = color.copy(alpha = 0.88f))
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
                drawLine(color, Offset(stroke.start.x, stroke.start.y + offsetY), Offset(stroke.end.x, stroke.end.y + offsetY), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }
        }
        StrokeTool.RECTANGLE -> {
            if (stroke.start != null && stroke.end != null) {
                val topLeft = Offset(minOf(stroke.start.x, stroke.end.x), minOf(stroke.start.y + offsetY, stroke.end.y + offsetY))
                val rectSize = Size(abs(stroke.end.x - stroke.start.x), abs(stroke.end.y - stroke.start.y))
                drawRect(color, topLeft, rectSize, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
            }
        }
        StrokeTool.ELLIPSE -> {
            if (stroke.start != null && stroke.end != null) {
                val topLeft = Offset(minOf(stroke.start.x, stroke.end.x), minOf(stroke.start.y + offsetY, stroke.end.y + offsetY))
                val ovalSize = Size(abs(stroke.end.x - stroke.start.x), abs(stroke.end.y - stroke.start.y))
                drawOval(color, topLeft, ovalSize, style = DrawStrokeStyle(width = strokeWidth))
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
                drawPath(path = path, color = color, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
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
                drawPath(path = path, color = color, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
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
                drawPath(path = path, color = color, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
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
                drawPath(path = path, color = color, style = DrawStrokeStyle(width = strokeWidth, join = StrokeJoin.Round))
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
                        this.color = stroke.colorInt
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
    LaunchedEffect(currentNote.id) {
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
            )
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
            }
            .semantics { contentDescription = "Sticky note" }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(noteColor)
                .border(1.dp, Color.Black.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
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
                                    dragOffsetX += dragAmount.x / currentZoom
                                    dragOffsetY += dragAmount.y / currentZoom
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
    onVoiceSpeedChange: (Float) -> Unit
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

    val screenX = (currentEmbed.x + dragOffsetX) * currentZoom + currentPan.x
    val screenY = (currentEmbed.y + dragOffsetY) * currentZoom + currentPan.y
    val actualWidth = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE && currentEmbed.isCollapsed) 48f else resizeWidth
    val actualHeight = if (currentEmbed.type == MediaEmbedType.AUDIO_NOTE && currentEmbed.isCollapsed) 48f else resizeHeight

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.toInt(), screenY.toInt()) }
            .width((actualWidth * currentZoom).dp)
            .height((actualHeight * currentZoom).dp)
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
                            dragOffsetX += dragAmount.x / currentZoom
                            dragOffsetY += dragAmount.y / currentZoom
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
                        }
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
    }
}
