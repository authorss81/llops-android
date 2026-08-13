package com.authorss81.noteflow.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.*
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.VoiceNoteManager
import com.authorss81.noteflow.ui.components.AnnotationCanvas
import com.authorss81.noteflow.ui.components.BacklinksInspectorBottomSheet
import com.authorss81.noteflow.ui.components.PromptNameDialog
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Toolbar State Management enum for floating capsule & popover bottom sheets.
 */
enum class FloatingToolbarState {
    COLLAPSED,      // Floating bottom pill capsule at rest
    TOOL_PICKER,    // Expanded popover/bottom-sheet with tool suite
    COLOR_PICKER,   // Expanded popover/bottom-sheet with color swatches
    WIDTH_PICKER,   // Expanded popover/bottom-sheet with stroke width slider & presets
    SETTINGS_MENU,  // Expanded popover/bottom-sheet with canvas options (Infinite, Division, Grid, BG color, Zoom)
    HIDDEN_DRAWING  // Fully hidden while actively drawing on the canvas
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    page: NotePageEntity,
    viewModel: NoteflowViewModel,
    onBack: () -> Unit,
    onOpenPage: (NotePageEntity) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var currentTool by remember { mutableStateOf(StrokeTool.PEN) }
    var lastDrawingTool by remember { mutableStateOf(StrokeTool.PEN) }
    LaunchedEffect(currentTool) {
        if (currentTool != StrokeTool.PAN && currentTool != StrokeTool.SELECT) {
            lastDrawingTool = currentTool
        }
    }
    var currentColor by remember { mutableStateOf(Color(0xFF1B365D)) }
    var currentWidth by remember { mutableFloatStateOf(4f) }
    var template by remember { mutableStateOf(page.template ?: "blank") }
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var stickyNotes by remember { mutableStateOf<List<CanvasStickyNote>>(emptyList()) }
    var mediaEmbeds by remember { mutableStateOf<List<CanvasMediaEmbed>>(emptyList()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showBacklinks by remember { mutableStateOf(false) }
    var showClearCanvasWarning by remember { mutableStateOf(false) }

    val context = LocalContext.current
    com.authorss81.noteflow.utils.JankStatsHelper.MonitorJank("EditorScreen")
    val voiceNoteManager = remember { VoiceNoteManager(context) }

    // Release recorder/player & cancel timer jobs when leaving the editor
    DisposableEffect(voiceNoteManager) {
        onDispose { voiceNoteManager.release() }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceNoteManager.startRecording(page.id)
        } else {
            android.widget.Toast.makeText(context, "Microphone permission is required to record voice notes", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val bgImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bytes = inputStream?.readBytes()
                if (bytes != null) {
                    val fileName = "custom_bg_${System.currentTimeMillis()}.png"
                    scope.launch {
                        val savedPath = com.authorss81.noteflow.services.ImportExportService.persistFile(context, fileName, bytes)
                        viewModel.updatePageSource(page.id, savedPath, "image")
                        android.widget.Toast.makeText(context, "Custom paper background loaded!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to load custom background image", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }


    val isRecordingVoice by voiceNoteManager.isRecording.collectAsState()
    val recordingElapsedMs by voiceNoteManager.recordingElapsedMs.collectAsState()
    val isPlayingVoice by voiceNoteManager.isPlaying.collectAsState()
    val activeVoicePositionMs by voiceNoteManager.playbackPositionMs.collectAsState()
    val activeVoiceSpeed by voiceNoteManager.playbackSpeed.collectAsState()
    val activeVoicePlaybackFilePath by voiceNoteManager.activePlayingFilePath.collectAsState()
    val voiceRecordingError by voiceNoteManager.recordingError.collectAsState()
    val voicePlaybackError by voiceNoteManager.playbackError.collectAsState()

    val paletteItems by viewModel.paletteItems.collectAsState()

    // S-Pen / Stylus Palm Rejection & Pressure Toggle State
    var palmRejectionEnabled by remember { mutableStateOf(true) }
    var stylusPressureEnabled by remember { mutableStateOf(true) }
    var advancedBrushesEnabled by remember { mutableStateOf(false) }

    // Dynamic Color Palette Manager (Eyedropper Sampled Colors)
    var customPalette by remember {
        mutableStateOf(
            listOf(
                Color(0xFF1B365D), // Deep Ink Blue
                Color(0xFFDC2626), // Crimson Red
                Color(0xFF16A34A), // Forest Green
                Color(0xFFD97706), // Amber
                Color(0xFF9333EA), // Royal Purple
                Color(0xFF0D9488), // Teal
                Color(0xFFE11D48), // Coral Pink
                Color(0xFF475569), // Slate Charcoal
                Color(0xFF000000), // Pitch Black
                Color(0xFFFFFFFF), // Cream White
                Color(0xFF4F46E5), // Indigo
                Color(0xFFCA8A04), // Warm Ochre
                Color(0xFFFF00AA)  // Rainbow
            )
        )
    }

    // Floating Distraction-Free Toolbar State & Quick Pen Presets
    var toolbarState by remember { mutableStateOf(FloatingToolbarState.COLLAPSED) }
    val penPresets = remember {
        listOf(
            PenPreset(1, "Highlighter Felt", StrokeTool.HIGHLIGHTER, 0xFF84CC16.toInt(), 16f),
            PenPreset(2, "Sharpie Felt Tip", StrokeTool.MARKER, 0xFF9333EA.toInt(), 6f),
            PenPreset(3, "Calligraphy Fountain Nib", StrokeTool.CALLIGRAPHIC, 0xFF0F172A.toInt(), 4f),
            PenPreset(4, "Red Dotted Line Pen", StrokeTool.DOTTED, 0xFFEF4444.toInt(), 4f),
            PenPreset(5, "Chalk Neon Glow", StrokeTool.NEON, 0xFFF59E0B.toInt(), 8f),
            PenPreset(6, "Flat Chisel Marker", StrokeTool.CHISEL_MARKER, 0xFFF97316.toInt(), 14f),
            PenPreset(7, "Cyan Fineliner", StrokeTool.FINELINER, 0xFF06B6D4.toInt(), 2.5f),
            PenPreset(8, "Chisel Brush Pen", StrokeTool.CALLIGRAPHIC, 0xFF7C3AED.toInt(), 10f),
            PenPreset(9, "Graphite Pencil", StrokeTool.PENCIL, 0xFF334155.toInt(), 3.5f),
            PenPreset(10, "Soft Mint Watercolor", StrokeTool.WATERCOLOR, 0xFF10B981.toInt(), 12f),
            PenPreset(11, "Laser Pointer", StrokeTool.LASER, 0xFFFF0044.toInt(), 12f)
        )
    }
    var activePresetId by remember { mutableStateOf<Int?>(1) }
    var activeCustomPresetId by remember { mutableStateOf<String?>(null) }

    // Undo / Redo Stacks
    var undoStack by remember { mutableStateOf<List<List<Stroke>>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<List<Stroke>>>(emptyList()) }

    // Layers State
    var layers by remember { mutableStateOf<List<LayerEntity>>(emptyList()) }
    var activeLayerId by remember { mutableStateOf<String?>(null) }
    var showLayersSheet by remember { mutableStateOf(false) }

    // Paper Color & Infinite Canvas Page Division State
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isAppDark = remember(surfaceColor) {
        (0.299f * surfaceColor.red + 0.587f * surfaceColor.green + 0.114f * surfaceColor.blue) < 0.5f
    }
    var paperColorHex by remember(page.id, page.paperColor, isAppDark) {
        mutableStateOf(page.paperColor ?: if (isAppDark) "#1E293B" else "#FFFFFF")
    }
    var divideIntoPages by remember { mutableStateOf(true) }
    var gpuWetBrushesEnabled by remember { mutableStateOf(viewModel.settings.gpuWetBrushesEnabled) }
    var shapeAutoSnapEnabled by remember { mutableStateOf(viewModel.settings.shapeAutoSnapEnabled) }

    LaunchedEffect(Unit) {
        val detectedTier = com.authorss81.noteflow.utils.DeviceCompatibilityManager.getDeviceTier(context, viewModel.settings)
        if (detectedTier == com.authorss81.noteflow.utils.DeviceTier.LOW_END) {
            if (!viewModel.settings.lowEndWarningShown) {
                gpuWetBrushesEnabled = false
                viewModel.settings.gpuWetBrushesEnabled = false
                viewModel.settings.lowEndWarningShown = true
                android.widget.Toast.makeText(
                    context,
                    "GPU Wet Brushes disabled for low-end device performance. You can override this in settings.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Multi-page PDF & Continuous View State
    var currentPdfPage by remember { mutableIntStateOf(0) }
    var isContinuousMode by remember { mutableStateOf(true) } // Default to Infinite Canvas mode
    val isPdf = remember(page.sourceFileType, page.sourceFilePath) {
        page.sourceFileType?.equals("pdf", ignoreCase = true) == true && !page.sourceFilePath.isNullOrEmpty()
    }
    var pdfTotalPages by remember { mutableIntStateOf(1) }

    var pdfPageBitmaps by remember { mutableStateOf<Map<Int, ImageBitmap>>(emptyMap()) }
    var activeRawBitmapMap by remember { mutableStateOf<Map<Int, android.graphics.Bitmap>>(emptyMap()) }
    var visiblePageWindow by remember { mutableStateOf(setOf(0)) }

    // Interactive Zoom & Pan State
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Auto-Save Debounce Job & Initial Load Guard
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var isInitialLoadComplete by remember { mutableStateOf(false) }

    // Load Initial Strokes & Media Embeds & Sticky Notes
    LaunchedEffect(page.id) {
        val loadedStrokes = viewModel.repository.getStrokesForPage(page.id)
        strokes = loadedStrokes

        val loadedLayers = viewModel.repository.getLayersForPage(page.id)
        layers = loadedLayers
        activeLayerId = loadedLayers.firstOrNull { !it.locked }?.id ?: loadedLayers.firstOrNull()?.id

        val (loadedNotes, loadedEmbeds) = viewModel.repository.getCanvasItemsForPage(page.id)
        stickyNotes = loadedNotes
        mediaEmbeds = loadedEmbeds

        if (!isPdf) {
            val maxStrokePage = loadedStrokes.maxOfOrNull { it.pdfPage } ?: 0
            val maxNotePage = loadedNotes.maxOfOrNull { it.pdfPage } ?: 0
            val maxEmbedPage = loadedEmbeds.maxOfOrNull { it.pdfPage } ?: 0
            val maxPage = maxOf(maxStrokePage, maxNotePage, maxEmbedPage)
            pdfTotalPages = maxOf(1, maxPage + 1)
        }
        isInitialLoadComplete = true
    }

    // Unmount Guard: flush save when navigating away
    DisposableEffect(page.id) {
        onDispose {
            if (isInitialLoadComplete) {
                viewModel.viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                    viewModel.repository.saveStrokesForPage(page.id, strokes)
                    viewModel.repository.saveCanvasItemsForPage(page.id, stickyNotes, mediaEmbeds)
                    viewModel.repository.saveLayersForPage(page.id, layers)
                }
            }
        }
    }

    // PDF Page Count & Background Rendering
    LaunchedEffect(page.sourceFilePath, isPdf) {
        if (isPdf && !page.sourceFilePath.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                val total = getPdfPageCount(page.sourceFilePath)
                if (total > 0) pdfTotalPages = total
            }
        }
    }

    // Memory-Bounded Render Window for PDF pages & Multi-page Image calculations
    LaunchedEffect(page.sourceFilePath, isPdf, visiblePageWindow, isContinuousMode, currentPdfPage) {
        if (isPdf && !page.sourceFilePath.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                val neededPages = if (isContinuousMode) visiblePageWindow else setOf(currentPdfPage)
                val newRawMap = mutableMapOf<Int, android.graphics.Bitmap>()
                val newImageMap = mutableMapOf<Int, ImageBitmap>()

                for (p in neededPages) {
                    val raw = renderPdfPageToRawBitmap(page.sourceFilePath, p, context = context)
                    if (raw != null) {
                        newRawMap[p] = raw
                        newImageMap[p] = raw.asImageBitmap()
                    }
                }

                // Return old unused raw bitmaps to the pool for reuse
                activeRawBitmapMap.forEach { (pageIdx, bmp) ->
                    if (pageIdx !in neededPages) {
                        com.authorss81.noteflow.utils.BitmapPool.release(bmp)
                    }
                }

                activeRawBitmapMap = newRawMap
                pdfPageBitmaps = newImageMap
            }
        } else if (!page.sourceFilePath.isNullOrEmpty() && page.sourceFileType?.equals("image", ignoreCase = true) == true) {
            withContext(Dispatchers.IO) {
                val raw = decodeBoundedBitmap(page.sourceFilePath)
                if (raw != null) {
                    activeRawBitmapMap.forEach { (_, bmp) -> com.authorss81.noteflow.utils.BitmapPool.release(bmp) }
                    activeRawBitmapMap = mapOf(0 to raw)
                    pdfPageBitmaps = mapOf(0 to raw.asImageBitmap())

                    // Calculate if imported photo height spans multiple pages:
                    val imgW = raw.width.toFloat()
                    val imgH = raw.height.toFloat()
                    if (imgW > 0f) {
                        val scale = 1080f / imgW
                        val scaledHeight = imgH * scale
                        val pageCountNeeded = Math.ceil((scaledHeight / 1528f).toDouble()).toInt()
                        if (pageCountNeeded > 1) {
                            pdfTotalPages = maxOf(pdfTotalPages, pageCountNeeded)
                        }
                    }
                }
            }
        }
    }

    fun triggerAutoSave(newStrokes: List<Stroke>) {
        if (!isInitialLoadComplete) return
        saveJob?.cancel()
        saveJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            delay(1000) // 1s Debounce
            viewModel.repository.saveStrokesForPage(page.id, newStrokes)
        }
    }

    fun handleStrokesChange(newStrokes: List<Stroke>) {
        val newUndo = undoStack.toMutableList()
        newUndo.add(strokes)
        if (newUndo.size > 30) newUndo.removeAt(0)
        undoStack = newUndo
        redoStack = emptyList()
        strokes = newStrokes
        triggerAutoSave(newStrokes)
    }

    fun handleLayersChange(newLayers: List<LayerEntity>) {
        layers = newLayers
        scope.launch {
            viewModel.repository.saveLayersForPage(page.id, newLayers)
        }
    }

    fun onAddLayer() {
        val nextZ = (layers.maxOfOrNull { it.zOrder } ?: -1) + 1
        val newLayerId = "layer_" + java.util.UUID.randomUUID().toString()
        val newLayer = LayerEntity(
            id = newLayerId,
            pageId = page.id,
            name = "Layer ${layers.size + 1}",
            zOrder = nextZ,
            opacity = 1.0f,
            blendMode = "NORMAL",
            visible = true,
            locked = false
        )
        val updated = layers + newLayer
        handleLayersChange(updated)
        activeLayerId = newLayerId
    }

    fun onUpdateLayer(updatedLayer: LayerEntity) {
        val updated = layers.map { if (it.id == updatedLayer.id) updatedLayer else it }
        handleLayersChange(updated)
    }

    fun onDeleteLayer(deletedLayerId: String) {
        if (layers.size <= 1) return
        val remainingLayers = layers.filter { it.id != deletedLayerId }
        val targetLayerId = remainingLayers.firstOrNull()?.id ?: "layer_default"

        val updatedStrokes = strokes.map { stroke ->
            if (stroke.layerId == deletedLayerId) stroke.copy(layerId = targetLayerId) else stroke
        }
        handleStrokesChange(updatedStrokes)

        handleLayersChange(remainingLayers)
        if (activeLayerId == deletedLayerId) {
            activeLayerId = targetLayerId
        }
    }

    fun onDuplicateLayer(layerToDuplicate: LayerEntity) {
        val nextZ = (layers.maxOfOrNull { it.zOrder } ?: -1) + 1
        val dupLayerId = "layer_" + java.util.UUID.randomUUID().toString()
        val dupLayer = layerToDuplicate.copy(
            id = dupLayerId,
            name = "${layerToDuplicate.name} Copy",
            zOrder = nextZ
        )

        val layerStrokes = strokes.filter { (it.layerId ?: "layer_default") == layerToDuplicate.id }
        val duplicatedStrokes = layerStrokes.map { stroke ->
            stroke.copy(
                id = java.util.UUID.randomUUID().toString(),
                layerId = dupLayerId
            )
        }
        if (duplicatedStrokes.isNotEmpty()) {
            handleStrokesChange(strokes + duplicatedStrokes)
        }

        handleLayersChange(layers + dupLayer)
        activeLayerId = dupLayerId
    }

    fun onMergeDown(layer: LayerEntity) {
        val sorted = layers.sortedBy { it.zOrder }
        val currentIndex = sorted.indexOfFirst { it.id == layer.id }
        if (currentIndex > 0) {
            val lowerLayer = sorted[currentIndex - 1]

            val updatedStrokes = strokes.map { stroke ->
                if ((stroke.layerId ?: "layer_default") == layer.id) {
                    stroke.copy(layerId = lowerLayer.id)
                } else {
                    stroke
                }
            }
            handleStrokesChange(updatedStrokes)

            val remainingLayers = layers.filter { it.id != layer.id }
            handleLayersChange(remainingLayers)

            if (activeLayerId == layer.id) {
                activeLayerId = lowerLayer.id
            }
        }
    }

    fun onMoveUp(layer: LayerEntity) {
        val sorted = layers.sortedBy { it.zOrder }
        val currentIndex = sorted.indexOfFirst { it.id == layer.id }
        if (currentIndex < sorted.size - 1) {
            val upperLayer = sorted[currentIndex + 1]
            val updated = layers.map {
                when (it.id) {
                    layer.id -> it.copy(zOrder = upperLayer.zOrder)
                    upperLayer.id -> it.copy(zOrder = layer.zOrder)
                    else -> it
                }
            }
            handleLayersChange(updated)
        }
    }

    fun onMoveDown(layer: LayerEntity) {
        val sorted = layers.sortedBy { it.zOrder }
        val currentIndex = sorted.indexOfFirst { it.id == layer.id }
        if (currentIndex > 0) {
            val lowerLayer = sorted[currentIndex - 1]
            val updated = layers.map {
                when (it.id) {
                    layer.id -> it.copy(zOrder = lowerLayer.zOrder)
                    lowerLayer.id -> it.copy(zOrder = layer.zOrder)
                    else -> it
                }
            }
            handleLayersChange(updated)
        }
    }

    fun handleMediaEmbedsChange(newEmbeds: List<CanvasMediaEmbed>) {
        mediaEmbeds = newEmbeds
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            viewModel.repository.saveCanvasItemsForPage(page.id, stickyNotes, newEmbeds)
        }
    }

    fun handleStickyNotesChange(newNotes: List<CanvasStickyNote>) {
        stickyNotes = newNotes
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            viewModel.repository.saveCanvasItemsForPage(page.id, newNotes, mediaEmbeds)
        }
    }

    fun insertPage(before: Boolean) {
        val pageStride = 1528f + 64f
        val currentIdx = if (isPdf) currentPdfPage else ((-panOffset.y / zoomScale) / pageStride).toInt().coerceAtLeast(0)
        val targetThreshold = if (before) currentIdx else currentIdx + 1

        val updatedStrokes = strokes.map { stroke ->
            if (stroke.pdfPage >= targetThreshold) {
                val updatedPoints = stroke.points.map { pt -> pt.copy(y = pt.y + pageStride) }
                val updatedStart = stroke.start?.copy(y = stroke.start.y + pageStride)
                val updatedEnd = stroke.end?.copy(y = stroke.end.y + pageStride)
                stroke.copy(
                    pdfPage = stroke.pdfPage + 1,
                    points = updatedPoints,
                    start = updatedStart,
                    end = updatedEnd
                )
            } else {
                stroke
            }
        }

        val updatedNotes = stickyNotes.map { note ->
            if (note.pdfPage >= targetThreshold) {
                note.copy(
                    pdfPage = note.pdfPage + 1,
                    y = note.y + pageStride
                )
            } else {
                note
            }
        }

        val updatedEmbeds = mediaEmbeds.map { embed ->
            if (embed.pdfPage >= targetThreshold) {
                embed.copy(
                    pdfPage = embed.pdfPage + 1,
                    y = embed.y + pageStride
                )
            } else {
                embed
            }
        }

        undoStack = undoStack + listOf(strokes)
        redoStack = emptyList()

        strokes = updatedStrokes
        stickyNotes = updatedNotes
        mediaEmbeds = updatedEmbeds
        pdfTotalPages += 1

        scope.launch {
            viewModel.repository.saveStrokesForPage(page.id, updatedStrokes)
            viewModel.repository.saveCanvasItemsForPage(page.id, updatedNotes, updatedEmbeds)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val fileName = "photo_${System.currentTimeMillis()}.jpg"
                        val savedFile = ImportExportService.persistFile(context, fileName, bytes)
                        val pageStride = 1528f + 64f
                        val centerViewportY = (-panOffset.y + 600f) / zoomScale
                        val activePageIdx = if (!isContinuousMode) currentPdfPage else (centerViewportY / pageStride).toInt().coerceIn(0, if (isPdf) (pdfTotalPages - 1).coerceAtLeast(0) else Int.MAX_VALUE)
                        val activePageYOffset = if (isContinuousMode) activePageIdx * pageStride else 0f

                        val newPhotoEmbed = CanvasMediaEmbed(
                            pageId = page.id,
                            type = MediaEmbedType.PHOTO,
                            x = 120f,
                            y = activePageYOffset + 180f,
                            width = 340f,
                            height = 260f,
                            contentUrlOrPath = savedFile,
                            textContent = "Photo attachment",
                            pdfPage = activePageIdx
                        )
                        handleMediaEmbedsChange(mediaEmbeds + newPhotoEmbed)
                        Toast.makeText(context, "Photo attached to canvas", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to attach photo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleUndo() {
        if (undoStack.isNotEmpty()) {
            val previousState = undoStack.last()
            undoStack = undoStack.dropLast(1)
            redoStack = redoStack + listOf(strokes)
            strokes = previousState
            triggerAutoSave(previousState)
        }
    }

    fun handleRedo() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.last()
            redoStack = redoStack.dropLast(1)
            undoStack = undoStack + listOf(strokes)
            strokes = nextState
            triggerAutoSave(nextState)
        }
    }

    BackHandler {
        saveJob?.cancel()
        if (isInitialLoadComplete) {
            viewModel.viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                viewModel.repository.saveStrokesForPage(page.id, strokes)
                viewModel.repository.saveCanvasItemsForPage(page.id, stickyNotes, mediaEmbeds)
                viewModel.repository.saveLayersForPage(page.id, layers)
            }
        }
        onBack()
    }

    var showMinimap by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Navigation Back Button
                    IconButton(
                        onClick = {
                            saveJob?.cancel()
                            if (isInitialLoadComplete) {
                                viewModel.viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                                    viewModel.repository.saveStrokesForPage(page.id, strokes)
                                    viewModel.repository.saveCanvasItemsForPage(page.id, stickyNotes, mediaEmbeds)
                                    viewModel.repository.saveLayersForPage(page.id, layers)
                                }
                            }
                            onBack()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Page Title Display with Edit Indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showRenameDialog = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = page.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Rename Page",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                    }

                    // Voice Note Recording Pill
                    FilterChip(
                        selected = isRecordingVoice,
                        onClick = {
                            if (isRecordingVoice) {
                                val result = voiceNoteManager.stopRecording()
                                if (result != null) {
                                    val pageStride = 1528f + 64f
                                    val centerViewportY = (-panOffset.y + 600f) / zoomScale
                                    val activePageIdx = if (!isContinuousMode) currentPdfPage else (centerViewportY / pageStride).toInt().coerceIn(0, if (isPdf) (pdfTotalPages - 1).coerceAtLeast(0) else Int.MAX_VALUE)
                                    val activePageYOffset = if (isContinuousMode) activePageIdx * pageStride else 0f

                                    val newAudioEmbed = CanvasMediaEmbed(
                                        pageId = page.id,
                                        type = MediaEmbedType.AUDIO_NOTE,
                                        width = 320f,
                                        height = 135f,
                                        x = 100f,
                                        y = activePageYOffset + 150f,
                                        contentUrlOrPath = result.filePath,
                                        durationMs = result.durationMs,
                                        waveformAmplitudes = result.waveformAmplitudes,
                                        pdfPage = activePageIdx
                                    )
                                    handleMediaEmbedsChange(mediaEmbeds + newAudioEmbed)
                                }
                            } else {
                                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (hasPermission) {
                                    voiceNoteManager.startRecording(page.id)
                                } else {
                                    recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        label = {
                            if (isRecordingVoice) {
                                val sec = (recordingElapsedMs / 1000) % 60
                                val min = (recordingElapsedMs / 1000) / 60
                                Text(String.format("%02d:%02d", min, sec))
                            } else {
                                Text("Voice")
                            }
                        },
                        leadingIcon = {
                            Icon(
                                if (isRecordingVoice) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                                contentDescription = "Voice Note Recording",
                                tint = if (isRecordingVoice) Color.Red else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.height(32.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Add Embeds Dropdown Menu (Sticky Note, Photo, Code)
                    var showEmbedMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showEmbedMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = "Add Embed")
                    }
                    DropdownMenu(
                        expanded = showEmbedMenu,
                        onDismissRequest = { showEmbedMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sticky Note") },
                            leadingIcon = { Icon(Icons.Outlined.StickyNote2, contentDescription = null) },
                            onClick = {
                                val pageStride = 1528f + 64f
                                val centerViewportY = (-panOffset.y + 600f) / zoomScale
                                val activePageIdx = if (!isContinuousMode) currentPdfPage else (centerViewportY / pageStride).toInt().coerceIn(0, if (isPdf) (pdfTotalPages - 1).coerceAtLeast(0) else Int.MAX_VALUE)
                                val activePageYOffset = if (isContinuousMode) activePageIdx * pageStride else 0f

                                val newNote = CanvasStickyNote(
                                    id = java.util.UUID.randomUUID().toString(),
                                    text = "Tap to edit note",
                                    colorHex = "#FEF08A",
                                    x = 120f,
                                    y = activePageYOffset + 150f,
                                    pdfPage = activePageIdx
                                )
                                stickyNotes = stickyNotes + newNote
                                showEmbedMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Photo Attachment") },
                            leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null) },
                            onClick = {
                                showEmbedMenu = false
                                photoPickerLauncher.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Code Block") },
                            leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                            onClick = {
                                val pageStride = 1528f + 64f
                                val centerViewportY = (-panOffset.y + 600f) / zoomScale
                                val activePageIdx = if (!isContinuousMode) currentPdfPage else (centerViewportY / pageStride).toInt().coerceIn(0, if (isPdf) (pdfTotalPages - 1).coerceAtLeast(0) else Int.MAX_VALUE)
                                val activePageYOffset = if (isContinuousMode) activePageIdx * pageStride else 0f

                                val newCodeEmbed = CanvasMediaEmbed(
                                    pageId = page.id,
                                    type = MediaEmbedType.CODE_BLOCK,
                                    x = 120f,
                                    y = activePageYOffset + 220f,
                                    textContent = "// Write code here\nfun hello() {\n    println(\"NoteFlow\")\n}",
                                    codeLanguage = "kotlin",
                                    pdfPage = activePageIdx
                                )
                                handleMediaEmbedsChange(mediaEmbeds + newCodeEmbed)
                                showEmbedMenu = false
                            }
                        )
                    }

                    // Layers Toggle Button
                    IconButton(onClick = { showLayersSheet = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Layers, contentDescription = "Layers Manager", tint = if (layers.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }

                    // Overflow Settings & Knowledge Connections Menu
                    var showOverflowMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showOverflowMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More Options")
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Knowledge Graph & Backlinks") },
                            leadingIcon = { Icon(Icons.Outlined.Hub, contentDescription = null) },
                            onClick = {
                                showBacklinks = true
                                showOverflowMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Page as PNG") },
                            leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                scope.launch {
                                    val bgBmp = pdfPageBitmaps[currentPdfPage]?.asAndroidBitmap()
                                    val file = ImportExportService.exportAnnotatedPage(
                                        context = context,
                                        title = "${page.title}_Page_${currentPdfPage + 1}",
                                        strokes = strokes,
                                        bgBitmap = bgBmp,
                                        template = template,
                                        exportAsPdf = false,
                                        layers = layers,
                                        stickyNotes = stickyNotes,
                                        mediaEmbeds = mediaEmbeds,
                                        pageIndex = currentPdfPage
                                    )
                                    if (file != null) {
                                        Toast.makeText(context, "Exported Page PNG to Downloads: ${file.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Page as PDF") },
                            leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                scope.launch {
                                    val bgBmp = pdfPageBitmaps[currentPdfPage]?.asAndroidBitmap()
                                    val file = ImportExportService.exportAnnotatedPage(
                                        context = context,
                                        title = "${page.title}_Page_${currentPdfPage + 1}",
                                        strokes = strokes,
                                        bgBitmap = bgBmp,
                                        template = template,
                                        exportAsPdf = true,
                                        layers = layers,
                                        stickyNotes = stickyNotes,
                                        mediaEmbeds = mediaEmbeds,
                                        pageIndex = currentPdfPage
                                    )
                                    if (file != null) {
                                        Toast.makeText(context, "Exported Page PDF to Downloads: ${file.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Document as PDF") },
                            leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                scope.launch {
                                    val totalPages = maxOf(1, pdfPageBitmaps.size, (strokes.maxOfOrNull { it.pdfPage } ?: 0) + 1)
                                    val androidBgBitmaps = pdfPageBitmaps.mapValues { it.value.asAndroidBitmap() }
                                    val file = ImportExportService.exportDocumentAsPdf(
                                        context = context,
                                        title = page.title,
                                        totalPages = totalPages,
                                        strokes = strokes,
                                        bgBitmaps = androidBgBitmaps,
                                        template = template,
                                        layers = layers,
                                        stickyNotes = stickyNotes,
                                        mediaEmbeds = mediaEmbeds
                                    )
                                    if (file != null) {
                                        Toast.makeText(context, "Exported Full PDF ($totalPages pgs) to Downloads: ${file.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Document PDF export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Note as HTML") },
                            leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                scope.launch {
                                    val file = ImportExportService.exportNoteToHtml(context, page, viewModel.repository)
                                    if (file != null) {
                                        Toast.makeText(context, "Exported HTML to Downloads: ${file.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "HTML export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Layers as PSD") },
                            leadingIcon = { Icon(Icons.Outlined.Layers, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                scope.launch {
                                    val file = ImportExportService.exportPageToPsd(context, page, viewModel.repository)
                                    if (file != null) {
                                        Toast.makeText(context, "Exported Layered PSD to Downloads: ${file.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "PSD export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Section Vault (ZIP)") },
                            leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                Toast.makeText(context, "Packaging Section Vault ZIP...", Toast.LENGTH_SHORT).show()
                                viewModel.exportSectionVaultZip(context, page.sectionId) { zipFile ->
                                    if (zipFile != null) {
                                        Toast.makeText(context, "Exported Section Vault ZIP to Downloads: ${zipFile.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Section Vault export failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (palmRejectionEnabled) "Palm Rejection: Enabled" else "Palm Rejection: Disabled") },
                            leadingIcon = { Icon(Icons.Outlined.DoNotTouch, contentDescription = null) },
                            onClick = {
                                palmRejectionEnabled = !palmRejectionEnabled
                                showOverflowMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (stylusPressureEnabled) "Stylus Pressure: Enabled" else "Stylus Pressure: Disabled") },
                            leadingIcon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
                            onClick = {
                                stylusPressureEnabled = !stylusPressureEnabled
                                showOverflowMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(if (advancedBrushesEnabled) "Advanced Brushes: Enabled" else "Advanced Brushes: Disabled")
                                    Text("Jetpack Ink (Alpha API)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingIcon = { Icon(Icons.Outlined.Brush, contentDescription = null) },
                            onClick = {
                                advancedBrushesEnabled = !advancedBrushesEnabled
                                showOverflowMenu = false
                            }
                        )
                        val showStrokePreviewsInPicker by viewModel.showStrokePreviewsInPicker.collectAsState()
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Show Pen Stroke Previews")
                                    Switch(
                                        checked = showStrokePreviewsInPicker,
                                        onCheckedChange = { viewModel.toggleShowStrokePreviewsInPicker(it) }
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                viewModel.toggleShowStrokePreviewsInPicker(!showStrokePreviewsInPicker)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Clear Canvas", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showClearCanvasWarning = true
                                showOverflowMenu = false
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Full screen Canvas surface
            AnnotationCanvas(
                modifier = Modifier.fillMaxSize(),
                strokes = strokes,
                stickyNotes = stickyNotes,
                mediaEmbeds = mediaEmbeds,
                currentTool = currentTool,
                currentColor = currentColor,
                currentWidth = currentWidth,
                template = template,
                pageTags = page.tags,
                paperColorHex = paperColorHex,
                divideIntoPages = divideIntoPages,
                backgroundImage = pdfPageBitmaps[currentPdfPage],
                layers = layers,
                activeLayerId = activeLayerId,
                pdfPageBitmaps = pdfPageBitmaps,
                activeRawBitmapMap = activeRawBitmapMap,
                pdfTotalPages = pdfTotalPages,
                isPdf = isPdf,
                pdfPageFilter = if (isPdf) currentPdfPage else 0,
                isContinuousMode = isContinuousMode,
                zoomScale = zoomScale,
                panOffset = panOffset,
                palmRejectionEnabled = palmRejectionEnabled,
                stylusPressureEnabled = stylusPressureEnabled,
                advancedBrushesEnabled = advancedBrushesEnabled,
                showMinimap = showMinimap,
                isRecordingVoice = isRecordingVoice,
                recordingElapsedMsProvider = { recordingElapsedMs },
                activeVoicePlaybackFilePath = activeVoicePlaybackFilePath,
                isPlayingVoice = isPlayingVoice,
                activeVoicePositionMsProvider = { activeVoicePositionMs },
                activeVoiceSpeed = activeVoiceSpeed,
                gpuWetBrushesEnabled = gpuWetBrushesEnabled,
                shapeAutoSnapEnabled = shapeAutoSnapEnabled,
                onZoomScaleChanged = { zoomScale = it },
                onPanOffsetChanged = { panOffset = it },
                onVisiblePageWindowChanged = { newWindow ->
                    visiblePageWindow = newWindow
                },
                onStrokesChanged = { updatedStrokes ->
                    handleStrokesChange(updatedStrokes)
                },
                onStickyNotesChanged = { updatedNotes ->
                    handleStickyNotesChange(updatedNotes)
                },
                onMediaEmbedsChanged = { updatedEmbeds ->
                    handleMediaEmbedsChange(updatedEmbeds)
                },
                onToggleVoicePlay = { filePath ->
                    voiceNoteManager.togglePlayback(filePath)
                },
                onVoiceSeekTo = { posMs ->
                    voiceNoteManager.seekTo(posMs)
                },
                onVoiceSpeedChange = { speed ->
                    voiceNoteManager.setPlaybackSpeed(speed)
                },
                onColorSampled = { sampledColor ->
                    currentColor = sampledColor
                    if (sampledColor !in customPalette) {
                        customPalette = customPalette + sampledColor
                    }
                },
                onDrawingStart = {
                    toolbarState = FloatingToolbarState.HIDDEN_DRAWING
                },
                onDrawingEnd = {
                    if (toolbarState == FloatingToolbarState.HIDDEN_DRAWING) {
                        toolbarState = FloatingToolbarState.COLLAPSED
                    }
                },
                onCanvasTap = {
                    toolbarState = FloatingToolbarState.COLLAPSED
                }
            )

            // Voice note failure banner (real recorder/playback errors — no silent fakes)
            val voiceErrorBanner = voiceRecordingError ?: voicePlaybackError
            if (voiceErrorBanner != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(0.95f)
                        .padding(top = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Text(
                            text = voiceErrorBanner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { voiceNoteManager.clearErrors() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Floating Bottom/Side Toolbar Pill (Capsule) — auto-hides when drawing
            val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

            AnimatedVisibility(
                visible = toolbarState != FloatingToolbarState.HIDDEN_DRAWING,
                enter = if (isLandscape) {
                    fadeIn() + androidx.compose.animation.slideInHorizontally { it }
                } else {
                    fadeIn() + slideInVertically { it }
                },
                exit = if (isLandscape) {
                    fadeOut() + androidx.compose.animation.slideOutHorizontally { it }
                } else {
                    fadeOut() + slideOutVertically { it }
                },
                modifier = Modifier
                    .align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter)
                    .padding(
                        bottom = if (isLandscape) 0.dp else 20.dp,
                        end = if (isLandscape) 20.dp else 0.dp
                    )
            ) {
                FloatingBottomToolbarPill(
                    currentTool = currentTool,
                    lastDrawingTool = lastDrawingTool,
                    currentColor = currentColor,
                    currentWidth = currentWidth,
                    toolbarState = toolbarState,
                    onToolClick = {
                        toolbarState = if (toolbarState == FloatingToolbarState.TOOL_PICKER) FloatingToolbarState.COLLAPSED else FloatingToolbarState.TOOL_PICKER
                    },
                    onTogglePan = {
                        currentTool = if (currentTool == StrokeTool.PAN) lastDrawingTool else StrokeTool.PAN
                    },
                    onColorClick = {
                        toolbarState = if (toolbarState == FloatingToolbarState.COLOR_PICKER) FloatingToolbarState.COLLAPSED else FloatingToolbarState.COLOR_PICKER
                    },
                    onWidthClick = {
                        toolbarState = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) FloatingToolbarState.COLLAPSED else FloatingToolbarState.WIDTH_PICKER
                    },
                    onSettingsClick = {
                        toolbarState = if (toolbarState == FloatingToolbarState.SETTINGS_MENU) FloatingToolbarState.COLLAPSED else FloatingToolbarState.SETTINGS_MENU
                    },
                    onUndo = { handleUndo() },
                    onRedo = { handleRedo() },
                    isLandscape = isLandscape
                )
            }
        }
    }

    val showStrokePreviewsInPicker by viewModel.showStrokePreviewsInPicker.collectAsState()

    // Modal Bottom Sheets for popover pickers
    when (toolbarState) {
        FloatingToolbarState.TOOL_PICKER -> {
            ToolPickerBottomSheet(
                currentTool = currentTool,
                showStrokePreviews = showStrokePreviewsInPicker,
                onToolSelect = { tool ->
                    currentTool = tool
                    activePresetId = null
                },
                onDismiss = { toolbarState = FloatingToolbarState.COLLAPSED }
            )
        }
        FloatingToolbarState.COLOR_PICKER -> {
            ColorPickerBottomSheet(
                currentColor = currentColor,
                palette = customPalette,
                advancedBrushesEnabled = advancedBrushesEnabled,
                savedSwatches = paletteItems.filter { it.type == "SWATCH" },
                onColorSelect = { color ->
                    currentColor = color
                    activePresetId = null
                    activeCustomPresetId = null
                },
                onSaveSwatch = { color ->
                    viewModel.insertPaletteItem(
                        PaletteItemEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            type = "SWATCH",
                            name = "Swatch",
                            colorInt = color.toArgb(),
                            toolName = null,
                            strokeWidth = null
                        )
                    )
                },
                onDeleteSwatch = { id ->
                    viewModel.deletePaletteItem(id)
                },
                onDismiss = { toolbarState = FloatingToolbarState.COLLAPSED }
            )
        }
        FloatingToolbarState.WIDTH_PICKER -> {
            WidthPickerBottomSheet(
                currentWidth = currentWidth,
                currentColor = currentColor,
                penPresets = penPresets,
                activePresetId = activePresetId,
                advancedBrushesEnabled = advancedBrushesEnabled,
                customPresets = paletteItems.filter { it.type == "PRESET" },
                currentTool = currentTool,
                onWidthSelect = { w ->
                    currentWidth = w
                    activePresetId = null
                    activeCustomPresetId = null
                },
                onPresetSelect = { preset ->
                    activePresetId = preset.id
                    activeCustomPresetId = null
                    currentTool = preset.tool
                    currentColor = preset.color
                    currentWidth = preset.width
                },
                onCustomPresetSelect = { customPreset ->
                    activeCustomPresetId = customPreset.id
                    activePresetId = null
                    customPreset.toolName?.let {
                        try {
                            currentTool = StrokeTool.valueOf(it)
                        } catch (e: Exception) {}
                    }
                    currentColor = Color(customPreset.colorInt)
                    customPreset.strokeWidth?.let {
                        currentWidth = it
                    }
                },
                onSavePreset = { name ->
                    viewModel.insertPaletteItem(
                        PaletteItemEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            type = "PRESET",
                            name = name,
                            colorInt = currentColor.toArgb(),
                            toolName = currentTool.name,
                            strokeWidth = currentWidth
                        )
                    )
                },
                onDeletePreset = { id ->
                    if (activeCustomPresetId == id) {
                        activeCustomPresetId = null
                    }
                    viewModel.deletePaletteItem(id)
                },
                onDismiss = { toolbarState = FloatingToolbarState.COLLAPSED }
            )
        }
        FloatingToolbarState.SETTINGS_MENU -> {
            CanvasSettingsBottomSheet(
                isContinuousMode = isContinuousMode,
                divideIntoPages = divideIntoPages,
                template = template,
                paperColorHex = paperColorHex,
                showMinimap = showMinimap,
                zoomScale = zoomScale,
                panOffset = panOffset,
                isPdf = isPdf,
                gpuWetBrushesEnabled = gpuWetBrushesEnabled,
                onGpuWetBrushesToggle = { enabled ->
                    gpuWetBrushesEnabled = enabled
                    viewModel.settings.gpuWetBrushesEnabled = enabled
                },
                shapeAutoSnapEnabled = shapeAutoSnapEnabled,
                onShapeAutoSnapToggle = { enabled ->
                    shapeAutoSnapEnabled = enabled
                    viewModel.settings.shapeAutoSnapEnabled = enabled
                },
                onContinuousModeToggle = { isContinuousMode = !isContinuousMode },
                onDividePagesToggle = { divideIntoPages = !divideIntoPages },
                onTemplateSelect = { selectedTemplate ->
                    template = selectedTemplate
                    viewModel.updatePageTemplate(page.id, selectedTemplate)
                },
                onPaperColorSelect = { selectedHex ->
                    paperColorHex = selectedHex
                },
                onUploadCustomBg = { bgImagePicker.launch("image/*") },
                onMinimapToggle = { showMinimap = !showMinimap },
                onResetZoomPan = {
                    zoomScale = 1f
                    panOffset = Offset.Zero
                },
                onInsertPageBefore = { insertPage(before = true) },
                onInsertPageAfter = { insertPage(before = false) },
                onDismiss = { toolbarState = FloatingToolbarState.COLLAPSED }
            )
        }
        else -> {}
    }

    if (showRenameDialog) {
        PromptNameDialog(
            title = "Rename Page",
            initialValue = page.title,
            submitLabel = "Rename",
            onDismiss = { showRenameDialog = false },
            onSubmit = { newTitle ->
                viewModel.renamePage(page.id, newTitle)
                showRenameDialog = false
            }
        )
    }

    if (showClearCanvasWarning) {
        AlertDialog(
            onDismissRequest = { showClearCanvasWarning = false },
            title = { Text("Clear Canvas") },
            text = { Text("Are you sure you want to clear all ink strokes from the canvas? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        strokes = emptyList()
                        handleStrokesChange(emptyList())
                        showClearCanvasWarning = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCanvasWarning = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBacklinks) {
        BacklinksInspectorBottomSheet(
            activePage = page,
            viewModel = viewModel,
            onOpenPage = { targetPage ->
                showBacklinks = false
                onOpenPage(targetPage)
            },
            onDismiss = { showBacklinks = false }
        )
    }

    if (showLayersSheet) {
        LayersPanelBottomSheet(
            layers = layers,
            activeLayerId = activeLayerId,
            onActiveLayerSelect = { id -> activeLayerId = id },
            onAddLayer = { onAddLayer() },
            onUpdateLayer = { onUpdateLayer(it) },
            onDeleteLayer = { onDeleteLayer(it) },
            onDuplicateLayer = { onDuplicateLayer(it) },
            onMergeDown = { onMergeDown(it) },
            onMoveUp = { onMoveUp(it) },
            onMoveDown = { onMoveDown(it) },
            onDismiss = { showLayersSheet = false }
        )
    }
}

/**
 * Floating Bottom Toolbar Pill capsule
 */
@Composable
private fun FloatingBottomToolbarPill(
    currentTool: StrokeTool,
    lastDrawingTool: StrokeTool,
    currentColor: Color,
    currentWidth: Float,
    toolbarState: FloatingToolbarState,
    onToolClick: () -> Unit,
    onTogglePan: () -> Unit,
    onColorClick: () -> Unit,
    onWidthClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    isLandscape: Boolean = false
) {
    val displayTool = if (currentTool == StrokeTool.PAN || currentTool == StrokeTool.SELECT) {
        lastDrawingTool
    } else {
        currentTool
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = if (isLandscape) {
            Modifier.width(56.dp).wrapContentHeight()
        } else {
            Modifier.height(56.dp)
        }
    ) {
        if (isLandscape) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Tool selector button
                Surface(
                    onClick = onToolClick,
                    shape = RoundedCornerShape(20.dp),
                    color = if (toolbarState == FloatingToolbarState.TOOL_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getToolIcon(displayTool),
                            contentDescription = displayTool.label,
                            tint = if (toolbarState == FloatingToolbarState.TOOL_PICKER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Scroll / Pan Canvas Toggle Button
                val isPanActive = currentTool == StrokeTool.PAN || currentTool == StrokeTool.SELECT
                Surface(
                    onClick = onTogglePan,
                    shape = RoundedCornerShape(20.dp),
                    color = if (isPanActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.PanTool,
                            contentDescription = "Scroll / Pan Canvas",
                            tint = if (isPanActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Color swatch button
                Surface(
                    onClick = onColorClick,
                    shape = CircleShape,
                    color = if (toolbarState == FloatingToolbarState.COLOR_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(currentColor)
                                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                    }
                }

                // Width badge button
                Surface(
                    onClick = onWidthClick,
                    shape = RoundedCornerShape(20.dp),
                    color = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                ) {
                    Box(
                        modifier = Modifier.padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.LineWeight,
                            contentDescription = "Stroke Width",
                            tint = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier
                        .width(24.dp)
                        .padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Canvas Settings button
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = "Canvas & Paper Settings",
                        tint = if (toolbarState == FloatingToolbarState.SETTINGS_MENU) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Undo / Redo
                IconButton(onClick = onUndo, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Undo,
                        contentDescription = "Undo",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRedo, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Redo,
                        contentDescription = "Redo",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Tool selector button
                Surface(
                    onClick = onToolClick,
                    shape = RoundedCornerShape(20.dp),
                    color = if (toolbarState == FloatingToolbarState.TOOL_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = getToolIcon(displayTool),
                            contentDescription = displayTool.label,
                            tint = if (toolbarState == FloatingToolbarState.TOOL_PICKER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = displayTool.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (toolbarState == FloatingToolbarState.TOOL_PICKER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Scroll / Pan Canvas Toggle Button
                val isPanActive = currentTool == StrokeTool.PAN || currentTool == StrokeTool.SELECT
                Surface(
                    onClick = onTogglePan,
                    shape = RoundedCornerShape(20.dp),
                    color = if (isPanActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.PanTool,
                            contentDescription = "Scroll / Pan Canvas",
                            tint = if (isPanActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Scroll",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPanActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Color swatch button
                Surface(
                    onClick = onColorClick,
                    shape = CircleShape,
                    color = if (toolbarState == FloatingToolbarState.COLOR_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(currentColor)
                                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                    }
                }

                // Width badge button
                Surface(
                    onClick = onWidthClick,
                    shape = RoundedCornerShape(20.dp),
                    color = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.LineWeight,
                            contentDescription = "Stroke Width",
                            tint = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "${currentWidth.toInt()}pt",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                VerticalDivider(
                    modifier = Modifier
                        .height(24.dp)
                        .padding(horizontal = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Canvas Settings button
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = "Canvas & Paper Settings",
                        tint = if (toolbarState == FloatingToolbarState.SETTINGS_MENU) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Undo / Redo
                IconButton(onClick = onUndo, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Undo,
                        contentDescription = "Undo",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRedo, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Redo,
                        contentDescription = "Redo",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Tool Picker Modal Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolPickerBottomSheet(
    currentTool: StrokeTool,
    showStrokePreviews: Boolean = false,
    onToolSelect: (StrokeTool) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Select Tool",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))

            val wetToolsList = listOf(StrokeTool.WATERCOLOR, StrokeTool.OIL_PAINT, StrokeTool.SMUDGE, StrokeTool.SPLATTER)
            val standardTools = StrokeTool.entries.filter { it !in wetToolsList }
            val gpuTools = wetToolsList
            val context = androidx.compose.ui.platform.LocalContext.current
            var hasShownShaderWarning by remember { mutableStateOf(false) }

            Text(
                text = "STANDARD BRUSHES & TOOLS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            ) {
                items(standardTools) { tool ->
                    val selected = tool == currentTool
                    
                    Surface(
                        onClick = {
                            onToolSelect(tool)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = getToolIcon(tool),
                                contentDescription = tool.label,
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tool.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (tool.isFreehandTool && showStrokePreviews) {
                                Spacer(modifier = Modifier.height(4.dp))
                                com.authorss81.noteflow.ui.components.PenNibVisualPreview(
                                    tool = tool,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                    width = when (tool) {
                                        StrokeTool.DOTTED, StrokeTool.FINELINER -> 3f
                                        StrokeTool.HIGHLIGHTER, StrokeTool.CHISEL_MARKER -> 10f
                                        StrokeTool.NEON, StrokeTool.LASER -> 8f
                                        else -> 5f
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "GPU WET BRUSHES (ANDROID 13+)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                gpuTools.forEach { tool ->
                    val selected = tool == currentTool
                    val isUnsupported = !com.authorss81.noteflow.ui.components.ShaderCapabilityHelper.isAgslSupported

                    Surface(
                        onClick = {
                            if (isUnsupported && !hasShownShaderWarning) {
                                hasShownShaderWarning = true
                                android.widget.Toast.makeText(
                                    context,
                                    "Real-time wet blending requires Android 13+ — using soft-blend watercolor fallback on this device.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            onToolSelect(tool)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary) else null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = getToolIcon(tool),
                                contentDescription = tool.label,
                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else if (isUnsupported) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tool.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else if (isUnsupported) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Color Picker Modal Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerBottomSheet(
    currentColor: Color,
    palette: List<Color>,
    advancedBrushesEnabled: Boolean,
    savedSwatches: List<PaletteItemEntity>,
    onColorSelect: (Color) -> Unit,
    onSaveSwatch: (Color) -> Unit,
    onDeleteSwatch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (advancedBrushesEnabled) "Advanced Stroke Color" else "Stroke Color",
                    style = MaterialTheme.typography.titleLarge
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(currentColor)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (advancedBrushesEnabled) {
                // Initialize HSV from currentColor using safe android.graphics API
                val initialHsv = remember(currentColor) {
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(currentColor.toArgb(), hsv)
                    hsv
                }

                var h by remember(currentColor) { mutableFloatStateOf(initialHsv[0]) }
                var s by remember(currentColor) { mutableFloatStateOf(initialHsv[1]) }
                var v by remember(currentColor) { mutableFloatStateOf(initialHsv[2]) }

                val derivedColor = remember(h, s, v) {
                    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
                    Color(argb)
                }

                Text(
                    text = "HSV Color Customization",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Hue Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "H", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(16.dp))
                    Slider(
                        value = h,
                        onValueChange = {
                            h = it
                            onColorSelect(derivedColor)
                        },
                        valueRange = 0f..360f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = "${h.toInt()}°", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
                }

                // Saturation Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "S", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(16.dp))
                    Slider(
                        value = s,
                        onValueChange = {
                            s = it
                            onColorSelect(derivedColor)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = "${(s * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
                }

                // Value/Brightness Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "V", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(16.dp))
                    Slider(
                        value = v,
                        onValueChange = {
                            v = it
                            onColorSelect(derivedColor)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = "${(v * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onSaveSwatch(derivedColor) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save to Custom Swatches")
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (savedSwatches.isNotEmpty()) {
                    Text(
                        text = "Saved Swatches",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                    ) {
                        items(savedSwatches) { swatch ->
                            val swatchColor = Color(swatch.colorInt)
                            val isSelected = currentColor.toArgb() == swatch.colorInt
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        onColorSelect(swatchColor)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                                        .clickable { onDeleteSwatch(swatch.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("×", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = "Selected",
                                        tint = if (swatchColor.luminance() > 0.5f) Color.Black else Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Classic Simple Color Palette Row
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                ) {
                    items(palette) { color ->
                        val selected = currentColor == color
                        val isRainbow = color == Color(0xFFFF00AA)
                        val boxModifier = if (isRainbow) {
                            Modifier
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta)))
                        } else {
                            Modifier
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(color)
                        }
                        Box(
                            modifier = boxModifier
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    onColorSelect(color)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = "Selected",
                                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Stroke Width & Pen Presets Modal Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidthPickerBottomSheet(
    currentWidth: Float,
    currentColor: Color,
    penPresets: List<PenPreset>,
    activePresetId: Int?,
    advancedBrushesEnabled: Boolean,
    customPresets: List<PaletteItemEntity>,
    currentTool: StrokeTool,
    onWidthSelect: (Float) -> Unit,
    onPresetSelect: (PenPreset) -> Unit,
    onCustomPresetSelect: (PaletteItemEntity) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newPresetName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Stroke Width & Presets",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (preset in penPresets) {
                    val isSelected = activePresetId == preset.id
                    com.authorss81.noteflow.ui.components.PenPresetVisualCard(
                        preset = preset,
                        isSelected = isSelected,
                        onClick = {
                            onPresetSelect(preset)
                            onDismiss()
                        }
                    )
                }
            }

            if (advancedBrushesEnabled) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Custom Saved Presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (customPresets.isEmpty()) {
                    Text(
                        text = "No custom presets saved yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (preset in customPresets) {
                            val isSelected = false
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    onCustomPresetSelect(preset)
                                    onDismiss()
                                },
                                label = {
                                    Text(
                                        "${preset.name} (${preset.toolName ?: "Pen"})",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(Color(preset.colorInt))
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { onDeletePreset(preset.id) },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Text("×", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Save Custom Preset Form
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("Preset Name") },
                        placeholder = { Text("e.g. Blue Marker") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (newPresetName.isNotBlank()) {
                                onSavePreset(newPresetName.trim())
                                newPresetName = ""
                            }
                        },
                        enabled = newPresetName.isNotBlank()
                    ) {
                        Text("Save")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Thickness: ${currentWidth.toInt()}pt",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stroke visual live preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(currentWidth.coerceAtLeast(1f).dp)
                ) {
                    drawRoundRect(
                        color = currentColor,
                        cornerRadius = CornerRadius(currentWidth, currentWidth)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Slider(
                value = currentWidth,
                onValueChange = onWidthSelect,
                valueRange = 1f..36f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Canvas & Paper Settings Modal Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CanvasSettingsBottomSheet(
    isContinuousMode: Boolean,
    divideIntoPages: Boolean,
    template: String,
    paperColorHex: String,
    showMinimap: Boolean,
    zoomScale: Float,
    panOffset: Offset,
    isPdf: Boolean,
    gpuWetBrushesEnabled: Boolean = true,
    onGpuWetBrushesToggle: (Boolean) -> Unit = {},
    shapeAutoSnapEnabled: Boolean = true,
    onShapeAutoSnapToggle: (Boolean) -> Unit = {},
    onContinuousModeToggle: () -> Unit,
    onDividePagesToggle: () -> Unit,
    onTemplateSelect: (String) -> Unit,
    onPaperColorSelect: (String) -> Unit,
    onUploadCustomBg: () -> Unit = {},
    onMinimapToggle: () -> Unit,
    onResetZoomPan: () -> Unit,
    onInsertPageBefore: () -> Unit,
    onInsertPageAfter: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Canvas & Paper Options",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Canvas Mode (Infinite vs Single Page)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Infinite Canvas Mode", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (isContinuousMode) "Continuous vertical page flow" else "Single page view",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isContinuousMode,
                            onCheckedChange = { onContinuousModeToggle() }
                        )
                    }

                    if (isContinuousMode) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Page Division Gaps", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (divideIntoPages) "Visual gaps between pages" else "Seamless canvas sheet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = divideIntoPages,
                                onCheckedChange = { onDividePagesToggle() }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Paper Template selector
            Text(
                text = "Paper Template",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            val templates = listOf(
                "blank" to "Blank",
                "lined" to "Lined",
                "grid" to "Grid",
                "dots" to "Dot Grid",
                "cornell" to "Cornell",
                "meeting" to "Meeting",
                "todo" to "To-Do Grid"
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((key, label) in templates.take(4)) {
                        val selected = template == key
                        FilterChip(
                            selected = selected,
                            onClick = { onTemplateSelect(key) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((key, label) in templates.drop(4)) {
                        val selected = template == key
                        FilterChip(
                            selected = selected,
                            onClick = { onTemplateSelect(key) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onUploadCustomBg,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Upload Custom Paper Background Image")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Paper Background Color selector
            Text(
                text = "Paper Background Color",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            val colorOptions = listOf(
                "White" to "#FFFFFF",
                "Cream" to "#FFFBEB",
                "Sepia" to "#FDF6E2",
                "Mint" to "#F0FDF4",
                "Rose" to "#FFF1F2",
                "Slate" to "#1E293B",
                "Midnight" to "#0F172A",
                "Black" to "#000000"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for ((name, hex) in colorOptions) {
                    val selected = paperColorHex.equals(hex, ignoreCase = true)
                    val optionColor = try {
                        Color(android.graphics.Color.parseColor(hex))
                    } catch (e: Exception) {
                        Color.White
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onPaperColorSelect(hex) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(optionColor)
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isPdf) {
                Text(
                    text = "Page Management",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            onInsertPageBefore()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Page Before", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = {
                            onInsertPageAfter()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Page After", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Minimap toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Canvas Minimap", style = MaterialTheme.typography.titleMedium)
                }
                Switch(
                    checked = showMinimap,
                    onCheckedChange = { onMinimapToggle() }
                )
            }

            // GPU Wet Brushes Toggle (conditional on AGSL availability)
            if (com.authorss81.noteflow.ui.components.ShaderCapabilityHelper.isAgslSupported) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Brush, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("GPU Wet Brushes", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "AGSL wet-mixing oil & watercolors",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = gpuWetBrushesEnabled,
                        onCheckedChange = onGpuWetBrushesToggle
                    )
                }
            }

            // Shape Auto-Snap Toggle (Line/Rectangle/Ellipse/Arrow straightening)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Gesture, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Shape Auto-Snap", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Straighten line/rect/ellipse/arrow gestures",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = shapeAutoSnapEnabled,
                    onCheckedChange = onShapeAutoSnapToggle
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            if (zoomScale != 1f || panOffset != Offset.Zero) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onResetZoomPan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CenterFocusWeak, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Zoom & Pan (${(zoomScale * 100).toInt()}%)")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun getToolIcon(tool: StrokeTool): ImageVector {
    return when (tool) {
        StrokeTool.PEN -> Icons.Outlined.Edit
        StrokeTool.FOUNTAIN_PEN -> Icons.Outlined.HistoryEdu
        StrokeTool.PENCIL -> Icons.Outlined.Create
        StrokeTool.AIRBRUSH -> Icons.Outlined.Grain
        StrokeTool.MARKER -> Icons.Outlined.BorderColor
        StrokeTool.HIGHLIGHTER -> Icons.Outlined.Highlight
        StrokeTool.CALLIGRAPHIC -> Icons.Outlined.Brush
        StrokeTool.DOTTED -> Icons.Outlined.MoreHoriz
        StrokeTool.NEON -> Icons.Outlined.LightMode
        StrokeTool.FINELINER -> Icons.Outlined.EditNote
        StrokeTool.CHISEL_MARKER -> Icons.Outlined.HorizontalRule
        StrokeTool.LASER -> Icons.Outlined.CenterFocusWeak
        StrokeTool.ERASER -> Icons.Outlined.AutoFixNormal
        StrokeTool.TEXT -> Icons.Outlined.TextFields
        StrokeTool.RECTANGLE -> Icons.Outlined.Rectangle
        StrokeTool.LINE -> Icons.Outlined.HorizontalRule
        StrokeTool.ARROW -> Icons.Outlined.TrendingFlat
        StrokeTool.ELLIPSE -> Icons.Outlined.RadioButtonUnchecked
        StrokeTool.TRIANGLE -> Icons.Outlined.ChangeHistory
        StrokeTool.STAR -> Icons.Outlined.StarBorder
        StrokeTool.PENTAGON -> Icons.Outlined.Category
        StrokeTool.HEXAGON -> Icons.Outlined.Hexagon
        StrokeTool.SELECT -> Icons.Outlined.PanTool
        StrokeTool.PAN -> Icons.Outlined.PanTool
        StrokeTool.EYEDROPPER -> Icons.Outlined.Colorize
        StrokeTool.WATERCOLOR -> Icons.Outlined.WaterDrop
        StrokeTool.OIL_PAINT -> Icons.Outlined.FormatPaint
        StrokeTool.SMUDGE -> Icons.Outlined.TouchApp
        StrokeTool.SPLATTER -> Icons.Outlined.Grain
    }
}

// PDF & Image Helper Utilities
private fun renderPdfPageToRawBitmap(filePath: String, pageIndex: Int, targetWidth: Int = 1080, context: android.content.Context? = null): android.graphics.Bitmap? {
    return try {
        val file = File(filePath)
        if (!file.exists()) return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
            renderer.close()
            pfd.close()
            return null
        }
        val pdfPage = renderer.openPage(pageIndex)

        // 32.8: Bound PDF texture to max 1.5x of the device's screen width to prevent OOM
        val displayWidth = context?.resources?.displayMetrics?.widthPixels ?: 1080
        val maxAllowedWidth = (displayWidth * 1.5f).toInt().coerceAtMost(2048)
        val width = targetWidth.coerceAtMost(maxAllowedWidth)

        val height = (width * (pdfPage.height.toFloat() / pdfPage.width.toFloat())).toInt()
        val bitmap = com.authorss81.noteflow.utils.BitmapPool.acquire(width, height)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        pdfPage.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        pdfPage.close()
        renderer.close()
        pfd.close()
        bitmap
    } catch (e: Exception) {
        null
    }
}

private fun decodeBoundedBitmap(filePath: String, targetWidth: Int = 1080): android.graphics.Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)
        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        var inSampleSize = 1
        if (srcWidth > targetWidth) {
            val halfWidth = srcWidth / 2
            while (halfWidth / inSampleSize >= targetWidth) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(filePath, decodeOptions)
    } catch (e: Exception) {
        null
    }
}

private fun getPdfPageCount(filePath: String): Int {
    return try {
        val file = File(filePath)
        if (!file.exists()) return 0
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        val count = renderer.pageCount
        renderer.close()
        pfd.close()
        count
    } catch (e: Exception) {
        0
    }
}

/**
 * Layers Panel Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersPanelBottomSheet(
    layers: List<LayerEntity>,
    activeLayerId: String?,
    onActiveLayerSelect: (String) -> Unit,
    onAddLayer: () -> Unit,
    onUpdateLayer: (LayerEntity) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onDuplicateLayer: (LayerEntity) -> Unit,
    onMergeDown: (LayerEntity) -> Unit,
    onMoveUp: (LayerEntity) -> Unit,
    onMoveDown: (LayerEntity) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Layers Manager",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Button(
                    onClick = onAddLayer,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Layer")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val sortedLayers = remember(layers) { layers.sortedByDescending { it.zOrder } }

            if (sortedLayers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No layers available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    items(sortedLayers.size) { index ->
                        val layer = sortedLayers[index]
                        val isActive = layer.id == activeLayerId

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onActiveLayerSelect(layer.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isActive) 2.dp else 1.dp,
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Outlined.Layers,
                                            contentDescription = null,
                                            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Text(
                                        text = layer.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )

                                    IconButton(
                                        onClick = { onUpdateLayer(layer.copy(visible = !layer.visible)) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                            contentDescription = "Toggle Visibility",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (layer.visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    IconButton(
                                        onClick = { onUpdateLayer(layer.copy(locked = !layer.locked)) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (layer.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                            contentDescription = "Toggle Lock",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (layer.locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Opacity: ${(layer.opacity * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.width(80.dp)
                                    )
                                    Slider(
                                        value = layer.opacity,
                                        onValueChange = { onUpdateLayer(layer.copy(opacity = it)) },
                                        modifier = Modifier.weight(1f).height(24.dp)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    var showBlendMenu by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(
                                            onClick = { showBlendMenu = true },
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(
                                                text = "Blend: ${layer.blendMode}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showBlendMenu,
                                            onDismissRequest = { showBlendMenu = false }
                                        ) {
                                            val blendModes = listOf("NORMAL", "MULTIPLY", "SCREEN", "OVERLAY", "DARKEN", "LIGHTEN", "COLOR_DODGE", "COLOR_BURN", "HARD_LIGHT", "SOFT_LIGHT", "DIFFERENCE", "EXCLUSION")
                                            blendModes.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode) },
                                                    onClick = {
                                                        onUpdateLayer(layer.copy(blendMode = mode))
                                                        showBlendMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { onMoveUp(layer) },
                                            enabled = index > 0,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Outlined.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(14.dp))
                                        }

                                        IconButton(
                                            onClick = { onMoveDown(layer) },
                                            enabled = index < sortedLayers.size - 1,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Outlined.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(14.dp))
                                        }

                                        IconButton(
                                            onClick = { onDuplicateLayer(layer) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(14.dp))
                                        }

                                        val isBottomLayer = index == sortedLayers.size - 1
                                        IconButton(
                                            onClick = { onMergeDown(layer) },
                                            enabled = !isBottomLayer,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Outlined.MergeType, contentDescription = "Merge Down", modifier = Modifier.size(14.dp))
                                        }

                                        val isOnlyLayer = sortedLayers.size == 1
                                        IconButton(
                                            onClick = { onDeleteLayer(layer.id) },
                                            enabled = !isOnlyLayer,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = if (isOnlyLayer) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
