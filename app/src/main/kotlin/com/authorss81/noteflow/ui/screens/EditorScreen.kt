package com.authorss81.noteflow.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.*
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.ShapeFromInkOutcome
import com.authorss81.noteflow.plugins.export.mimeType
import com.authorss81.noteflow.plugins.inktos.InkToShapePlugin
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.ExportDestinationPolicy
import com.authorss81.noteflow.services.PsdExportPolicy
import com.authorss81.noteflow.services.DockPosturePolicy
import com.authorss81.noteflow.services.DockSnapMath
import com.authorss81.noteflow.services.FloatingWidgetDragPolicy
import com.authorss81.noteflow.services.MinimapGeometryPolicy
import com.authorss81.noteflow.services.CanvasPageBudgetPolicy
import com.authorss81.noteflow.services.HarmonyScheme
import com.authorss81.noteflow.services.LayerRenderBudgetPolicy
import com.authorss81.noteflow.services.PaletteCatalog
import com.authorss81.noteflow.services.PaletteMath
import com.authorss81.noteflow.services.PressureCurve
import com.authorss81.noteflow.services.SymmetryMode
import com.authorss81.noteflow.services.VoiceNoteManager
import com.authorss81.noteflow.services.ReferenceImagePolicy
import com.authorss81.noteflow.services.InlineImagePathPolicy
import com.authorss81.noteflow.ui.components.AnnotationCanvas
import com.authorss81.noteflow.ui.components.decodeBoundedImage
import com.authorss81.noteflow.ui.components.rememberSaFExporter
import com.authorss81.noteflow.ui.components.SaFExportResult
import com.authorss81.noteflow.ui.components.BacklinksInspectorBottomSheet
import com.authorss81.noteflow.ui.components.OcrResultDialog
import com.authorss81.noteflow.ui.components.PromptNameDialog
import com.authorss81.noteflow.ui.components.overflowMenuScrollModifier
import com.authorss81.noteflow.ui.components.overflowMenuScrollState
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Toolbar State Management enum for floating capsule & popover bottom sheets.
 */
enum class FloatingToolbarState {
    COLLAPSED,      // Floating bottom pill capsule at rest
    TOOL_PICKER,    // Expanded popover/bottom-sheet with tool suite
    COLOR_PICKER,   // Expanded popover/bottom-sheet with color swatches
    WIDTH_PICKER,   // Expanded popover/bottom-sheet with stroke width slider & presets
    SETTINGS_MENU,  // Expanded popover/bottom-sheet with canvas options (Infinite, Division, Grid, BG color, Zoom)
    STICKER_PICKER, // Phase 13: offline emoji sticker gallery (STICKER tool active)
    BRUSH_PRESETS,  // Phase 13: ready-made wet-brush preset gallery (BrushPresetPack)
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
    var currentColor by remember { mutableStateOf(Color(viewModel.settings.brushColorArgb)) }
    var currentWidth by remember { mutableFloatStateOf(4f) }
    // Phase 27 + 122: multi-color brush modes (RAINBOW / GRADIENT / SHIMMER). The
    // mode + seed + optional gradient end color persist ON each stroke and the
    // per-point color is re-derived at render time. The seed is refreshed per
    // stroke at draw start (onDrawingStart) so every multi-color stroke begins at
    // a different hue. Phase 122: the current MODE also persists across sessions
    // (SettingsManager.brushColorModeKey) and is restored here on editor open.
    var currentColorMode by remember {
        mutableStateOf(
            com.authorss81.noteflow.services.ColorModePersistencePolicy
                .modeFromPref(viewModel.settings.brushColorModeKey)
        )
    }
    var currentColorSeed by remember { mutableIntStateOf(0) }
    var currentGradientToColor by remember { mutableStateOf(Color(viewModel.settings.brushGradientToArgb)) }

    // Phase 122 (review fix): ONE shared handler pair for the colour-MODE selection so
    // the colour picker and the width/quick picker can never drift apart. Each handler
    // persists the chosen mode AND the colour parameters it depends on (base colour +
    // gradient end) via SettingsManager — a reopened GRADIENT/SHIMMER session therefore
    // restores its real colours, not the default navy.
    val handleColorModeChange: (StrokeColorMode, Color, Color?) -> Unit = { mode, baseColor, gradientTo ->
        currentColorMode = mode
        viewModel.settings.brushColorModeKey = mode.persistenceKey
        viewModel.settings.brushColorArgb = baseColor.toArgb()
        when (mode) {
            StrokeColorMode.SOLID -> currentColor = baseColor
            StrokeColorMode.RAINBOW, StrokeColorMode.SHIMMER -> {
                currentColor = baseColor
                currentColorSeed = (Math.random() * 360).toInt()
            }
            StrokeColorMode.GRADIENT -> {
                currentColor = baseColor
                currentGradientToColor = gradientTo ?: baseColor
                viewModel.settings.brushGradientToArgb = currentGradientToColor.toArgb()
            }
        }
    }
    val handleGradientToColorSelect: (Color) -> Unit = { gradientTo ->
        currentGradientToColor = gradientTo
        currentColorMode = StrokeColorMode.GRADIENT
        viewModel.settings.brushColorModeKey = StrokeColorMode.GRADIENT.persistenceKey
        viewModel.settings.brushGradientToArgb = gradientTo.toArgb()
    }
    var template by remember { mutableStateOf(page.template ?: "blank") }
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var stickyNotes by remember { mutableStateOf<List<CanvasStickyNote>>(emptyList()) }
    var mediaEmbeds by remember { mutableStateOf<List<CanvasMediaEmbed>>(emptyList()) }

    // Phase 178: per-page reference-image underlay (max one per page, delivered
    // separately from the draggable embed set). referenceImage holds the stored
    // embed (relative path + geometry + field-encrypted opacity config);
    // referenceImageBitmap is the B1-AUTH-? confined decode resolved through
    // InlineImagePathPolicy against the app-private imports dir; null when the
    // path was unreadable or policy-blocked (render nothing — never crash).
    var referenceImage by remember { mutableStateOf<CanvasMediaEmbed?>(null) }
    var referenceImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var referenceImageControlsVisible by remember { mutableStateOf(false) }

    // Phase 13: the sticker the STICKER tool currently places (id from
    // StickerCatalog; "sparkles" default), persisted in memory for the session.
    var selectedStickerId by remember { mutableStateOf<String?>(com.authorss81.noteflow.services.StickerCatalog.all().firstOrNull()?.id) }
    // Phase 13: brush preset selected in Settings → Ready-made Presets (persisted
    // to SharedPreferences via SettingsManager so it survives restarts).
    var activeBrushPresetId by remember { mutableStateOf(viewModel.settings.activeBrushPresetId) }
    // Phase 155: two-finger undo/redo + long-press quick-color ring on the canvas.
    // Both OFF by default; toggled in Canvas & Paper Options and persisted so a
    // crash/rotation keeps the user's choice.
    var twoFingerGesturesEnabled by remember { mutableStateOf(viewModel.settings.twoFingerUndoRedoEnabled) }
    var quickColorRingEnabled by remember { mutableStateOf(viewModel.settings.quickColorRingEnabled) }
    // Phase 155: user's imported .inkbrush presets ("My presets"), persisted as a
    // JSON list in shared prefs (NO DB schema change).
    var importedBrushPresets by remember { mutableStateOf(com.authorss81.noteflow.services.BrushPresetFileCodec.decodeList(viewModel.settings.importedBrushPresetsJson)) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showBacklinks by remember { mutableStateOf(false) }
    var showClearCanvasWarning by remember { mutableStateOf(false) }

    // Phase 12: OCR target — set when the user taps "Extract text (OCR)" on an
    // attached photo; drives the OcrResultDialog rendered at the Scaffold level.
    var ocrTargetPath by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    // B1-PLAT-3 (phase-59): every export goes to a user-picked SAF destination —
    // never straight into public Downloads.
    val exporter = rememberSaFExporter(scope)
    // R2-B1P-03 (phase-141): the Export Engine share launches through an explicit
    // chooser and the plaintext staging file is deleted once the chooser dismisses
    // (share delivered OR dismissed) — transfer-then-delete, as in SaFExporter.
    // rememberSaveable so a rotation/recreation while the chooser is open keeps the
    // pending file reference alive and the launcher callback still deletes it
    // (phase-141 review fix): the FILE isn't directly Bundle-saveable, so we keep
    // the absolute path string and rebuild the File on the callback side.
    var pendingExportFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val exportShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        pendingExportFilePath?.let { path -> runCatching { File(path).delete() } }
        pendingExportFilePath = null
    }
    // Phase 12: OCR is offered on attached photos only while an OCR plugin is
    // actually enabled and device-available (never a dead button).
    val ocrAvailable = viewModel.pluginRegistry
        .availablePlugins(PluginCapability.OCR, context)
        .isNotEmpty()
    // Phase 25: Ink → Shape is available only while the plugin is enabled +
    // device-available. When disabled the "Convert to Shape" action is grayed
    // out with an "enable it in Plugins" hint — never a silently dead button.
    val inkToShapeAvailable = viewModel.pluginRegistry
        .availablePlugins(PluginCapability.ShapeFromInk, context)
        .isNotEmpty()
    var inkToShapeKeepOriginal by remember {
        mutableStateOf(
            viewModel.pluginRegistry.settingsFor(InkToShapePlugin.ID)
                .getBoolean(InkToShapePlugin.SETTING_KEEP_ORIGINAL, false)
        )
    }
    // Stroke ids already converted to a shape this session (Phase 25). Guards
    // against re-converting the same raw stroke in keep-original mode, where it
    // remains in the stroke list after conversion.
    val convertedStrokeIds = remember { mutableStateListOf<String>() }
    com.authorss81.noteflow.utils.JankStatsHelper.MonitorJank("EditorScreen")
    // 22.9: tactile feedback for high-value affordances (skipped when the user
    // has reduce-motion/remove-animations enabled system-wide).
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current
    val voiceNoteManager = remember { VoiceNoteManager(context) }

    // Release recorder/player & cancel timer jobs when leaving the editor.
    // R2-b2b1-UI-05 (phase-153): a lock mid-recording disposes this composition
    // (recomposition dips to LockScreen) — `release()` fails closed by sweeping
    // the plaintext temp, but the finished recording's only error surface
    // (`recordingError`) dies with this collector. When release reports a
    // discard, the honest notice is re-published over the persistent snackbar
    // pipeline so it surfaces after unlock.
    DisposableEffect(voiceNoteManager) {
        onDispose {
            if (voiceNoteManager.release()) {
                viewModel.notifyVoiceRecordDiscarded()
            }
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceNoteManager.startRecording(page.id)
        } else {
            viewModel.showSnackbar("Microphone permission is required to record voice notes")
        }
    }

    // Phase 07: custom paper-texture pack (tiled page background, per-page pref).
    var paperTexturePath by remember(page.id) { mutableStateOf(viewModel.settings.paperTexturePathForPage(page.id)) }
    var paperTexture by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page.id, paperTexturePath) {
        paperTexture = if (paperTexturePath.isNullOrBlank()) {
            null
        } else {
            val bmp = withContext(Dispatchers.IO) { decodeBoundedBitmap(paperTexturePath!!, targetWidth = 512) }
            bmp?.asImageBitmap()
        }
    }

    // Phase 07 housekeeping: delete paper-texture files that no page references
    // anymore (page deleted, texture replaced or cleared while another page's
    // picker ran, etc.) so internal storage cannot accumulate stray files.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val referenced = viewModel.settings.allPaperTexturePaths().toSet()
            val dir = com.authorss81.noteflow.services.ImportExportService.getImportsDir(context)
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("paper_texture_") && file.absolutePath !in referenced) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    val bgImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            scope.launch {
                try {
                    // B2-DOS-05 (phase-81): bound the read to the ingest cap AND do it
                    // off the main thread (review fix) so a huge picker source can never
                    // be fully slurped into heap or jank the UI at pick time.
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)
                            ?.use { stream ->
                                com.authorss81.noteflow.services.AttachmentIngestPolicy.boundedReadBytes(stream)
                            }
                    }
                    if (bytes != null) {
                        val fileName = "custom_bg_${System.currentTimeMillis()}.png"
                        val savedPath = com.authorss81.noteflow.services.ImportExportService.persistFile(context, fileName, bytes)
                        viewModel.updatePageSource(page.id, savedPath, "image")
                        viewModel.showSnackbar("Custom paper background loaded!")
                    }
                } catch (e: com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException) {
                    viewModel.showSnackbar("Background image is too large (max 25 MB)")
                } catch (e: Exception) {
                    viewModel.showSnackbar("Failed to load custom background image")
                }
            }
        }
    }

    // Phase 07: custom paper-texture pack picker (tiled page background).
    val paperTexturePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            scope.launch {
                try {
                    // B2-DOS-05 (phase-81): bound the read to the ingest cap AND do it
                    // off the main thread (review fix).
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)
                            ?.use { stream ->
                                com.authorss81.noteflow.services.AttachmentIngestPolicy.boundedReadBytes(stream)
                            }
                    }
                    if (bytes != null) {
                        val ext = paperTextureExtensionFromUri(context, uri)
                        val fileName = "paper_texture_${System.currentTimeMillis()}.$ext"
                        deletePaperTextureFile(paperTexturePath)
                        val savedPath = com.authorss81.noteflow.services.ImportExportService.persistFile(context, fileName, bytes)
                        paperTexturePath = savedPath
                        viewModel.settings.setPaperTexturePathForPage(page.id, savedPath)
                        viewModel.showSnackbar("Paper texture applied (tiled)")
                    }
                } catch (e: com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException) {
                    viewModel.showSnackbar("Paper texture is too large (max 25 MB)")
                } catch (e: Exception) {
                    viewModel.showSnackbar("Failed to load paper texture")
                }
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
    // B2-DOS-03 (phase-79): a recording that hit the 30 min / 32 MB ceiling is
    // auto-finalized by the sampler — observe its published result so the audio
    // embed is attached instead of leaving an orphaned encrypted blob.
    val completedVoiceRecording by voiceNoteManager.completedRecordingResult.collectAsState()

    val paletteItems by viewModel.paletteItems.collectAsState()

    // Phase 172: PERSISTED recently-used colors + favorites (StateFlows seeded
    // from SettingsManager at VM init — no blocking prefs read on main). Explicit
    // picks/eyedropper samples record into recents via viewModel.recordRecentColor.
    val recentColors by viewModel.recentColors.collectAsState()
    val favoriteColors by viewModel.favoriteColors.collectAsState()

    // S-Pen / Stylus Palm Rejection & Pressure Toggle State
    var palmRejectionEnabled by remember { mutableStateOf(true) }
    var stylusPressureEnabled by remember { mutableStateOf(true) }
    var advancedBrushesEnabled by remember { mutableStateOf(false) }

    // Dynamic Color Palette Manager (Eyedropper Sampled Colors + recent colors)
    // Phase 19: default is the curated, organized vibrant palette (PaletteCatalog).
    var customPalette by remember {
        mutableStateOf(
            PaletteCatalog.defaultColorInts().map { Color(it) }
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
    var hapticsEnabled by remember { mutableStateOf(viewModel.settings.hapticsEnabled) }

    // Phase 19: dual eraser mode + render-time vibrancy. Stored in SharedPreferences
    // (SettingsManager), no DB schema change. OFF by default.
    var eraserMode by remember { mutableStateOf(com.authorss81.noteflow.services.EraserMode.fromSettingKey(viewModel.settings.eraserModeKey)) }
    var vibrancyEnabled by remember { mutableStateOf(viewModel.settings.vibrancyEnabled) }
    var vibrancyBoostLevel by remember { mutableFloatStateOf(viewModel.settings.vibrancyBoostLevel) }

    // Phase 07 painting features: stabilizer / pressure curve / symmetry mode.
    var stabilizerEnabled by remember { mutableStateOf(viewModel.settings.strokeStabilizerEnabled) }
    var pressureCurve by remember { mutableStateOf(PressureCurve.fromSettingKey(viewModel.settings.pressureCurveKey)) }


    // Phase 155: .inkbrush brush-preset IMPORT via the SAF picker. The bytes are
    // classified by the pure-JVM codec; reject reasons are sanitized (no file
    // paths or names — R2-b2b3-LOG-03 carried forward). Size-capped up front so a
    // hostile huge file is never fully slurped into memory.
    fun applyImportedPreset(preset: com.authorss81.noteflow.services.BrushPreset) {
        activeBrushPresetId = preset.id
        viewModel.settings.activeBrushPresetId = preset.id
        currentTool = preset.tool
        runCatching { currentColor = Color(android.graphics.Color.parseColor(preset.colorHex)) }.onFailure { }
        currentWidth = preset.size
        activePresetId = null
        activeCustomPresetId = null
        pressureCurve = com.authorss81.noteflow.services.PressureCurve.fromSettingKey(preset.pressureCurveKey)
        viewModel.settings.pressureCurveKey = pressureCurve.settingKey
    }

    fun persistImportedPresets(presets: List<com.authorss81.noteflow.services.BrushPreset>) {
        importedBrushPresets = presets
        viewModel.settings.importedBrushPresetsJson = com.authorss81.noteflow.services.BrushPresetFileCodec.encodeList(presets)
    }

    val brushPresetImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.let { stream ->
                        com.authorss81.noteflow.services.AttachmentIngestPolicy.boundedReadBytes(
                            stream,
                            com.authorss81.noteflow.services.BrushPresetImportPolicy.MAX_BRUSH_FILE_BYTES.toLong()
                        )
                    }
                }.getOrNull()
            }
            if (bytes == null) {
                viewModel.showSnackbar("Could not read that brush file")
                return@launch
            }
            when (val result = com.authorss81.noteflow.services.BrushPresetFileCodec.decode(bytes)) {
                is com.authorss81.noteflow.services.BrushPresetFileCodec.DecodeResult.Preset -> {
                    val preset = result.preset
                    if (!com.authorss81.noteflow.services.BrushPresetImportPolicy.canImport(
                            preset, importedBrushPresets.size, bytes.size
                        )
                    ) {
                        viewModel.showSnackbar("Import rejected — too many presets, too large, or not a paint tool")
                        return@launch
                    }
                    val existing = importedBrushPresets.firstOrNull { it.id == preset.id }
                    val updated = if (existing != null) {
                        importedBrushPresets.map { if (it.id == preset.id) preset else it }
                    } else {
                        importedBrushPresets + preset
                    }
                    persistImportedPresets(updated)
                    applyImportedPreset(preset)
                    viewModel.showSnackbar(
                        if (existing != null) "Preset updated from .inkbrush" else "Preset imported from .inkbrush"
                    )
                }
                is com.authorss81.noteflow.services.BrushPresetFileCodec.DecodeResult.RawProtobuf -> {
                    // A native androidx.ink binary brush (e.g. from Google Ink
                    // Tooling): hand it to the dormant ProtobufBrushLoader. The
                    // loader's own logging is already sanitized (class-name token
                    // only); here we surface a success/failure verdict with no
                    // file paths or names.
                    val family = com.authorss81.noteflow.services.ProtobufBrushLoader.loadFromByteArray(result.bytes)
                    viewModel.showSnackbar(
                        if (family != null) "Native .inkbrush brush loaded" else "That .inkbrush brush could not be parsed"
                    )
                }
                is com.authorss81.noteflow.services.BrushPresetFileCodec.DecodeResult.Invalid -> {
                    viewModel.showSnackbar("Import failed: ${result.reason}")
                }
            }
        }
    }

    // Phase 155: .inkbrush EXPORT of the current brush settings (stage in
    // app-private cacheDir, then hand to the SAF destination picker).
    fun exportCurrentBrushPreset() {
        val currentPreset = activeBrushPresetId
            ?.let { com.authorss81.noteflow.services.BrushPresetPack.byId(it) }
            ?: importedBrushPresets.firstOrNull { it.id == activeBrushPresetId }
        val preset = currentPreset ?: com.authorss81.noteflow.services.BrushPreset(
            id = "custom",
            name = "My Brush",
            tool = currentTool,
            brushParams = com.authorss81.noteflow.services.BrushStudioParams(),
            size = currentWidth,
            colorHex = "#" + Integer.toHexString(currentColor.toArgb() and 0xFFFFFF).uppercase().padStart(6, '0'),
            pressureCurveKey = pressureCurve.settingKey
        )
        val bytes = com.authorss81.noteflow.services.BrushPresetFileCodec.encode(preset)
        val file = java.io.File(context.cacheDir, "${com.authorss81.noteflow.services.BrushPresetImportPolicy.sanitizeName(preset.name) ?: "brush"}.inkbrush")
        val staged = runCatching {
            file.writeBytes(bytes)
            file
        }.getOrNull()
        if (staged == null) {
            viewModel.showSnackbar("Could not prepare the brush file for export")
            return
        }
        exporter.export(ExportDestinationPolicy.ExportKind.BRUSH_PRESET, staged) { outcome ->
            viewModel.showSnackbar(
                when (outcome) {
                    SaFExportResult.SAVED -> "Brush preset exported as .inkbrush"
                    SaFExportResult.CANCELLED -> "Brush export cancelled"
                    SaFExportResult.FAILED -> "Brush export failed — try a different destination"
                }
            )
        }
    }
    var symmetryMode by remember { mutableStateOf(SymmetryMode.fromSettingKey(viewModel.settings.symmetryModeKey)) }

    // Phase 35: minimap HUD visibility, persisted in SettingsManager so the
    // canvas minimap toggle survives navigation.
    var showMinimap by remember { mutableStateOf(viewModel.settings.minimapHudEnabled) }

    // Phase 129 restore: the ink bar is a horizontal capsule in portrait
    // (pre-35 default) and a vertical side column in landscape. Dragging,
    // snap-to-edge and cross-session dock persistence are opt-in extras
    // (default OFF) that never move/resize the bar by default. The dragged
    // offset is session-scoped (survives the bar's auto-hide) and additionally
    // persisted when dock persistence is on.
    var inkBarDraggable by remember { mutableStateOf(viewModel.settings.inkBarDraggable) }
    var inkBarSnapToEdgeEnabled by remember { mutableStateOf(viewModel.settings.inkBarSnapToEdgeEnabled) }
    var inkBarDockPersistEnabled by remember { mutableStateOf(viewModel.settings.inkBarDockPersistEnabled) }
    var inkBarDragOffsetX by remember { mutableFloatStateOf(-1f) }
    var inkBarDragOffsetY by remember { mutableFloatStateOf(-1f) }
    var minimapDraggable by remember { mutableStateOf(viewModel.settings.minimapDraggable) }

    // Phase 129: a persisted dock offset applies only when the user enabled
    // dock persistence (fail-closed — otherwise the bar rests at its default
    // pre-35 anchor).
    val persistedInkBarOffset = remember(inkBarDockPersistEnabled) {
        if (inkBarDockPersistEnabled &&
            FloatingWidgetDragPolicy.hasPersistedOffset(
                viewModel.settings.inkBarDragOffsetX,
                viewModel.settings.inkBarDragOffsetY
            )
        ) {
            com.authorss81.noteflow.services.FloatingWidgetDragPolicy.Offset(
                viewModel.settings.inkBarDragOffsetX,
                viewModel.settings.inkBarDragOffsetY
            )
        } else {
            null
        }
    }
    val effectiveInkBarDragOffset = remember(inkBarDragOffsetX, inkBarDragOffsetY, persistedInkBarOffset) {
        if (inkBarDragOffsetX >= 0f && inkBarDragOffsetY >= 0f) {
            com.authorss81.noteflow.services.FloatingWidgetDragPolicy.Offset(inkBarDragOffsetX, inkBarDragOffsetY)
        } else {
            persistedInkBarOffset
        }
    }

        LaunchedEffect(Unit) {
            val detectedTier = com.authorss81.noteflow.utils.DeviceCompatibilityManager.getDeviceTier(context, viewModel.settings)
            if (detectedTier == com.authorss81.noteflow.utils.DeviceTier.LOW_END) {
                if (!viewModel.settings.lowEndWarningShown) {
                    gpuWetBrushesEnabled = false
                    viewModel.settings.gpuWetBrushesEnabled = false
                    viewModel.settings.lowEndWarningShown = true
                    viewModel.showSnackbar(
                        "GPU Wet Brushes disabled for low-end device performance. You can override this in settings.",
                        isLong = true
                    )
                }
                // Phase 35: the minimap HUD draws per-frame viewport + thumbnails on
                // 2-core devices — turn it off once (with a message, never silent)
                // and let the user re-enable it from canvas settings.
                if (viewModel.settings.minimapHudEnabled && !viewModel.settings.lowEndMinimapWarningShown) {
                    showMinimap = false
                    viewModel.settings.minimapHudEnabled = false
                    viewModel.settings.lowEndMinimapWarningShown = true
                    viewModel.showSnackbar(
                        "Minimap HUD turned off for low-end device performance. You can re-enable it in canvas settings.",
                        isLong = true
                    )
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
    // R2-b2b1-UI-01 (phase-134): all three reads run through ONE guarded VM
    // accessor (loadEditorCanvasPage) so a lock() disposing the SQLCipher pool
    // mid-load degrades to armed-empty data + a notice instead of an uncaught
    // closed-pool ISE in this composition-scoped coroutine, and the results are
    // only assigned while the auth gate is still up.
    LaunchedEffect(page.id) {
        val data = viewModel.loadEditorCanvasPage(page.id)

        if (viewModel.authenticated.value) {
            strokes = data.strokes
            layers = data.layers
            activeLayerId = data.layers.firstOrNull { !it.locked }?.id ?: data.layers.firstOrNull()?.id

            stickyNotes = data.stickyNotes
            mediaEmbeds = data.mediaEmbeds
            referenceImage = data.referenceImage
            referenceImageControlsVisible = data.referenceImage != null

            if (!isPdf) {
                val maxStrokePage = data.strokes.maxOfOrNull { it.pdfPage } ?: 0
                val maxNotePage = data.stickyNotes.maxOfOrNull { it.pdfPage } ?: 0
                val maxEmbedPage = data.mediaEmbeds.maxOfOrNull { it.pdfPage } ?: 0
                val maxPage = maxOf(maxStrokePage, maxNotePage, maxEmbedPage)
                pdfTotalPages = maxOf(1, maxPage + 1)
            }
        }
        isInitialLoadComplete = true
    }

    // Phase 178: confined decode of the underlay artwork. The stored
    // contentUrlOrPath is the RELATIVE file name inside the app-private imports
    // dir; InlineImagePathPolicy.resolve re-verifies the canonical destination
    // still lives inside that subtree (B1-AUTH-05 contract) before any decode.
    // A policy-blocked or missing path yields null → the canvas renders no
    // underlay (fail-closed, never an arbitrary file read).
    LaunchedEffect(referenceImage?.contentUrlOrPath) {
        referenceImageBitmap = withContext(Dispatchers.IO) {
            val storedRelative = referenceImage?.contentUrlOrPath ?: return@withContext null
            val resolved = InlineImagePathPolicy.resolve(
                storedRelative,
                ImportExportService.getImportsDir(context)
            ) ?: return@withContext null
            decodeBoundedImage(resolved.absolutePath, maxDim = 1600)?.asImageBitmap()
        }
    }

    // Unmount Guard: flush save when navigating away — but NEVER when the vault
    // is locked (auto-lock / "Lock Vault Now" / ON_STOP dispose this editor while
    // the DEK is zeroized). The ViewModel routes the flush through its lock-safe
    // gate: unlocked ⇒ persist now; locked ⇒ stash the snapshot and write it
    // ENCRYPTED after the next unlock. The old code launched these saves with
    // `NonCancellable` and no authenticated/key check, which ran post-lock with
    // `encryptionKey == null` and wrote PLAINTEXT stroke/embed rows (B2-UI-1).
    //
    // B2-UI-3 (phase-73): the flush first CANCELS the pending debounced autosave
    // and AWAITS its settlement (viewModel.disposeEditorPageFlush) so a STALE
    // snapshot can never fire after this flush and land last — cancelling stops
    // the debounce, joining settles any write it already dispatched, then the
    // final (newest) snapshot is persisted. `saveJob` is nulled so a later
    // dispose can't double-cancel a job that already completed normally.
    DisposableEffect(page.id) {
        onDispose {
            if (isInitialLoadComplete) {
                val pending = saveJob
                saveJob = null
                viewModel.disposeEditorPageFlush(page.id, strokes, stickyNotes, mediaEmbeds, layers, pending)
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
        // B2-UI-3 (phase-73): the debounce coroutine now INCLUDES the write — the
        // VM autosave is suspended and runs the persistence inline — so `saveJob`
        // covers the whole debounced flush. That lets the dispose flush (via
        // viewModel.disposeEditorPageFlush) CANCEL a stale pending snapshot and
        // AWAIT a write that already started, guaranteeing the newest snapshot
        // lands last. The historical 1s debounce behaviour is unchanged.
        saveJob = viewModel.viewModelScope.launch(Dispatchers.IO) {
            delay(1000) // 1s Debounce
            // B2-UI-1 (phase-49): route through the VM lock-safe gate. The old
            // code called repository.saveStrokesForPage directly — a lock firing
            // inside this 1s window (deterministic on auto-lock) ran the write
            // with encryptionKey == null and persisted PLAINTEXT stroke rows.
            // The gate persists now, or defers the whole snapshot for after unlock.
            viewModel.autosaveStrokes(page.id, newStrokes, stickyNotes, mediaEmbeds, layers)
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
        // B2-UI-1 (phase-49): route the write through the lock-safe gate.
        viewModel.saveLayersGated(page.id, newLayers, strokes, stickyNotes, mediaEmbeds)
    }

    fun onAddLayer() {
        // R2-b2b4-DOS-02 (phase-150): the LIVE canvas caps at
        // LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT — the renderer keeps one
        // full-page ARGB bitmap per visible layer, so an uncapped add was a DoS
        // vector (crafted 40-layer page ≈ 416 MB native). Exact same number as
        // the PSD export cap, and the gate fails CLOSED with a non-alarming
        // notice (AGENTS.md: never silent degradation, always a message).
        if (LayerRenderBudgetPolicy.layerLimitReached(layers.size)) {
            viewModel.showSnackbar(LayerRenderBudgetPolicy.layerLimitNotice(), isLong = true)
            return
        }
        if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
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
        if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
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
        // R2-b2b4-DOS-02 (phase-150): same live-cap gate as [onAddLayer] — a
        // duplicate adds one full layer the renderer must rasterize per page.
        if (LayerRenderBudgetPolicy.layerLimitReached(layers.size)) {
            viewModel.showSnackbar(LayerRenderBudgetPolicy.layerLimitNotice(), isLong = true)
            return
        }
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
        // B2-UI-1 (phase-49): lock-safe gated write (persist now / defer until unlock).
        viewModel.flushEditorPageSave(page.id, strokes, stickyNotes, newEmbeds, layers)
    }

    // B2-DOS-03 (phase-79): shared attach path for a finished voice recording.
    // Used by the manual chip-tap stop AND the ceiling-abort auto-stop (observer
    // on `completedVoiceRecording`), so a capped recording is attached exactly
    // like a manual one — never silently dropped, never double-attached.
    fun attachVoiceRecording(result: com.authorss81.noteflow.services.VoiceRecordingResult) {
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

    // B2-DOS-03 (phase-79): observe a ceiling-aborted recording (30 min / 32 MB)
    // and attach the completed audio embed exactly like a manual chip-tap stop.
    // The result is published once per recording session and reset on the next
    // startRecording, so this effect can never double-attach. Declared after the
    // shared attach helper (Kotlin local functions are not forward-referenceable).
    LaunchedEffect(completedVoiceRecording) {
        val result = completedVoiceRecording ?: return@LaunchedEffect
        attachVoiceRecording(result)
    }

    // Phase 13: STICKER tool tap → persist an emoji sticker as a media_embeds row
    // (type=STICKER, stickerId in contentUrlOrPath, emoji in textContent which is
    // already covered by the export field-encryption map).
    fun handlePlaceSticker(canvasOffset: Offset, targetPage: Int) {
        val sticker = selectedStickerId?.let { com.authorss81.noteflow.services.StickerCatalog.byId(it) }
        if (sticker == null) {
            viewModel.showSnackbar("Pick a sticker from the sticker gallery first")
            return
        }
        val placed = com.authorss81.noteflow.services.StickerCatalog.placeTopLeft(
            tapX = canvasOffset.x,
            tapY = canvasOffset.y,
            size = com.authorss81.noteflow.services.StickerCatalog.DEFAULT_SIZE,
            pageWidth = 1080f,
            pageHeight = 1528f
        )
        val embed = CanvasMediaEmbed(
            pageId = page.id,
            type = MediaEmbedType.STICKER,
            x = placed.first,
            y = placed.second,
            width = com.authorss81.noteflow.services.StickerCatalog.DEFAULT_SIZE,
            height = com.authorss81.noteflow.services.StickerCatalog.DEFAULT_SIZE,
            contentUrlOrPath = sticker.id,
            textContent = sticker.emoji,
            pdfPage = targetPage,
            rotationDegrees = 0f
        )
        handleMediaEmbedsChange(mediaEmbeds + embed)
        viewModel.showSnackbar("Sticker added — drag to move, use the handle to rotate")
    }

    fun handleStickyNotesChange(newNotes: List<CanvasStickyNote>) {
        stickyNotes = newNotes
        // B2-UI-1 (phase-49): lock-safe gated write (persist now / defer until unlock).
        viewModel.flushEditorPageSave(page.id, strokes, newNotes, mediaEmbeds, layers)
    }

    // Phase 178: reset the underlay to a new opacity without touching its stored
    // geometry/path. The new opacity is range-gated by the policy and the row is
    // re-persisted through the lock-safe VM gate (the encrypted textContent is
    // re-encrypted inside the repository).
    fun handleReferenceImageOpacityChange(newOpacity: Float) {
        val current = referenceImage ?: return
        val updated = current.copy(
            textContent = ReferenceImagePolicy.encodeConfig(newOpacity)
        )
        referenceImage = updated
        viewModel.saveReferenceImage(page.id, updated)
    }

    // Phase 178: remove the underlay — its artwork file is deleted under the same
    // InlineImagePathPolicy confinement used to read it, then the row is removed.
    fun handleRemoveReferenceImage() {
        val current = referenceImage ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val storedRelative = current.contentUrlOrPath
                if (storedRelative != null) {
                    InlineImagePathPolicy
                        .resolve(storedRelative, ImportExportService.getImportsDir(context))
                        ?.let { runCatching { it.delete() } }
                }
            }
            referenceImage = null
            referenceImageBitmap = null
            referenceImageControlsVisible = false
            viewModel.saveReferenceImage(page.id, null)
            viewModel.showSnackbar("Reference image removed")
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

        // Phase 178: the underlay belongs to a page like any other canvas item —
        // inserting a page before/after it must shift its page index and world y.
        val updatedReference = referenceImage?.let { ref ->
            if (ref.pdfPage >= targetThreshold) {
                ref.copy(pdfPage = ref.pdfPage + 1, y = ref.y + pageStride)
            } else {
                ref
            }
        }

        undoStack = undoStack + listOf(strokes)
        redoStack = emptyList()

        strokes = updatedStrokes
        stickyNotes = updatedNotes
        mediaEmbeds = updatedEmbeds
        referenceImage = updatedReference
        pdfTotalPages += 1

        // B2-UI-1 (phase-49): lock-safe gated write.
        viewModel.flushEditorPageSave(page.id, updatedStrokes, updatedNotes, updatedEmbeds, layers)
        if (updatedReference != null) {
            viewModel.saveReferenceImage(page.id, updatedReference)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    // B2-DOS-05 (phase-81): bound the read to the ingest cap so a
                    // huge picker source can never be fully slurped into heap and
                    // OOM the embed at attach time — and do it off the main thread
                    // (review fix).
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            com.authorss81.noteflow.services.AttachmentIngestPolicy.boundedReadBytes(stream)
                        }
                    }
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
                        viewModel.showSnackbar("Photo attached to canvas")
                    }
                } catch (e: com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException) {
                    viewModel.showSnackbar("Photo is too large to attach (max 25 MB)")
                } catch (e: Exception) {
                    viewModel.showSnackbar("Could not attach the photo. It may be unreadable or unavailable.")
                }
            }
        }
    }

    // Phase 178: reference-image underlay insertion. One per page (per the phase
    // spec); reusing the SAF picker + bounded read, persisting the artwork into
    // the app-private imports dir, then storing the row with a RELATIVE path so
    // the renderer re-verifies it through InlineImagePathPolicy (B1-AUTH-05
    // confinement). Geometry is the aspect-preserving centered fit into the
    // current page world (pdf pages use the background bitmap's aspect).
    val referencePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            com.authorss81.noteflow.services.AttachmentIngestPolicy.boundedReadBytes(stream)
                        }
                    }
                    if (bytes != null) {
                        val savedFile = ImportExportService.persistFile(
                            context,
                            "reference_image_${System.currentTimeMillis()}.img",
                            bytes
                        )
                        val bmp = withContext(Dispatchers.IO) {
                            decodeBoundedImage(savedFile, maxDim = 1600)
                        }
                        if (bmp == null) {
                            viewModel.showSnackbar("Could not read that image as a reference underlay")
                            return@launch
                        }
                        val bg = pdfPageBitmaps[currentPdfPage]
                        val pageWorldW = 1080f
                        val pageWorldH = if (bg != null && bg.width > 0) {
                            pageWorldW * bg.height / bg.width
                        } else {
                            1528f
                        }
                        val fit = ReferenceImagePolicy.fitForPage(bmp.width, bmp.height, pageWorldW, pageWorldH)
                        val pageStride = 1528f + 64f
                        val centerViewportY = (-panOffset.y + 600f) / zoomScale
                        val activePageIdx = if (!isContinuousMode) currentPdfPage else (centerViewportY / pageStride).toInt().coerceIn(0, if (isPdf) (pdfTotalPages - 1).coerceAtLeast(0) else Int.MAX_VALUE)
                        val embed = CanvasMediaEmbed(
                            pageId = page.id,
                            type = MediaEmbedType.REFERENCE_IMAGE,
                            x = fit.x,
                            y = fit.y,
                            width = fit.width,
                            height = fit.height,
                            contentUrlOrPath = File(savedFile).name,
                            textContent = ReferenceImagePolicy.encodeConfig(ReferenceImagePolicy.DEFAULT_OPACITY),
                            pdfPage = activePageIdx
                        )
                        referenceImage = embed
                        referenceImageControlsVisible = true
                        viewModel.saveReferenceImage(page.id, embed)
                        viewModel.showSnackbar("Reference image placed — drag to position, trace over it, adjust opacity or remove from the controls")
                    }
                } catch (e: com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException) {
                    viewModel.showSnackbar("Image is too large for a reference underlay (max 25 MB)")
                } catch (e: Exception) {
                    viewModel.showSnackbar("Could not add the reference image. It may be unreadable or unavailable.")
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
            if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
        }
    }

    fun handleRedo() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.last()
            redoStack = redoStack.dropLast(1)
            undoStack = undoStack + listOf(strokes)
            strokes = nextState
            triggerAutoSave(nextState)
            if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
        }
    }

    // Phase 25: on-demand InkStroke→Shape conversion. Takes the LATEST freehand
    // stroke, runs the Ink→Shape plugin, and either REPLACES it with the crisp
    // shape or inserts the shape alongside it (namespaced `keepOriginal`
    // setting, decided by the plugin). Routes through the plugin manager on a
    // background dispatcher; the result is a normal undoable canvas operation
    // (handleStrokesChange pushes the pre-state onto the undo stack), and a
    // non-shape stroke is rejected honestly with no fake conversion.
    // `convertedStrokeIds` guards against re-converting a stroke that already
    // became a shape (in keep-original mode the raw stroke stays in the list,
    // so a second tap would otherwise duplicate the shape).
    fun convertLatestStrokeToShape() {
        val freehand = strokes.lastOrNull { it.tool.isFreehandTool }
        if (freehand == null) {
            viewModel.showSnackbar("No freehand stroke to convert — draw a line, circle, rectangle or arrow first")
            return
        }
        if (freehand.id in convertedStrokeIds) {
            viewModel.showSnackbar("That stroke was already converted to a shape — draw a new one to convert it")
            return
        }
        scope.launch {
            when (val result = viewModel.convertStrokeToShape(freehand)) {
                is PluginResult.Success -> when (val outcome = result.value) {
                    is ShapeFromInkOutcome.Success -> {
                        convertedStrokeIds += freehand.id
                        val updated = if (outcome.replaceOriginal) {
                            strokes.map { if (it.id == freehand.id) outcome.snappedStroke else it }
                        } else {
                            strokes + outcome.snappedStroke
                        }
                        handleStrokesChange(updated)
                        viewModel.showSnackbar("Converted ink to ${outcome.kind.label}")
                    }
                    is ShapeFromInkOutcome.NotAShape -> viewModel.showSnackbar(outcome.message)
                    is ShapeFromInkOutcome.Error -> viewModel.showSnackbar(outcome.message)
                }
                is PluginResult.Failure -> viewModel.showSnackbar(result.message)
                is PluginResult.Unavailable -> viewModel.showSnackbar(result.message)
            }
        }
    }

    BackHandler {
        saveJob?.cancel()
        if (isInitialLoadComplete) {
            // B2-UI-1 (phase-49): lock-safe gated flush (persist now / defer until unlock).
            viewModel.flushEditorPageSave(page.id, strokes, stickyNotes, mediaEmbeds, layers)
        }
        onBack()
    }

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
                                // B2-UI-1 (phase-49): lock-safe gated flush.
                                viewModel.flushEditorPageSave(page.id, strokes, stickyNotes, mediaEmbeds, layers)
                            }
                            onBack()
                        },
                        modifier = Modifier.size(48.dp)
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
                            if (com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                            if (isRecordingVoice) {
                                val result = voiceNoteManager.stopRecording()
                                if (result != null) {
                                    attachVoiceRecording(result)
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
                        modifier = Modifier.height(48.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Add Embeds Dropdown Menu (Sticky Note, Photo, Code)
                    var showEmbedMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showEmbedMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.AddCircleOutline, contentDescription = "Add Embed")
                    }
                    DropdownMenu(
                        expanded = showEmbedMenu,
                        onDismissRequest = { showEmbedMenu = false },
                        scrollState = overflowMenuScrollState(),
                        modifier = overflowMenuScrollModifier()
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
                        onDismissRequest = { showOverflowMenu = false },
                        scrollState = overflowMenuScrollState(),
                        modifier = overflowMenuScrollModifier()
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
                            text = { Text("Screenshot → new note") },
                            leadingIcon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                scope.launch {
                                    val bgBmp = pdfPageBitmaps[currentPdfPage]?.asAndroidBitmap()
                                    when (val r = viewModel.captureScreenshotNote(
                                        strokes = strokes,
                                        layers = layers,
                                        stickyNotes = stickyNotes,
                                        mediaEmbeds = mediaEmbeds,
                                        bgBitmap = bgBmp,
                                        template = template,
                                        pageIndex = currentPdfPage,
                                        shouldOcr = false,
                                        onCreated = { note ->
                                            viewModel.showSnackbar("Screenshot saved as note: ${note.title}", isLong = true)
                                        }
                                    )) {
                                        is com.authorss81.noteflow.plugins.PluginResult.Success -> {
                                            val o = r.value
                                            if (o is com.authorss81.noteflow.plugins.ScreenshotCaptureOutcome.Error) {
                                                viewModel.showSnackbar("Screenshot failed: ${o.message}", isLong = true)
                                            }
                                        }
                                        is com.authorss81.noteflow.plugins.PluginResult.Failure ->
                                            viewModel.showSnackbar("Screenshot failed: ${r.message}", isLong = true)
                                        is com.authorss81.noteflow.plugins.PluginResult.Unavailable ->
                                            viewModel.showSnackbar("Screenshot unavailable: ${r.message}", isLong = true)
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Screenshot → new note + OCR") },
                            leadingIcon = { Icon(Icons.Outlined.TextSnippet, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                scope.launch {
                                    val bgBmp = pdfPageBitmaps[currentPdfPage]?.asAndroidBitmap()
                                    when (val r = viewModel.captureScreenshotNote(
                                        strokes = strokes,
                                        layers = layers,
                                        stickyNotes = stickyNotes,
                                        mediaEmbeds = mediaEmbeds,
                                        bgBitmap = bgBmp,
                                        template = template,
                                        pageIndex = currentPdfPage,
                                        shouldOcr = true,
                                        onCreated = { note ->
                                            viewModel.showSnackbar("Screenshot + OCR saved as note: ${note.title}", isLong = true)
                                        }
                                    )) {
                                        is com.authorss81.noteflow.plugins.PluginResult.Success -> {
                                            val o = r.value
                                            if (o is com.authorss81.noteflow.plugins.ScreenshotCaptureOutcome.Error) {
                                                viewModel.showSnackbar("Screenshot failed: ${o.message}", isLong = true)
                                            }
                                        }
                                        is com.authorss81.noteflow.plugins.PluginResult.Failure ->
                                            viewModel.showSnackbar("Screenshot failed: ${r.message}", isLong = true)
                                        is com.authorss81.noteflow.plugins.PluginResult.Unavailable ->
                                            viewModel.showSnackbar("Screenshot unavailable: ${r.message}", isLong = true)
                                    }
                                }
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
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.PAGE_PNG,
                                            file
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> Unit
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Export to the chosen destination failed")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Export failed")
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Page as WebP") },
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
                                        pageIndex = currentPdfPage,
                                        exportImageFormat = com.authorss81.noteflow.services.ImportExportService.ExportImageFormat.WEBP
                                    )
                                    if (file != null) {
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.PAGE_WEBP,
                                            file
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> Unit
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Export to the chosen destination failed")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Export failed")
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
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.PAGE_PDF,
                                            file
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> Unit
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Export to the chosen destination failed")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Export failed")
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
                                    // Phase-182: totalPages must reflect the REAL source
                                    // page count (pdfTotalPages — set via getPdfPageCount
                                    // for PDFs and pageCountNeeded for tall images), NOT
                                    // the memory-bounded visible window size
                                    // pdfPageBitmaps — otherwise every page beyond the
                                    // visible window was silently dropped and exported
                                    // blank. sourceFilePath is threaded through so the
                                    // per-page background fallback re-renders every
                                    // page's source (PDF page N via renderPdfPageToBitmap,
                                    // tall images via sampled decode).
                                    val totalPages = maxOf(1, pdfTotalPages, (strokes.maxOfOrNull { it.pdfPage } ?: 0) + 1)
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
                                        mediaEmbeds = mediaEmbeds,
                                        sourceFilePath = page.sourceFilePath
                                    )
                                    if (file != null) {
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.DOCUMENT_PDF,
                                            file
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> Unit
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Export to the chosen destination failed")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Document PDF export failed")
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
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.NOTE_HTML,
                                            file
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> Unit
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Export to the chosen destination failed")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("HTML export failed")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Share via Export Engine…") },
                            leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                val request = com.authorss81.noteflow.plugins.ExportRequest(
                                    title = page.title,
                                    markdown = page.extractedText?.takeIf { it.isNotBlank() },
                                    plainText = page.extractedText?.takeIf { it.isNotBlank() }
                                )
                                scope.launch {
                                    when (val result = viewModel.exportNote(
                                        request,
                                        com.authorss81.noteflow.plugins.ExportFormat.MARKDOWN
                                    )) {
                                        is com.authorss81.noteflow.plugins.PluginResult.Success -> {
                                            when (val outcome = result.value) {
                                                is com.authorss81.noteflow.plugins.ExportOutcome.Success -> {
                                                    // R2-B1P-03 (phase-141): launch the
                                                    // chooser (target always user-chosen) and
                                                    // delete the staging file when it dismisses.
                                                    pendingExportFilePath = outcome.file.absolutePath
                                                    try {
                                                        val chooser = com.authorss81.noteflow.plugins.export.ExportShareHelper.chooserForExport(
                                                            context, outcome.file, outcome.format.mimeType
                                                        )
                                                        exportShareLauncher.launch(chooser)
                                                    } catch (e: Exception) {
                                                        // No activity can handle the share (or the
                                                        // chooser build failed) — never leave the
                                                        // plaintext export in the grantable cache root.
                                                        runCatching { outcome.file.delete() }
                                                        pendingExportFilePath = null
                                                        viewModel.showSnackbar(
                                                            "No app on this device can receive the export",
                                                            isLong = true
                                                        )
                                                    }
                                                }
                                                is com.authorss81.noteflow.plugins.ExportOutcome.Error ->
                                                    viewModel.showSnackbar(outcome.message, isLong = true)
                                            }
                                        }
                                        is com.authorss81.noteflow.plugins.PluginResult.Failure ->
                                            viewModel.showSnackbar(result.message, isLong = true)
                                        is com.authorss81.noteflow.plugins.PluginResult.Unavailable ->
                                            viewModel.showSnackbar(result.message, isLong = true)
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
                                    val outcome = ImportExportService.exportPageToPsd(context, page, viewModel.repository)
                                    if (outcome.file != null) {
                                            exporter.export(
                                                ExportDestinationPolicy.ExportKind.LAYERED_PSD,
                                                outcome.file
                                            ) { result ->
                                                when (result) {
                                                    SaFExportResult.SAVED -> if (outcome.wasLayerCapped) {
                                                        // B2-DOS-06 (phase-82): when the layer budget
                                                        // dropped layers, tell the user once — but only
                                                        // AFTER the export completed, so a cancelled
                                                        // picker can never hide the omission info behind
                                                        // "Export cancelled". Non-alarming, never silent.
                                                        viewModel.showSnackbar(
                                                            PsdExportPolicy.noticeMessage(
                                                                outcome.exportedLayerCount,
                                                                outcome.omittedLayerCount
                                                            ),
                                                            isLong = true
                                                        )
                                                    }
                                                    SaFExportResult.CANCELLED -> viewModel.showSnackbar("Export cancelled")
                                                    SaFExportResult.FAILED -> viewModel.showSnackbar("Export to the chosen destination failed")
                                                }
                                            }
                                        } else {
                                            viewModel.showSnackbar("PSD export failed")
                                        }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export Section Vault (ZIP)") },
                            leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.showSnackbar("Packaging Section Vault ZIP...")
                                viewModel.exportSectionVaultZip(context, page.sectionId) { zipFile ->
                                    if (zipFile != null) {
                                        exporter.export(
                                            ExportDestinationPolicy.ExportKind.VAULT_ZIP,
                                            zipFile
                                        ) { result ->
                                            when (result) {
                                                SaFExportResult.SAVED -> Unit
                                                SaFExportResult.CANCELLED -> viewModel.showSnackbar("Export cancelled")
                                                SaFExportResult.FAILED -> viewModel.showSnackbar("Export to the chosen destination failed")
                                            }
                                        }
                                    } else {
                                        viewModel.showSnackbar("Section Vault export failed")
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
                            text = {
                                Text(if (referenceImage == null) "Insert Reference Image…" else "Remove Reference Image")
                            },
                            leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                if (referenceImage == null) {
                                    referencePickerLauncher.launch("image/*")
                                } else {
                                    handleRemoveReferenceImage()
                                }
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
            // Phase 155: quick-color ring swatches — seeded from the user's saved
            // DesignerPalette swatches (SKAP_WATCH items), falling back to the
            // current tool color + a small neutral ramp when the vault palette is
            // empty. Capped to the ring budget so layout and hit-test never drift.
            val quickColorSwatches = buildList {
                if (paletteItems.isEmpty()) {
                    add(currentColor)
                    add(Color(0xFF000000)); add(Color(0xFFFFFFFF)); add(Color(0xFFE11D48))
                    add(Color(0xFFF59E0B)); add(Color(0xFF22C55E)); add(Color(0xFF3B82F6))
                    add(Color(0xFF8B5CF6)); add(Color(0xFFEC4899))
                } else {
                    addAll(paletteItems.filter { it.type == "SWATCH" }.map { Color(it.colorInt) })
                }
            }.take(com.authorss81.noteflow.services.QuickColorRingMath.MAX_SWATCHES)

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
                paperTexture = paperTexture,
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
                minimapDraggable = minimapDraggable,
                isRecordingVoice = isRecordingVoice,
                recordingElapsedMsProvider = { recordingElapsedMs },
                activeVoicePlaybackFilePath = activeVoicePlaybackFilePath,
                isPlayingVoice = isPlayingVoice,
                activeVoicePositionMsProvider = { activeVoicePositionMs },
                activeVoiceSpeed = activeVoiceSpeed,
                gpuWetBrushesEnabled = gpuWetBrushesEnabled,
                shapeAutoSnapEnabled = shapeAutoSnapEnabled,
                hapticsEnabled = hapticsEnabled,
                stabilizerEnabled = stabilizerEnabled,
                pressureCurve = pressureCurve,
                symmetryMode = symmetryMode,
                onZoomScaleChanged = { zoomScale = it },
                onPanOffsetChanged = { panOffset = it },
                onVisiblePageWindowChanged = { newWindow ->
                    visiblePageWindow = newWindow
                },
                // Phase-150 review fix 4: once per session, tell the user when the
                // note's own strokes extend past the world ceiling (their tail ink
                // is folded by CanvasPageBudgetPolicy, never silently).
                onDynamicPageCountCapped = {
                    viewModel.showSnackbar(CanvasPageBudgetPolicy.pageCountCappedNotice(), isLong = true)
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
                    viewModel.settings.brushColorArgb = sampledColor.toArgb()
                    // Phase 172: an eyedropper pick is a real color choice — persist
                    // it into the recent-colors list too (was only the volatile list).
                    viewModel.recordRecentColor(sampledColor.toArgb())
                    // Eyedropper returns a concrete pixel color → back to SOLID (a
                    // reasonable default the user can re-override with a mode chip).
                    if (currentColorMode.isMultiColor) {
                        currentColorMode = StrokeColorMode.SOLID
                        viewModel.settings.brushColorModeKey = StrokeColorMode.SOLID.persistenceKey
                        viewModel.showSnackbar("Eyedropper sampled a solid color")
                    }
                    if (sampledColor !in customPalette) {
                        customPalette = customPalette + sampledColor
                    }
                },
                onDrawingStart = {
                    if (currentColorMode.isMultiColor) {
                        currentColorSeed = (Math.random() * 360).toInt()
                    }
                    toolbarState = FloatingToolbarState.HIDDEN_DRAWING
                },
                onDrawingEnd = {
                    if (toolbarState == FloatingToolbarState.HIDDEN_DRAWING) {
                        toolbarState = FloatingToolbarState.COLLAPSED
                    }
                },
                onCanvasTap = {
                    toolbarState = FloatingToolbarState.COLLAPSED
                },
                onExtractOcr = if (ocrAvailable) {
                    { path -> ocrTargetPath = path }
                } else {
                    // No OCR plugin enabled/available right now — pass null so the
                    // photo card's "Extract text" button renders disabled instead
                    // of silently doing nothing on tap.
                    null
                },
                selectedStickerId = selectedStickerId,
                onPlaceSticker = { canvasOffset, targetPage ->
                    handlePlaceSticker(canvasOffset, targetPage)
                },
                activeBrushPresetId = activeBrushPresetId,
                eraserMode = eraserMode,
                vibrancyEnabled = vibrancyEnabled,
                vibrancyBoostLevel = vibrancyBoostLevel,
                currentColorMode = currentColorMode,
                currentColorSeed = currentColorSeed,
                currentGradientToColor = currentGradientToColor,
                twoFingerGesturesEnabled = twoFingerGesturesEnabled,
                onTwoFingerUndo = { handleUndo() },
                onTwoFingerRedo = { handleRedo() },
                quickColorRingEnabled = quickColorRingEnabled,
                quickColorSwatches = quickColorSwatches,
                onQuickColorPicked = { picked ->
                    currentColor = picked
                    viewModel.settings.brushColorArgb = picked.toArgb()
                    if (currentColorMode.isMultiColor) {
                        currentColorMode = StrokeColorMode.SOLID
                        viewModel.settings.brushColorModeKey = StrokeColorMode.SOLID.persistenceKey
                    }
                },
                importedBrushPresets = importedBrushPresets,
                // Phase 178: reference-image underlay (bitmap confined via
                // InlineImagePathPolicy; opacity range-gated by the policy).
                referenceImage = referenceImageBitmap,
                referenceImageOpacity = referenceImage?.let { ReferenceImagePolicy.decodeOpacity(it.textContent) }
                    ?: ReferenceImagePolicy.DEFAULT_OPACITY,
                referenceImageX = referenceImage?.x ?: 0f,
                referenceImageY = referenceImage?.y ?: 0f,
                referenceImageWidth = referenceImage?.width ?: 0f,
                referenceImageHeight = referenceImage?.height ?: 0f,
                referenceImagePage = referenceImage?.pdfPage ?: 0
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

            // Floating Tool Dock (Phase 35) — pill that snaps to any screen edge
            // with a spring and auto-tucks while a stroke is being drawn.
            val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

            AnimatedVisibility(
                visible = toolbarState != FloatingToolbarState.HIDDEN_DRAWING,
                enter = com.authorss81.noteflow.theme.MotionSystem.enter(
                    if (isLandscape) {
                        fadeIn() + androidx.compose.animation.slideInHorizontally { it }
                    } else {
                        fadeIn() + slideInVertically { it }
                    }
                ),
                exit = com.authorss81.noteflow.theme.MotionSystem.exit(
                    if (isLandscape) {
                        fadeOut() + androidx.compose.animation.slideOutHorizontally { it }
                    } else {
                        fadeOut() + slideOutVertically { it }
                    }
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                FloatingToolDock(
                    currentTool = currentTool,
                    lastDrawingTool = lastDrawingTool,
                    currentColor = currentColor,
                    currentWidth = currentWidth,
                    toolbarState = toolbarState,
                    isLandscape = isLandscape,
                    draggable = inkBarDraggable,
                    snapToEdgeEnabled = inkBarSnapToEdgeEnabled,
                    dockPersistEnabled = inkBarDockPersistEnabled,
                    draggedOffset = effectiveInkBarDragOffset,
                    onChangeDraggedOffset = { x, y ->
                        inkBarDragOffsetX = x
                        inkBarDragOffsetY = y
                        if (inkBarDockPersistEnabled) {
                            viewModel.settings.inkBarDragOffsetX = x
                            viewModel.settings.inkBarDragOffsetY = y
                        }
                    },
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
                    onQuickTool = { tool ->
                        toolbarState = FloatingToolbarState.COLLAPSED
                        currentTool = tool
                        activePresetId = null
                        activeCustomPresetId = null
                        if (tool == StrokeTool.STICKER) {
                            toolbarState = FloatingToolbarState.STICKER_PICKER
                        }
                    }
                )
            }

            // Phase 12: on-device OCR — result dialog + insert the extracted text
            // into the note as a sticky note placed just below the source image.
            ocrTargetPath?.let { path ->
                OcrResultDialog(
                    imagePath = path,
                    viewModel = viewModel,
                    onInsertIntoNote = { text ->
                        val pageStride = 1528f + 64f
                        val sourceEmbed = mediaEmbeds.firstOrNull { it.contentUrlOrPath == path }
                        val anchorY = sourceEmbed?.y ?: ((-panOffset.y / zoomScale) + 300f)
                        val note = CanvasStickyNote(
                            x = sourceEmbed?.x ?: 120f,
                            y = anchorY + (sourceEmbed?.height ?: 260f) + 40f,
                            width = 260f,
                            height = 220f,
                            text = text,
                            pdfPage = sourceEmbed?.pdfPage ?: currentPdfPage
                        )
                        handleStickyNotesChange(stickyNotes + note)
                        viewModel.showSnackbar("OCR text inserted as a sticky note")
                    },
                    onDismiss = { ocrTargetPath = null }
                )
            }

            // Phase 178: the reference-image control card — shown when the page
            // has an underlay and the controls are open (auto-opened on insert).
            // Holds the opacity slider (range-gated) and removal. Compact and
            // dismissible so it can never crowd the canvas.
            if (referenceImage != null && referenceImageControlsVisible) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 12.dp, end = 12.dp, top = 4.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Reference Image",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { referenceImageControlsVisible = false },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Hide reference image controls",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        val currentOpacity = referenceImage?.let { ReferenceImagePolicy.decodeOpacity(it.textContent) }
                            ?: ReferenceImagePolicy.DEFAULT_OPACITY
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Opacity", style = MaterialTheme.typography.labelSmall)
                            Slider(
                                value = currentOpacity,
                                onValueChange = { handleReferenceImageOpacityChange(it) },
                                valueRange = ReferenceImagePolicy.MIN_OPACITY..ReferenceImagePolicy.MAX_OPACITY,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            )
                            Text(
                                text = "${(currentOpacity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = { handleRemoveReferenceImage() }) {
                            Text("Remove Reference Image", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
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
                eraserMode = eraserMode,
                onEraserModeChange = { mode ->
                    eraserMode = mode
                    viewModel.settings.eraserModeKey = mode.key
                    viewModel.showSnackbar(
                        if (mode == com.authorss81.noteflow.services.EraserMode.PARTIAL) {
                            "Partial eraser on — drag over strokes to trim them"
                        } else {
                            "Whole-stroke eraser on — any touch removes the stroke"
                        },
                        isLong = false
                    )
                },
                onToolSelect = { tool ->
                    currentTool = tool
                    activePresetId = null
                    // Phase 13: choosing the Sticker tool opens the offline emoji
                    // gallery so there is always a real sticker to place.
                    if (tool == StrokeTool.STICKER) {
                        toolbarState = FloatingToolbarState.STICKER_PICKER
                    }
                },
                onDismiss = { toolbarState = FloatingToolbarState.COLLAPSED },
                onSnackbar = { text, isLong -> viewModel.showSnackbar(text, isLong) }
            )
        }
        FloatingToolbarState.COLOR_PICKER -> {
            ColorPickerBottomSheet(
                currentColor = currentColor,
                palette = customPalette,
                advancedBrushesEnabled = advancedBrushesEnabled,
                savedSwatches = paletteItems.filter { it.type == "SWATCH" },
                currentColorMode = currentColorMode,
                currentGradientToColor = currentGradientToColor,
                // Phase 172: persisted recents/favorites drive the picker rows.
                recentColors = recentColors.map { Color(it) },
                favoriteColors = favoriteColors.map { Color(it) },
                onToggleFavorite = { color -> viewModel.toggleFavoriteColor(color.toArgb()) },
                onRecordRecent = { color -> viewModel.recordRecentColor(color.toArgb()) },
                onColorSelect = { color ->
                    // Picking a concrete solid color → SOLID mode (multi-color modes
                    // are re-engaged explicitly via the mode chips).
                    currentColor = color
                    viewModel.settings.brushColorArgb = color.toArgb()
                    currentColorMode = StrokeColorMode.SOLID
                    viewModel.settings.brushColorModeKey = StrokeColorMode.SOLID.persistenceKey
                    activePresetId = null
                    activeCustomPresetId = null
                },
                onColorModeChange = handleColorModeChange,
                onGradientToColorSelect = handleGradientToColorSelect,
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
                currentColorMode = currentColorMode,
                currentGradientToColor = currentGradientToColor,
                onColorModeChange = handleColorModeChange,
                onGradientToColorSelect = handleGradientToColorSelect,
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
                minimapDraggable = minimapDraggable,
                inkBarDraggable = inkBarDraggable,
                inkBarSnapToEdgeEnabled = inkBarSnapToEdgeEnabled,
                inkBarDockPersistEnabled = inkBarDockPersistEnabled,
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
                hapticsEnabled = hapticsEnabled,
                onHapticsToggle = { enabled ->
                    hapticsEnabled = enabled
                    viewModel.settings.hapticsEnabled = enabled
                },
                inkToShapeAvailable = inkToShapeAvailable,
                inkToShapeKeepOriginal = inkToShapeKeepOriginal,
                onInkToShapeKeepOriginalChange = { enabled ->
                    inkToShapeKeepOriginal = enabled
                    viewModel.pluginRegistry.settingsFor(InkToShapePlugin.ID)
                        .setBoolean(InkToShapePlugin.SETTING_KEEP_ORIGINAL, enabled)
                    viewModel.pluginRegistry.notifyConfigChanged(InkToShapePlugin.ID)
                },
                onConvertToShape = {
                    toolbarState = FloatingToolbarState.COLLAPSED
                    convertLatestStrokeToShape()
                },
                stabilizerEnabled = stabilizerEnabled,
                onStabilizerToggle = { enabled ->
                    stabilizerEnabled = enabled
                    viewModel.settings.strokeStabilizerEnabled = enabled
                },
                pressureCurve = pressureCurve,
                onPressureCurveSelect = { curve ->
                    pressureCurve = curve
                    viewModel.settings.pressureCurveKey = curve.settingKey
                },
                symmetryMode = symmetryMode,
                onSymmetryModeSelect = { mode ->
                    symmetryMode = mode
                    viewModel.settings.symmetryModeKey = mode.settingKey
                    if (mode != SymmetryMode.OFF && gpuWetBrushesEnabled) {
                        viewModel.showSnackbar("Symmetry on: GPU wet-mix falls back to classic rendering while active")
                    }
                },
                onContinuousModeToggle = { isContinuousMode = !isContinuousMode },
                onDividePagesToggle = { divideIntoPages = !divideIntoPages },
                onTemplateSelect = { selectedTemplate ->
                    template = selectedTemplate
                    viewModel.updatePageTemplate(page.id, selectedTemplate)
                },
                paperTexturePath = paperTexturePath,
                onUploadPaperTexture = { paperTexturePicker.launch("image/*") },
                onClearPaperTexture = {
                    deletePaperTextureFile(paperTexturePath)
                    paperTexturePath = null
                    paperTexture = null
                    viewModel.settings.setPaperTexturePathForPage(page.id, null)
                },
                onPaperColorSelect = { selectedHex ->
                    paperColorHex = selectedHex
                },
                onUploadCustomBg = { bgImagePicker.launch("image/*") },
                onMinimapToggle = {
                    showMinimap = !showMinimap
                    viewModel.settings.minimapHudEnabled = showMinimap
                },
                onMinimapDraggableToggle = {
                    minimapDraggable = !minimapDraggable
                    viewModel.settings.minimapDraggable = minimapDraggable
                },
                onInkBarDraggableToggle = {
                    inkBarDraggable = !inkBarDraggable
                    viewModel.settings.inkBarDraggable = inkBarDraggable
                },
                onInkBarSnapToEdgeToggle = {
                    inkBarSnapToEdgeEnabled = !inkBarSnapToEdgeEnabled
                    viewModel.settings.inkBarSnapToEdgeEnabled = inkBarSnapToEdgeEnabled
                },
                onInkBarDockPersistToggle = {
                    inkBarDockPersistEnabled = !inkBarDockPersistEnabled
                    viewModel.settings.inkBarDockPersistEnabled = inkBarDockPersistEnabled
                },
                onResetZoomPan = {
                    zoomScale = 1f
                    panOffset = Offset.Zero
                },
                onInsertPageBefore = { insertPage(before = true) },
                onInsertPageAfter = { insertPage(before = false) },
                activeBrushPresetName = activeBrushPresetId
                    ?.let { com.authorss81.noteflow.services.BrushPresetPack.byId(it)?.name },
                onOpenBrushPresets = { toolbarState = FloatingToolbarState.BRUSH_PRESETS },
                twoFingerUndoRedoEnabled = twoFingerGesturesEnabled,
                onTwoFingerUndoRedoToggle = { enabled ->
                    twoFingerGesturesEnabled = enabled
                    viewModel.settings.twoFingerUndoRedoEnabled = enabled
                    if (enabled && !viewModel.settings.twoFingerHintShown) {
                        viewModel.settings.twoFingerHintShown = true
                        viewModel.showSnackbar(
                            "Two-finger gestures ON — swipe left/right or two-finger double-tap to undo/redo. Pinch-zoom is unchanged.",
                            isLong = true
                        )
                    }
                },
                quickColorRingEnabled = quickColorRingEnabled,
                onQuickColorRingToggle = { enabled ->
                    quickColorRingEnabled = enabled
                    viewModel.settings.quickColorRingEnabled = enabled
                    if (enabled && !viewModel.settings.quickColorRingHintShown) {
                        viewModel.settings.quickColorRingHintShown = true
                        viewModel.showSnackbar(
                            "Quick-color ring ON — long-press the canvas to pick a color.",
                            isLong = true
                        )
                    }
                },
                vibrancyEnabled = vibrancyEnabled,
                onVibrancyToggle = { enabled ->
                    vibrancyEnabled = enabled
                    viewModel.settings.vibrancyEnabled = enabled
                    viewModel.showSnackbar(
                        if (enabled) "Vibrancy ON — richer, more saturated colors (render-time only)" else "Vibrancy OFF — stored colors restored",
                        isLong = false
                    )
                },
                vibrancyBoostLevel = vibrancyBoostLevel,
                onVibrancyBoostChange = { level ->
                    vibrancyBoostLevel = level
                    viewModel.settings.vibrancyBoostLevel = level
                },
                onDismiss = { toolbarState = FloatingToolbarState.COLLAPSED }
            )
        }
        FloatingToolbarState.STICKER_PICKER -> {
            StickerPickerBottomSheet(
                selectedStickerId = selectedStickerId,
                onStickerSelect = { stickerId ->
                    selectedStickerId = stickerId
                    currentTool = StrokeTool.STICKER
                    activePresetId = null
                    viewModel.showSnackbar("Sticker selected — tap the canvas to place it")
                },
                onDismiss = { toolbarState = FloatingToolbarState.COLLAPSED }
            )
        }
        FloatingToolbarState.BRUSH_PRESETS -> {
            BrushPresetPickerBottomSheet(
                activePresetId = activeBrushPresetId,
                onPresetSelect = { preset ->
                    activeBrushPresetId = preset.id
                    viewModel.settings.activeBrushPresetId = preset.id
                    currentTool = preset.tool
                    currentColor = Color(android.graphics.Color.parseColor(preset.colorHex))
                    currentWidth = preset.size
                    activePresetId = null
                    activeCustomPresetId = null
                    viewModel.showSnackbar("Preset applied — start drawing with the brush")
                },
                onPresetClear = {
                    activeBrushPresetId = null
                    viewModel.settings.activeBrushPresetId = null
                    viewModel.showSnackbar("Ready-made preset cleared — classic brush settings restored")
                },
                // Phase 155: .inkbrush import/export + the user's "My presets".
                importedPresets = importedBrushPresets,
                onImportPreset = { brushPresetImportLauncher.launch("application/octet-stream") },
                onExportPreset = { exportCurrentBrushPreset() },
                onImportedPresetSelect = { preset ->
                    applyImportedPreset(preset)
                    viewModel.showSnackbar("Imported preset applied — start drawing with the brush")
                },
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
 * Floating ink bar (Phase 129 restore) — the pre-phase-35 56dp horizontal
 * capsule in portrait and a 56dp-wide vertical side column in landscape,
 * posture decided by [DockPosturePolicy]. The bar sits at its default
 * bottom-centred / end-centred anchor and is only draggable when the user opts
 * in (`inkBarDraggable`, default OFF) — snap-to-edge on release and
 * cross-session persistence are separate opt-in extras, so nothing moves or
 * resizes the bar by default. The bar tucks away while a stroke is being drawn
 * (toolbarState == HIDDEN_DRAWING) and returns on canvas tap/stroke end.
 * Expanding the pill reveals the phase-35 one-tap quick-tool rail, so every
 * existing tool stays reachable in ≤2 taps (quick rail = 1, tool picker = 2).
 */
@Composable
private fun FloatingToolDock(
    currentTool: StrokeTool,
    lastDrawingTool: StrokeTool,
    currentColor: Color,
    currentWidth: Float,
    toolbarState: FloatingToolbarState,
    isLandscape: Boolean,
    draggable: Boolean,
    snapToEdgeEnabled: Boolean,
    dockPersistEnabled: Boolean,
    draggedOffset: com.authorss81.noteflow.services.FloatingWidgetDragPolicy.Offset?,
    onChangeDraggedOffset: (Float, Float) -> Unit,
    onToolClick: () -> Unit,
    onTogglePan: () -> Unit,
    onColorClick: () -> Unit,
    onWidthClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onQuickTool: (StrokeTool) -> Unit
) {
    val reduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Session drag state. rawPos tracks the finger while dragging; snapAnim
    // springs the resting target. The resting target is the dragged offset
    // (when the gate is open) else the orientation default anchor.
    var rawPos by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    val snapAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var initialized by remember { mutableStateOf(false) }
    var waitForMeasure by remember { mutableStateOf(true) }
    var dockW by remember { mutableFloatStateOf(with(density) { 156.dp.toPx() }) }
    var dockH by remember { mutableFloatStateOf(with(density) { 56.dp.toPx() }) }
    var expanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val bottomMarginPx = with(density) { 20.dp.toPx() }
        val endMarginPx = with(density) { 20.dp.toPx() }

        // Phase 129: posture is orientation-only. A bar mid-screen no longer
        // morphs into a vertical dock (phase-35 behaviour) — the portrait pill
        // stays horizontal wherever it is dragged.
        val horizontalPosture = DockPosturePolicy.isHorizontal(isLandscape)
        val defaultAnchor = if (horizontalPosture) {
            DockPosturePolicy.horizontalDefaultAnchor(screenW, screenH, dockW, dockH, bottomMarginPx)
        } else {
            DockPosturePolicy.verticalDefaultAnchor(screenW, screenH, dockW, dockH, endMarginPx)
        }
        val restingPos = FloatingWidgetDragPolicy.restingPosition(
            enabled = draggable,
            draggedX = draggedOffset?.x,
            draggedY = draggedOffset?.y,
            defaultX = defaultAnchor.first,
            defaultY = defaultAnchor.second
        )

        val insets = WindowInsets.safeDrawing
        val topInsetPx = with(density) { insets.getTop(density).toFloat() }
        val bottomInsetPx = with(density) { insets.getBottom(density).toFloat() }
        val startInsetPx = with(density) { insets.getLeft(density, LayoutDirection.Ltr).toFloat() }
        val endInsetPx = with(density) { insets.getRight(density, LayoutDirection.Ltr).toFloat() }

        // Compose-space resting top-left (the policy Offset is a plain holder).
        val restingOffset = Offset(restingPos.x, restingPos.y)

        LaunchedEffect(restingPos.x, restingPos.y, waitForMeasure) {
            // Wait for the first real measure so the bar is placed with its
            // true size — no fly-in from the top-left, no initial misplacement.
            if (waitForMeasure) return@LaunchedEffect
            if (!initialized) {
                initialized = true
                snapAnim.snapTo(restingOffset)
            } else if (!isDragging) {
                snapAnim.snapTo(snapAnim.value)
                if (reduceMotion) {
                    snapAnim.snapTo(restingOffset)
                } else {
                    snapAnim.animateTo(
                        targetValue = restingOffset,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                            visibilityThreshold = Offset(1f, 1f)
                        )
                    )
                }
            }
        }

        val displayPos = if (isDragging) rawPos else snapAnim.value
        val displayTool = if (currentTool == StrokeTool.PAN || currentTool == StrokeTool.SELECT) {
            lastDrawingTool
        } else {
            currentTool
        }
        val isPanActive = currentTool == StrokeTool.PAN || currentTool == StrokeTool.SELECT

        Box(
            modifier = Modifier
                .offset { IntOffset(displayPos.x.roundToInt(), displayPos.y.roundToInt()) }
                .onSizeChanged { size ->
                    if (!isDragging) { dockW = size.width.toFloat(); dockH = size.height.toFloat() }
                    if (waitForMeasure) waitForMeasure = false
                }
                .pointerInput(draggable, screenW, screenH, dockW, dockH) {
                    if (!FloatingWidgetDragPolicy.mayDrag(draggable)) return@pointerInput
                    detectDragGestures(
                        onDragStart = {
                            rawPos = snapAnim.value
                            isDragging = true
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val constrained = FloatingWidgetDragPolicy.constrainWithinSafeArea(
                                rawPos.x + amount.x, rawPos.y + amount.y, screenW, screenH,
                                dockW, dockH, topInsetPx, bottomInsetPx, startInsetPx, endInsetPx
                            )
                            rawPos = Offset(constrained.x, constrained.y)
                        },
                        onDragEnd = {
                            if (FloatingWidgetDragPolicy.maySnapToEdge(snapToEdgeEnabled)) {
                                val centre = Offset(rawPos.x + dockW / 2f, rawPos.y + dockH / 2f)
                                val anchor = DockSnapMath.snap(
                                    centre = DockSnapMath.Offset(centre.x, centre.y),
                                    screenW = screenW,
                                    screenH = screenH,
                                    marginPx = with(density) { 16.dp.toPx() },
                                    dockW = dockW,
                                    dockH = dockH
                                )
                                val target = Offset(anchor.x, anchor.y)
                                isDragging = false
                                scope.launch {
                                    snapAnim.snapTo(rawPos)
                                    if (reduceMotion) {
                                        snapAnim.snapTo(target)
                                    } else {
                                        snapAnim.animateTo(
                                            targetValue = target,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium,
                                                visibilityThreshold = Offset(1f, 1f)
                                            )
                                        )
                                    }
                                    onChangeDraggedOffset(target.x, target.y)
                                }
                            } else {
                                isDragging = false
                                scope.launch {
                                    snapAnim.snapTo(rawPos)
                                    onChangeDraggedOffset(rawPos.x, rawPos.y)
                                }
                            }
                        },
                        onDragCancel = { isDragging = false }
                    )
                }
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                if (!horizontalPosture) {
                    Column(
                        modifier = Modifier
                            .width(56.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AnimatedVisibility(
                            visible = expanded,
                            enter = com.authorss81.noteflow.theme.MotionSystem.enter(
                                fadeIn() + androidx.compose.animation.expandVertically()
                            ),
                            exit = com.authorss81.noteflow.theme.MotionSystem.exit(
                                fadeOut() + androidx.compose.animation.shrinkVertically()
                            )
                        ) {
                            DockQuickToolsColumn(
                                currentTool = currentTool,
                                onQuickTool = onQuickTool,
                                onTogglePan = onTogglePan,
                                expanded = expanded,
                                onExpandToggled = { expanded = !expanded }
                            )
                        }
                        InkBarLandscapeBar(
                            displayTool = displayTool,
                            toolbarState = toolbarState,
                            currentColor = currentColor,
                            currentWidth = currentWidth,
                            isPanActive = isPanActive,
                            expanded = expanded,
                            onToolClick = onToolClick,
                            onTogglePan = onTogglePan,
                            onColorClick = onColorClick,
                            onWidthClick = onWidthClick,
                            onSettingsClick = onSettingsClick,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onExpandToggled = { expanded = !expanded }
                        )
                    }
                } else {
                    Column {
                        InkBarPortraitBar(
                            displayTool = displayTool,
                            toolbarState = toolbarState,
                            currentColor = currentColor,
                            currentWidth = currentWidth,
                            isPanActive = isPanActive,
                            onToolClick = onToolClick,
                            onTogglePan = onTogglePan,
                            onColorClick = onColorClick,
                            onWidthClick = onWidthClick,
                            onSettingsClick = onSettingsClick,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            expanded = expanded,
                            onExpandToggled = { expanded = !expanded }
                        )
                        AnimatedVisibility(
                            visible = expanded,
                            enter = com.authorss81.noteflow.theme.MotionSystem.enter(
                                fadeIn() + androidx.compose.animation.expandVertically()
                            ),
                            exit = com.authorss81.noteflow.theme.MotionSystem.exit(
                                fadeOut() + androidx.compose.animation.shrinkVertically()
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .horizontalScroll(rememberScrollState()),
                                contentAlignment = Alignment.Center
                            ) {
                                DockQuickToolsRow(
                                    currentTool = currentTool,
                                    onQuickTool = onQuickTool,
                                    onTogglePan = onTogglePan,
                                    expanded = expanded,
                                    onExpandToggled = { expanded = !expanded }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InkBarPortraitBar(
    displayTool: StrokeTool,
    toolbarState: FloatingToolbarState,
    currentColor: Color,
    currentWidth: Float,
    isPanActive: Boolean,
    onToolClick: () -> Unit,
    onTogglePan: () -> Unit,
    onColorClick: () -> Unit,
    onWidthClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    expanded: Boolean,
    onExpandToggled: () -> Unit
) {
    // Phase 129: the restored pre-35 horizontal capsule — 56dp tall, inner Row
    // with horizontalScroll, padding 8/4, spacedBy 4.
    Row(
        modifier = Modifier
            .height(56.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1. Tool selector button — displayTool icon + label
        Surface(
            onClick = onToolClick,
            shape = RoundedCornerShape(20.dp),
            color = if (toolbarState == FloatingToolbarState.TOOL_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.minimumInteractiveComponentSize()
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

        // 2. Scroll / Pan Canvas toggle button
        Surface(
            onClick = onTogglePan,
            shape = RoundedCornerShape(20.dp),
            color = if (isPanActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.minimumInteractiveComponentSize()
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

        // 3. Color swatch button
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

        // 4. Width badge button
        Surface(
            onClick = onWidthClick,
            shape = RoundedCornerShape(20.dp),
            color = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.minimumInteractiveComponentSize()
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

        // 5. VerticalDivider
        VerticalDivider(
            modifier = Modifier.height(24.dp).padding(horizontal = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // 6. Canvas settings button
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = "Canvas & Paper Settings",
                tint = if (toolbarState == FloatingToolbarState.SETTINGS_MENU) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 7. Undo / Redo
        IconButton(onClick = onUndo, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRedo, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Phase 35 extra kept: quick-tool rail expand chevron (additive; no
        // parity item is replaced by it).
        IconButton(onClick = onExpandToggled, modifier = Modifier.size(36.dp)) {
            Icon(
                if (expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                contentDescription = if (expanded) "Collapse Quick Tools" else "Expand Quick Tools",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InkBarLandscapeBar(
    displayTool: StrokeTool,
    toolbarState: FloatingToolbarState,
    currentColor: Color,
    currentWidth: Float,
    isPanActive: Boolean,
    onToolClick: () -> Unit,
    onTogglePan: () -> Unit,
    onColorClick: () -> Unit,
    onWidthClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    expanded: Boolean,
    onExpandToggled: () -> Unit
) {
    // Phase 129: the restored pre-35 landscape side column — 56dp wide, inner
    // Column with padding 4/8, spacedBy 6, HorizontalDivider before settings.
    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Tool selector button — displayTool icon
        Surface(
            onClick = onToolClick,
            shape = RoundedCornerShape(20.dp),
            color = if (toolbarState == FloatingToolbarState.TOOL_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = getToolIcon(displayTool),
                    contentDescription = displayTool.label,
                    tint = if (toolbarState == FloatingToolbarState.TOOL_PICKER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 2. Scroll / Pan Canvas toggle button
        Surface(
            onClick = onTogglePan,
            shape = RoundedCornerShape(20.dp),
            color = if (isPanActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.PanTool,
                    contentDescription = "Scroll / Pan Canvas",
                    tint = if (isPanActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 3. Color swatch button
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

        // 4. Width badge button
        Surface(
            onClick = onWidthClick,
            shape = RoundedCornerShape(20.dp),
            color = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.LineWeight,
                    contentDescription = "Stroke Width",
                    tint = if (toolbarState == FloatingToolbarState.WIDTH_PICKER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 5. HorizontalDivider
        HorizontalDivider(
            modifier = Modifier.width(24.dp).padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // 6. Canvas settings button
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = "Canvas & Paper Settings",
                tint = if (toolbarState == FloatingToolbarState.SETTINGS_MENU) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 7. Undo / Redo
        IconButton(onClick = onUndo, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRedo, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Phase 35 extra kept: quick-tool rail expand chevron (additive; no
        // parity item is replaced by it).
        IconButton(onClick = onExpandToggled, modifier = Modifier.size(36.dp)) {
            Icon(
                if (expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                contentDescription = if (expanded) "Collapse Quick Tools" else "Expand Quick Tools",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DockQuickToolsRow(
    currentTool: StrokeTool,
    onQuickTool: (StrokeTool) -> Unit,
    onTogglePan: () -> Unit,
    expanded: Boolean,
    onExpandToggled: () -> Unit
) {
    val quickTools = listOf(
        StrokeTool.ERASER,
        StrokeTool.TEXT,
        StrokeTool.STICKER,
        StrokeTool.RECTANGLE,
        StrokeTool.ARROW,
        StrokeTool.LINE,
        StrokeTool.EYEDROPPER
    )
    Row(
        modifier = Modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (tool in quickTools) {
            DockToolButton(
                onClick = { onQuickTool(tool) },
                selected = currentTool == tool,
                content = {
                    Icon(
                        imageVector = getToolIcon(tool),
                        contentDescription = tool.label,
                        tint = if (currentTool == tool) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun DockQuickToolsColumn(
    currentTool: StrokeTool,
    onQuickTool: (StrokeTool) -> Unit,
    onTogglePan: () -> Unit,
    expanded: Boolean,
    onExpandToggled: () -> Unit
) {
    val quickTools = listOf(
        StrokeTool.ERASER,
        StrokeTool.TEXT,
        StrokeTool.STICKER,
        StrokeTool.RECTANGLE,
        StrokeTool.ARROW,
        StrokeTool.LINE,
        StrokeTool.EYEDROPPER
    )
    Column(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (tool in quickTools) {
            DockToolButton(
                onClick = { onQuickTool(tool) },
                selected = currentTool == tool,
                content = {
                    Icon(
                        imageVector = getToolIcon(tool),
                        contentDescription = tool.label,
                        tint = if (currentTool == tool) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun DockToolButton(
    onClick: () -> Unit,
    selected: Boolean,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun DockIconButton(
    onClick: () -> Unit,
    selected: Boolean,
    icon: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Box(
            modifier = Modifier.size(36.dp).minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolPickerBottomSheet(
    currentTool: StrokeTool,
    showStrokePreviews: Boolean = false,
    eraserMode: com.authorss81.noteflow.services.EraserMode = com.authorss81.noteflow.services.EraserMode.STROKE,
    onEraserModeChange: (com.authorss81.noteflow.services.EraserMode) -> Unit = {},
    onToolSelect: (StrokeTool) -> Unit,
    onDismiss: () -> Unit,
    onSnackbar: (String, Boolean) -> Unit = { _, _ -> }
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current
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

            // Phase 19: eraser sub-menu — shown while the ERASER is active so the
            // whole-stroke vs partial-erase choice is always reachable, not dead UI.
            if (currentTool == StrokeTool.ERASER) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Eraser Mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.authorss81.noteflow.services.EraserMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = eraserMode == mode,
                                    onClick = { onEraserModeChange(mode) },
                                    label = {
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Whole Stroke removes anything the eraser touches. " +
                                "Partial trims freehand strokes into segments; text & shapes are removed whole.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            val wetToolsList = StrokeTool.entries.filter { com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(it) }
            val standardTools = StrokeTool.entries.filter { it !in wetToolsList }
            val gpuTools = wetToolsList
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
                            if (!reduceMotion) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onToolSelect(tool)
                            // Phase 19: stay open for the ERASER so the mode chips
                            // (whole-stroke vs partial) are immediately visible.
                            if (tool != StrokeTool.ERASER) onDismiss()
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

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(gpuTools) { tool ->
                    val selected = tool == currentTool
                    val isUnsupported = !com.authorss81.noteflow.ui.components.ShaderCapabilityHelper.isAgslSupported

                    Surface(
                        onClick = {
                            if (!reduceMotion) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            if (isUnsupported && !hasShownShaderWarning) {
                                hasShownShaderWarning = true
                                onSnackbar(
                                    "Real-time wet blending requires Android 13+ — using soft-blend watercolor fallback on this device.",
                                    true
                                )
                            }
                            onToolSelect(tool)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary) else null,
                        modifier = Modifier.width(112.dp)
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
    currentColorMode: StrokeColorMode = StrokeColorMode.SOLID,
    currentGradientToColor: Color = currentColor,
    // Phase 172: PERSISTED recent + favorite colors drive the picker rows (the
    // StateFlows are seeded from SettingsManager — never a blocking prefs read).
    // onRecordRecent fires on EXPLICIT picks only (swatch taps), so HSV slider
    // drags can't flood the recents list with every intermediate tone.
    recentColors: List<Color> = emptyList(),
    favoriteColors: List<Color> = emptyList(),
    onToggleFavorite: (Color) -> Unit = {},
    onRecordRecent: (Color) -> Unit = {},
    onColorSelect: (Color) -> Unit,
    onColorModeChange: (StrokeColorMode, Color, Color?) -> Unit = { _, _, _ -> },
    onGradientToColorSelect: (Color) -> Unit = {},
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
                .verticalScroll(rememberScrollState())
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "#%06X".format(java.util.Locale.US, currentColor.toArgb() and 0xFFFFFF),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(currentColor)
                            .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Phase 35: curated designer palette selector (Vibrant + 4 studio
            // palettes). Switching the palette swaps the family sections below.
            var selectedPaletteName by remember { mutableStateOf("vibrant") }
            Text(
                text = "Palette Studio",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val paletteNames = listOf(
                    "vibrant" to "Vibrant",
                    "nordic" to "Nordic",
                    "botanical" to "Botanical",
                    "cyberpunk" to "Cyberpunk",
                    "terra" to "Terracotta"
                )
                paletteNames.forEach { (name, label) ->
                    FilterChip(
                        selected = selectedPaletteName == name,
                        onClick = { selectedPaletteName = name },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        com.authorss81.noteflow.services.DesignerPalettes.swatchesFor(name).firstOrNull()?.let { Color(it.argb) }
                                            ?: MaterialTheme.colorScheme.primary
                                    )
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Phase 19: scrollable, organized color picker — HSV panel (advanced
            // mode only), recent colors, curated family sections, saved swatches.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 430.dp)
            ) {
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
                                onColorSelect(Color(android.graphics.Color.HSVToColor(floatArrayOf(it, s, v))))
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
                                onColorSelect(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, it, v))))
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
                                onColorSelect(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, it))))
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

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Curated families: static, organized, deduped sections. Phase 35: sections
                // come from the active designer palette ("vibrant" = the classic
                // vibrant catalog) but are still grouped in enum family order.
                val familySections = remember(selectedPaletteName) {
                    val swatches = com.authorss81.noteflow.services.DesignerPalettes.swatchesFor(selectedPaletteName)
                    val byFamily = swatches.groupBy { it.family }
                    com.authorss81.noteflow.services.ColorFamily.entries.mapNotNull { family ->
                        byFamily[family]?.let { family to it }
                    }
                }
                val paletteArgbSet = remember(selectedPaletteName) {
                    com.authorss81.noteflow.services.DesignerPalettes.swatchesFor(selectedPaletteName).map { it.argb }.toSet()
                }
                val catalogArgbSet = paletteArgbSet
                // Phase 172: the recents row is PERSISTED (StateFlow from SettingsManager),
                // then padded with this-session custom/sampled colors (the old in-memory
                // extras) so a freshly-sampled eyedropper color still lands in the row.
                val displayedRecents = remember(recentColors, palette, selectedPaletteName) {
                    val recentArgb = recentColors.map { it.toArgb() }.toSet()
                    val sessionExtras = palette.filter { it.toArgb() !in catalogArgbSet && it.toArgb() !in recentArgb }.asReversed()
                    (recentColors + sessionExtras).take(com.authorss81.noteflow.services.ColorRecentsPolicy.MAX_RECENT_COLORS)
                }
                val favoriteArgbSet = remember(favoriteColors) { favoriteColors.map { it.toArgb() }.toSet() }

                // Favorites (persisted, bounded): the star toggles the CURRENT color;
                // tapping a swatch picks + records it.
                if (favoriteColors.isNotEmpty() || favoriteArgbSet.contains(currentColor.toArgb())) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Favorites",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { onToggleFavorite(currentColor) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = if (favoriteArgbSet.contains(currentColor.toArgb())) {
                                    Icons.Outlined.Star
                                } else {
                                    Icons.Outlined.StarBorder
                                },
                                contentDescription = if (favoriteArgbSet.contains(currentColor.toArgb())) {
                                    "Remove current color from Favorites"
                                } else {
                                    "Add current color to Favorites"
                                },
                                tint = if (favoriteArgbSet.contains(currentColor.toArgb())) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(favoriteColors) { color ->
                            ColorSwatch(
                                color = color,
                                isSelected = currentColor.toArgb() == color.toArgb(),
                                size = 38.dp,
                                onClick = {
                                    onRecordRecent(color)
                                    onColorSelect(color)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (displayedRecents.isNotEmpty()) {
                    Text(
                        text = "Recent Colors",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(displayedRecents) { color ->
                            ColorSwatch(
                                color = color,
                                isSelected = currentColor.toArgb() == color.toArgb(),
                                size = 38.dp,
                                onClick = {
                                    onRecordRecent(color)
                                    onColorSelect(color)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }

                familySections.forEach { (family, swatches) ->
                    Text(
                        text = family.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(swatches) { swatch ->
                            ColorSwatch(
                                color = Color(swatch.argb),
                                isSelected = currentColor.toArgb() == swatch.argb,
                                size = 34.dp,
                                onClick = {
                                    onRecordRecent(Color(swatch.argb))
                                    onColorSelect(Color(swatch.argb))
                                }
                            )
                        }
                    }
Spacer(modifier = Modifier.height(16.dp))

            // Phase 27 + 122: multi-color brush mode chips (shared composable so
            // the width/quick picker exposes the same row). Selecting
            // RAINBOW/SHIMMER keeps the current base color (rainbow/shimmer
            // re-derive hue/value from it), GRADIENT additionally shows a
            // second-color row below. The mode persists via SettingsManager.
            com.authorss81.noteflow.ui.components.ColorModeChipsRow(
                currentColorMode = currentColorMode,
                currentColor = currentColor,
                currentGradientToColor = currentGradientToColor,
                onColorModeChange = onColorModeChange,
                onGradientToColorSelect = onGradientToColorSelect
            )

            Spacer(modifier = Modifier.height(8.dp))
                }

                if (savedSwatches.isNotEmpty()) {
                    Text(
                        text = "Saved Swatches",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(savedSwatches) { swatch ->
                            val swatchColor = Color(swatch.colorInt)
                            val isSelected = currentColor.toArgb() == swatch.colorInt
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                onRecordRecent(swatchColor)
                                onColorSelect(swatchColor)
                            },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = "Selected",
                                        tint = if (swatchColor.luminance() > 0.5f) Color.Black else Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
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
                            }
                        }
                    }
                }
            }
            // Phase 07: deterministic color-harmony swatches (HSL rotation math).
            Spacer(modifier = Modifier.height(20.dp))
            HarmonySwatchesRow(
                sourceColor = currentColor,
                onColorSelect = onColorSelect
            )

            // Phase 35: harmonic contrast studio — complementary + analogous
            // suggestions with their mathematically computed WCAG ratio vs a
            // white paper background, so the user can pick a legible pair.
            Spacer(modifier = Modifier.height(20.dp))
            ContrastSuggestionsRow(
                sourceColor = currentColor,
                onColorSelect = onColorSelect
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Phase 35: complementary + analogous suggestions with WCAG contrast labels.
 */
@Composable
private fun ContrastSuggestionsRow(
    sourceColor: Color,
    onColorSelect: (Color) -> Unit
) {
    val sourceRgb = com.authorss81.noteflow.services.ColorHarmonyHelper.Rgb(
        android.graphics.Color.red(sourceColor.toArgb()).toFloat(),
        android.graphics.Color.green(sourceColor.toArgb()).toFloat(),
        android.graphics.Color.blue(sourceColor.toArgb()).toFloat()
    )
    val background = com.authorss81.noteflow.services.ColorHarmonyHelper.Rgb(255f, 255f, 255f)
    val suggestions = remember(sourceRgb.r, sourceRgb.g, sourceRgb.b) {
        com.authorss81.noteflow.services.HarmonicContrastMath.suggestions(sourceRgb, background)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Contrast Studio",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "WCAG ratio vs white",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        suggestions.forEach { s ->
            val argb = android.graphics.Color.rgb(
                s.color.r.toInt().coerceIn(0, 255),
                s.color.g.toInt().coerceIn(0, 255),
                s.color.b.toInt().coerceIn(0, 255)
            )
            val c = Color(argb)
            val ratioLabel = "%.2f:1".format(java.util.Locale.US, s.ratio)
            val badge = when {
                s.ratio >= 7f -> "AAA"
                s.ratio >= 4.5f -> "AA"
                else -> "—"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onColorSelect(c) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                Text(
                    text = s.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        s.ratio >= 7f -> MaterialTheme.colorScheme.primaryContainer
                        s.ratio >= 4.5f -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        text = "$ratioLabel $badge".trim(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Phase 19: reusable color swatch circle used across the organized palette
 * (recent colors + curator families). Selected swatches get a check overlay.
 */
@Composable
private fun ColorSwatch(
    color: Color,
    isSelected: Boolean,
    size: Dp,
    onClick: () -> Unit
) {
    // 36.0: color-picker detent — a subtle tick when a swatch is chosen, gated by
    // the merged haptics + reduce-motion policy.
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val gate = com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(
        com.authorss81.noteflow.theme.LocalHapticsEnabled.current,
        com.authorss81.noteflow.theme.LocalReduceMotion.current
    )
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .clickable {
                if (gate) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Color-harmony swatch rows derived from [sourceColor] via deterministic HSL
 * rotation (ColorHarmonyHelper). Selecting a swatch just picks that color —
 * the sheet stays open so the user can audition several harmonies.
 */
@Composable
private fun HarmonySwatchesRow(
    sourceColor: Color,
    onColorSelect: (Color) -> Unit
) {
    val sourceRgb = com.authorss81.noteflow.services.ColorHarmonyHelper.Rgb(
        android.graphics.Color.red(sourceColor.toArgb()).toFloat(),
        android.graphics.Color.green(sourceColor.toArgb()).toFloat(),
        android.graphics.Color.blue(sourceColor.toArgb()).toFloat()
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Color Harmonies",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        HarmonyScheme.entries.forEach { scheme ->
            val swatches = remember(sourceRgb.r, sourceRgb.g, sourceRgb.b, scheme) {
                com.authorss81.noteflow.services.ColorHarmonyHelper.generate(sourceRgb, scheme)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = scheme.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(96.dp)
                )
                swatches.forEach { sw ->
                    val argb = android.graphics.Color.rgb(sw.r.toInt().coerceIn(0, 255), sw.g.toInt().coerceIn(0, 255), sw.b.toInt().coerceIn(0, 255))
                    val c = Color(argb)
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onColorSelect(c) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
    currentColorMode: StrokeColorMode = StrokeColorMode.SOLID,
    currentGradientToColor: Color = currentColor,
    onColorModeChange: (StrokeColorMode, Color, Color?) -> Unit = { _, _, _ -> },
    onGradientToColorSelect: (Color) -> Unit = {},
    onWidthSelect: (Float) -> Unit,
    onPresetSelect: (PenPreset) -> Unit,
    onCustomPresetSelect: (PaletteItemEntity) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newPresetName by remember { mutableStateOf("") }
    // 36.0: slider-notch haptics — a subtle tick whenever the width crosses a whole
    // point, gated by the merged haptics + reduce-motion policy.
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val notchGate = com.authorss81.noteflow.services.MotionPolicy.hapticsAllowed(
        com.authorss81.noteflow.theme.LocalHapticsEnabled.current,
        com.authorss81.noteflow.theme.LocalReduceMotion.current
    )
    var lastWidthNotch by remember { androidx.compose.runtime.mutableFloatStateOf(currentWidth) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Stroke Width & Presets",
                style = MaterialTheme.typography.titleLarge
            )

            // Phase 122: the same colour-MODE chips row as the colour picker, so the
            // rainbow brush is reachable from the width/quick picker without opening
            // the full colour sheet. Selecting a mode persists via SettingsManager.
            Spacer(modifier = Modifier.height(12.dp))
            com.authorss81.noteflow.ui.components.ColorModeChipsRow(
                currentColorMode = currentColorMode,
                currentColor = currentColor,
                currentGradientToColor = currentGradientToColor,
                onColorModeChange = onColorModeChange,
                onGradientToColorSelect = onGradientToColorSelect
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
                                        Text("×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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

            // Phase 35: live nib preview — the palette selector shows real-time
            // pressure / tilt / wetness response on the stroke (NibPreviewMath),
            // updating as the sliders below are dragged. Values are local to the
            // sheet; real strokes keep sampling actual stylus input.
            var livePressure by remember { mutableFloatStateOf(0.62f) }
            var liveTilt by remember { mutableFloatStateOf(18f) }
            var liveWetness by remember { mutableFloatStateOf(0.25f) }

            Text(
                text = "Live Nib Preview",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.85f))
                    .border(0.5.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                com.authorss81.noteflow.ui.components.PenNibVisualPreview(
                    tool = currentTool,
                    color = currentColor,
                    width = currentWidth,
                    pressure = livePressure,
                    tiltDeg = liveTilt,
                    wetness = liveWetness,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Force ${(livePressure * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Tilt ${liveTilt.toInt()}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Wetness ${(liveWetness * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = livePressure,
                onValueChange = { livePressure = it },
                valueRange = 0.05f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            Slider(
                value = liveTilt,
                onValueChange = { liveTilt = it },
                valueRange = 0f..75f,
                modifier = Modifier.fillMaxWidth()
            )
            Slider(
                value = liveWetness,
                onValueChange = { liveWetness = it },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Thickness: ${currentWidth.toInt()}pt",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = currentWidth,
                onValueChange = { w ->
                    if (notchGate && com.authorss81.noteflow.services.MotionPolicy.sliderNotchTriggered(lastWidthNotch, w, granularity = 1f)) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    }
                    lastWidthNotch = w
                    onWidthSelect(w)
                },
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
    minimapDraggable: Boolean = false,
    onMinimapDraggableToggle: () -> Unit = {},
    inkBarDraggable: Boolean = false,
    onInkBarDraggableToggle: () -> Unit = {},
    inkBarSnapToEdgeEnabled: Boolean = false,
    onInkBarSnapToEdgeToggle: () -> Unit = {},
    inkBarDockPersistEnabled: Boolean = false,
    onInkBarDockPersistToggle: () -> Unit = {},
    zoomScale: Float,
    panOffset: Offset,
    isPdf: Boolean,
    gpuWetBrushesEnabled: Boolean = true,
    onGpuWetBrushesToggle: (Boolean) -> Unit = {},
    shapeAutoSnapEnabled: Boolean = false,
    onShapeAutoSnapToggle: (Boolean) -> Unit = {},
    hapticsEnabled: Boolean = true,
    onHapticsToggle: (Boolean) -> Unit = {},
    inkToShapeAvailable: Boolean = true,
    inkToShapeKeepOriginal: Boolean = false,
    onInkToShapeKeepOriginalChange: (Boolean) -> Unit = {},
    onConvertToShape: () -> Unit = {},
    stabilizerEnabled: Boolean = false,
    onStabilizerToggle: (Boolean) -> Unit = {},
    pressureCurve: PressureCurve = PressureCurve.LINEAR,
    onPressureCurveSelect: (PressureCurve) -> Unit = {},
    symmetryMode: SymmetryMode = SymmetryMode.OFF,
    onSymmetryModeSelect: (SymmetryMode) -> Unit = {},
    paperTexturePath: String? = null,
    onUploadPaperTexture: () -> Unit = {},
    onClearPaperTexture: () -> Unit = {},
    onContinuousModeToggle: () -> Unit,
    onDividePagesToggle: () -> Unit,
    onTemplateSelect: (String) -> Unit,
    onPaperColorSelect: (String) -> Unit,
    onUploadCustomBg: () -> Unit = {},
    onMinimapToggle: () -> Unit,
    onResetZoomPan: () -> Unit,
    onInsertPageBefore: () -> Unit,
    onInsertPageAfter: () -> Unit,
    activeBrushPresetName: String? = null,
    onOpenBrushPresets: () -> Unit = {},
    twoFingerUndoRedoEnabled: Boolean = false,
    onTwoFingerUndoRedoToggle: (Boolean) -> Unit = {},
    quickColorRingEnabled: Boolean = false,
    onQuickColorRingToggle: (Boolean) -> Unit = {},
    vibrancyEnabled: Boolean = false,
    onVibrancyToggle: (Boolean) -> Unit = {},
    vibrancyBoostLevel: Float = 0.4f,
    onVibrancyBoostChange: (Float) -> Unit = {},
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

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onUploadPaperTexture,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.Texture, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Upload Paper Texture")
                }
                if (paperTexturePath != null) {
                    Button(
                        onClick = onClearPaperTexture,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear")
                    }
                }
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

            // Phase 129: movable-widget opt-ins (all default OFF). The restored
            // horizontal ink bar and the minimap are draggable only when enabled;
            // snap-to-edge and cross-session dock persistence are separate extras.
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Draggable Minimap", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Reposition the minimap anywhere on the canvas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = minimapDraggable, enabled = showMinimap, onCheckedChange = { onMinimapDraggableToggle() })
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Draggable Ink Bar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Reposition the floating tool bar anywhere on the canvas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = inkBarDraggable, onCheckedChange = { onInkBarDraggableToggle() })
            }
            if (inkBarDraggable) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Snap Ink Bar to Edge", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Spring to the nearest screen edge on release",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = inkBarSnapToEdgeEnabled, onCheckedChange = { onInkBarSnapToEdgeToggle() })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Remember Ink Bar Position", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Keep the bar's position across sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = inkBarDockPersistEnabled, onCheckedChange = { onInkBarDockPersistToggle() })
                }
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

            // Phase 155: two-finger undo/redo gesture shortcuts (OFF by default;
            // the classifier never consumes, so pinch-zoom stays intact).
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Two-Finger Undo/Redo", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Swipe left/right or double-tap with two fingers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = twoFingerUndoRedoEnabled,
                    onCheckedChange = onTwoFingerUndoRedoToggle
                )
            }

            // Phase 155: long-press quick-color ring (OFF by default; instant per
            // reduce-motion — no animation tween).
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Quick-Color Ring", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Long-press the canvas to pick a color from the ring",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = quickColorRingEnabled,
                    onCheckedChange = onQuickColorRingToggle
                )
            }

            // 36.0: haptics master toggle (gesture-milestone ticks — shape snap,
            // color detents, slider notches). Still gated by reduce-motion.
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Haptic Feedback", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Subtle ticks on shape snap, color swatches & slider notches",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = hapticsEnabled,
                    onCheckedChange = onHapticsToggle
                )
            }

            // Phase 25: Ink → Shape — explicit on-demand convert through the
            // plugin framework (distinct from auto-snap above). Grayed out with
            // an enable-hint while the plugin is off/unavailable.
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Polyline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Ink → Shape", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (inkToShapeAvailable) {
                                        "Convert the latest freehand stroke to a clean shape"
                                    } else {
                                        "Unavailable — enable Ink to Shape in Plugins"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Button(
                        onClick = onConvertToShape,
                        enabled = inkToShapeAvailable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Convert to Shape")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep original stroke", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (inkToShapeKeepOriginal) "Shape inserted alongside the ink" else "Shape replaces the ink (undoable)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = inkToShapeKeepOriginal,
                            enabled = inkToShapeAvailable,
                            onCheckedChange = onInkToShapeKeepOriginalChange
                        )
                    }
                }
            }

            // Phase 19: render-time vibrancy — richer, more saturated colors.
            // OFF by default; stored color values are never modified.
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Vibrancy", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Saturation lift at render time — saved colors stay true",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = vibrancyEnabled,
                            onCheckedChange = onVibrancyToggle
                        )
                    }
                    if (vibrancyEnabled) {
                        Text(
                            text = "Boost: ${(vibrancyBoostLevel * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = vibrancyBoostLevel,
                            onValueChange = onVibrancyBoostChange,
                            valueRange = 0f..1f,
                            steps = 9
                        )
                        Text(
                            text = "Works on all brush types (vector fallback included) and the AGSL wet shader.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Phase 13: Ready-made brush presets (WetBrushEngine params). Only
            // offered on AGSL-capable devices — matches the GPU wet-brush toggle.
            if (com.authorss81.noteflow.ui.components.ShaderCapabilityHelper.isAgslSupported) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Ready-made Presets", style = MaterialTheme.typography.titleMedium)
                            Text(
                                activeBrushPresetName
                                    ?: "Watercolor, oil, chalk & more — one-tap brush setups",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    androidx.compose.material3.OutlinedButton(onClick = onOpenBrushPresets) {
                        Text(if (activeBrushPresetName != null) "Change" else "Browse")
                    }
                }
            }

            // Phase 07: Painting Assist section.
            Text(
                text = "Painting Assist",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Stroke Stabilizer toggle (rolling-window EWMA smoothing).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Waves, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Stroke Stabilizer", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Smooth pen jitter for cleaner strokes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = stabilizerEnabled,
                            onCheckedChange = onStabilizerToggle
                        )
                    }

                    // Pressure response curve selector.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Pressure Curve", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Tune stylus pressure feel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PressureCurve.entries.forEach { curve ->
                            FilterChip(
                                selected = pressureCurve == curve,
                                onClick = { onPressureCurveSelect(curve) },
                                label = { Text(curve.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                            if (curve != PressureCurve.entries.last()) {
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }
                    }

                    // Symmetry / mirror mode selector.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Flip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Symmetry", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Mirror strokes live while painting",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SymmetryMode.entries.forEach { mode ->
                            FilterChip(
                                selected = symmetryMode == mode,
                                onClick = { onSymmetryModeSelect(mode) },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                            if (mode != SymmetryMode.entries.last()) {
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }
                    }
                }
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
        StrokeTool.STICKER -> Icons.Outlined.EmojiEmotions
        StrokeTool.CHARCOAL -> Icons.Outlined.Brush
        StrokeTool.OIL_PASTEL -> Icons.Outlined.Palette
        StrokeTool.INK_WASH -> Icons.Outlined.InvertColors
        StrokeTool.GOUACHE -> Icons.Outlined.FormatColorFill
        StrokeTool.DRY_BRUSH -> Icons.Outlined.Grass
        StrokeTool.PALETTE_KNIFE -> Icons.Outlined.ContentCut
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

/**
 * Resolves the real file extension for a picked paper-texture image (from the
 * content provider's display name) so we do not mislabel JPEG/WebP bytes as PNG.
 */
private fun paperTextureExtensionFromUri(context: android.content.Context, uri: Uri): String {
    var ext: String? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    cursor.getString(idx)?.let { name ->
                        ext = name.substringAfterLast('.', "").lowercase()
                    }
                }
            }
        }
    }
    return ext?.takeIf { it.isNotBlank() } ?: "png"
}

/**
 * Deletes a stored paper-texture file (used when the texture is replaced or
 * cleared so internal storage does not accumulate orphans).
 */
private fun deletePaperTextureFile(path: String?) {
    if (path.isNullOrBlank()) return
    val file = File(path)
    if (file.name.startsWith("paper_texture_")) {
        runCatching { file.delete() }
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
                                        modifier = Modifier.minimumInteractiveComponentSize()
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
                                        modifier = Modifier.minimumInteractiveComponentSize()
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
                                            onDismissRequest = { showBlendMenu = false },
                                            scrollState = overflowMenuScrollState(),
                                            modifier = overflowMenuScrollModifier()
                                        ) {
                                            val blendModes = com.authorss81.noteflow.services.LayerBlendPresetPolicy.RENDERER_SUPPORTED_MODES
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

                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { onMoveUp(layer) },
                                            enabled = index > 0,
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Outlined.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(14.dp))
                                        }

                                        IconButton(
                                            onClick = { onMoveDown(layer) },
                                            enabled = index < sortedLayers.size - 1,
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Outlined.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(14.dp))
                                        }

                                        IconButton(
                                            onClick = { onDuplicateLayer(layer) },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(14.dp))
                                        }

                                        val isBottomLayer = index == sortedLayers.size - 1
                                        IconButton(
                                            onClick = { onMergeDown(layer) },
                                            enabled = !isBottomLayer,
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Outlined.MergeType, contentDescription = "Merge Down", modifier = Modifier.size(14.dp))
                                        }

                                        val isOnlyLayer = sortedLayers.size == 1
                                        IconButton(
                                            onClick = { onDeleteLayer(layer.id) },
                                            enabled = !isOnlyLayer,
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = if (isOnlyLayer) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }

                                // Phase 172: compact blend-mode QUICK PRESETS rendered as
                                // labelled chips (normal/multiply/screen/overlay/soft-light).
                                // The full 12-mode set stays in the dropdown above; every
                                // change flows through the SAME onUpdateLayer →
                                // handleLayersChange → saveLayersGated persistence path, so
                                // opacity/blend survive by the field already on LayerEntity.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    com.authorss81.noteflow.services.LayerBlendPresetPolicy.presets().forEach { preset ->
                                        FilterChip(
                                            selected = layer.blendMode.equals(preset.key, ignoreCase = true),
                                            onClick = { onUpdateLayer(layer.copy(blendMode = preset.key)) },
                                            label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Phase 13: Offline emoji sticker gallery (STICKER tool)
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickerPickerBottomSheet(
    selectedStickerId: String?,
    onStickerSelect: (String) -> Unit,
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
                text = "Sticker Gallery",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Free emoji stickers — no downloads, works offline",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Phase 28: searchable + category-filterable sticker/emoji picker.
            var query by remember { mutableStateOf("") }
            var activeCategory by remember { mutableStateOf<String?>(null) }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search stickers…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = activeCategory == null,
                        onClick = { activeCategory = null },
                        label = { Text("All") }
                    )
                }
                items(com.authorss81.noteflow.services.StickerCatalog.CATEGORIES) { category ->
                    FilterChip(
                        selected = activeCategory == category,
                        onClick = { activeCategory = category },
                        label = { Text(category) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val all = com.authorss81.noteflow.services.StickerCatalog.search(query)
            val stickers = if (activeCategory == null) all
                           else all.filter { it.category == activeCategory }
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
            ) {
                items(stickers) { sticker ->
                    val selected = sticker.id == selectedStickerId
                    Surface(
                        onClick = { onStickerSelect(sticker.id) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = sticker.emoji,
                                fontSize = 30.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = sticker.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (stickers.isEmpty()) {
                    item {
                        Text(
                            text = "No stickers match \u201C$query\u201D",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Tap a sticker, then tap the canvas to place it. Drag to move, use the round handle to rotate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Phase 13: Ready-made brush preset gallery (WetBrushEngine params)
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrushPresetPickerBottomSheet(
    activePresetId: String?,
    onPresetSelect: (com.authorss81.noteflow.services.BrushPreset) -> Unit,
    onPresetClear: () -> Unit,
    onDismiss: () -> Unit,
    // Phase 155: .inkbrush import/export + the user's imported "My presets".
    importedPresets: List<com.authorss81.noteflow.services.BrushPreset> = emptyList(),
    onImportPreset: () -> Unit = {},
    onExportPreset: () -> Unit = {},
    onImportedPresetSelect: (com.authorss81.noteflow.services.BrushPreset) -> Unit = {}
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
                text = "Ready-made Presets",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Pre-filled wet-brush setups for the existing AGSL engine",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            val presets = com.authorss81.noteflow.services.BrushPresetPack.all()
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                items(presets) { preset ->
                    val selected = preset.id == activePresetId
                    Surface(
                        onClick = { onPresetSelect(preset) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = getToolIcon(preset.tool),
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = preset.tool.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (activePresetId != null) {
                TextButton(onClick = onPresetClear, modifier = Modifier.align(Alignment.Start)) {
                    Text("Clear preset (restore classic brush)")
                }
            }

            // Phase 155: .inkbrush import/export via SAF. Ship the dormant
            // ProtobufBrushLoader path safely from day one (R2-b2b3-LOG-03
            // sanitized logging carried over): reject reasons and outcomes never
            // carry file paths or names.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onImportPreset,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import .inkbrush")
                }
                Button(
                    onClick = onExportPreset,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export .inkbrush")
                }
            }

            if (importedPresets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "My presets",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Imported .inkbrush brushes — tap to apply",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(importedPresets) { preset ->
                        val selected = preset.id == activePresetId
                        Surface(
                            onClick = { onImportedPresetSelect(preset) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(
                                        imageVector = getToolIcon(preset.tool),
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = preset.tool.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
