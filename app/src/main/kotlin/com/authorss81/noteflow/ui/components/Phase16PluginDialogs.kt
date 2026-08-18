package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.plugins.DictationPlugin
import com.authorss81.noteflow.plugins.DictationSession
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.ReadAloudOutcome
import com.authorss81.noteflow.plugins.TranslationLanguage
import com.authorss81.noteflow.plugins.TranslationModelStatus
import com.authorss81.noteflow.plugins.TranslationOutcome
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Phase 16 — three keyless, on-device plugin dialogs.
//
// Every action is strictly user-initiated:
//   * Dictation starts ONLY when the dialog opens after an explicit mic tap in
//     the Plugins menu. Nothing is ever recorded ambiently.
//   * Read-aloud speaks ONLY on an explicit Play tap and refuses outright in
//     SilentToggle (quiet mode) — no bytes are spoken in quiet mode.
//   * Translation only downloads a model when the user taps Translate or
//     "Download model" (that tap is the one-time consent).

// ---------------------------------------------------------------------------
// Dictation
// ---------------------------------------------------------------------------

/**
 * Live dictation into the current note. Opening the dialog starts a recognizer
 * session; the user must tap the mic entry in the Plugins menu first (explicit,
 * no ambient recording). Partials show as a live preview; finals are folded
 * into the note text via [DictationPlugin.appendUtterance] (pure JVM).
 *
 * When on-device (offline) recognition is unavailable the dialog surfaces
 * [DictationPlugin.onDeviceAvailabilityMessage] as a one-time banner and still
 * lets the user choose to continue — never a silent network-backed stream.
 */
@Composable
fun DictationDialog(
    viewModel: NoteflowViewModel,
    context: android.content.Context,
    initialText: String,
    onTextChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<DictationStage>(DictationStage.Loading) }
    var activePlugin by remember { mutableStateOf<DictationPlugin?>(null) }
    var session by remember { mutableStateOf<DictationSession?>(null) }
    var partialText by remember { mutableStateOf("") }
    var accumulated by remember { mutableStateOf(initialText) }
    var banner by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val job = scope.launch {
            when (val result = viewModel.dictationPlugin()) {
                is PluginResult.Success -> {
                    val plugin = result.value
                    activePlugin = plugin
                    stage = DictationStage.Ready
                    if (!plugin.isOnDeviceAvailable(context)) {
                        banner = plugin.onDeviceAvailabilityMessage()
                    }
                    val activeSession = plugin.startSession(context, object :
                        com.authorss81.noteflow.plugins.DictationListener {
                        override fun onPartialUtterance(text: String) {
                            partialText = text
                        }

                        override fun onFinalUtterance(text: String) {
                            var acc = accumulated
                            val toFold = if (banner != null) {
                                val b = banner
                                banner = null
                                listOf(text)
                            } else {
                                listOf(text)
                            }
                            for (u in toFold) {
                                acc = plugin.appendUtterance(acc, u)
                            }
                            accumulated = acc
                            partialText = ""
                            onTextChanged(acc)
                        }

                        override fun onError(message: String) {
                            partialText = ""
                            stage = DictationStage.Failed(message)
                        }

                        override fun onEnd() {
                            // Recognizer ran to completion; keep the dialog open so
                            // the user can finalize with Stop.
                        }
                    })
                    session = activeSession
                }
                is PluginResult.Failure -> stage = DictationStage.Failed(result.message)
                is PluginResult.Unavailable -> stage = DictationStage.Failed(result.message)
            }
        }
        onDispose {
            job.cancel()
            session?.stop()
        }
    }

    AlertDialog(
        onDismissRequest = {
            session?.stop()
            onDismiss()
        },
        // R2-b2b1-UI-02 (phase-140): dialog over an open decrypted note — carry
        // FLAG_SECURE itself in release builds.
        properties = secureDialogProperties(),
        icon = { Icon(Icons.Outlined.Mic, contentDescription = null) },
        title = { Text("Dictate (on-device)") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (val s = stage) {
                    DictationStage.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Starting the recognizer…", style = MaterialTheme.typography.bodySmall)
                    }
                    is DictationStage.Failed -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    is DictationStage.Ready -> {
                        banner?.let { message ->
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Note: $message You can still dictate now; " +
                                        "recognition may use the network.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (partialText.isNotBlank()) partialText else "Tap the mic and speak…",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Text(
                            "Committed text: ${accumulated.length} chars. " +
                                "Tap Stop when you are finished dictating.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (stage) {
                is DictationStage.Ready -> TextButton(onClick = {
                    session?.stop()
                    onDismiss()
                }) { Text("Stop & Done") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                session?.stop()
                onDismiss()
            }) { Text("Cancel") }
        }
    )
}

private sealed interface DictationStage {
    data object Loading : DictationStage
    data object Ready : DictationStage
    data class Failed(val message: String) : DictationStage
}

// ---------------------------------------------------------------------------
// Read aloud
// ---------------------------------------------------------------------------

@Composable
fun ReadAloudDialog(
    viewModel: NoteflowViewModel,
    context: android.content.Context,
    text: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var outcomeMessage by remember { mutableStateOf<String?>(null) }
    var bus by remember { mutableStateOf(false) }

    fun stop() {
        viewModel.stopReadAloud()
        playing = false
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopReadAloud() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // R2-b2b1-UI-02 (phase-140): dialog over an open decrypted note — carry
        // FLAG_SECURE itself in release builds.
        properties = secureDialogProperties(),
        icon = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) },
        title = { Text("Read aloud (on-device)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (text.isBlank()) {
                    Text(
                        "There is no text to read in this note yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text.take(220),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(
                        checked = viewModel.settings.silentModeEnabled,
                        onCheckedChange = { on ->
                            viewModel.settings.silentModeEnabled = on
                            if (on) stop()
                        }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text("Silent mode", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "While on, read-aloud refuses — no audio is ever spoken.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                outcomeMessage?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (bus) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = !bus && text.isNotBlank(),
                onClick = {
                    bus = true
                    outcomeMessage = null
                    scope.launch {
                        val result = viewModel.readAloud(
                            text,
                            quietMode = viewModel.settings.silentModeEnabled
                        )
                        if (coroutineContext.isActive) {
                            bus = false
                            when (result) {
                                is PluginResult.Success -> when (val o = result.value) {
                                    is ReadAloudOutcome.Started -> {
                                        playing = true
                                        outcomeMessage = "Reading ${o.chunkCount} passage(s)…"
                                    }
                                    is ReadAloudOutcome.Empty -> { playing = false; outcomeMessage = o.message }
                                    is ReadAloudOutcome.Quiet -> { playing = false; outcomeMessage = o.message }
                                    is ReadAloudOutcome.Error -> { playing = false; outcomeMessage = o.message }
                                }
                                is PluginResult.Failure -> outcomeMessage = result.message
                                is PluginResult.Unavailable -> outcomeMessage = result.message
                            }
                        }
                    }
                }
            ) { Text(if (playing) "Restart" else "Play") }
        },
        dismissButton = {
            Row {
                if (playing) {
                    TextButton(onClick = ::stop) {
                        Icon(Icons.Outlined.Stop, contentDescription = null)
                        Text(" Stop")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

// ---------------------------------------------------------------------------
// Translation
// ---------------------------------------------------------------------------

@Composable
fun TranslationDialog(
    viewModel: NoteflowViewModel,
    context: android.content.Context,
    text: String,
    onReplace: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var languages by remember { mutableStateOf<List<TranslationLanguage>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<TranslationUiStatus>(TranslationUiStatus.Idle) }
    var busy by remember { mutableStateOf(false) }
    var translated by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val job = scope.launch {
            languages = viewModel.translationTargetLanguages()
        }
        onDispose { job.cancel() }
    }

    fun checkDownloaded(code: String) {
        scope.launch {
            when (val r = viewModel.isTranslationModelDownloaded(code)) {
                is PluginResult.Success ->
                    if (coroutineContext.isActive) status = if (r.value) TranslationUiStatus.Downloaded else TranslationUiStatus.NotDownloaded
                is PluginResult.Failure -> status = TranslationUiStatus.NotDownloaded
                is PluginResult.Unavailable -> status = TranslationUiStatus.NotDownloaded
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // R2-b2b1-UI-02 (phase-140): dialog over an open decrypted note — carry
        // FLAG_SECURE itself in release builds.
        properties = secureDialogProperties(),
        icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
        title = { Text("Translate (on-device)") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (languages.isEmpty()) {
                    Text(
                        "No translation targets available. Is the Translation plugin enabled " +
                            "and this device supported (API 26+)?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Into:", style = MaterialTheme.typography.titleSmall)
                        var expanded by remember { mutableStateOf(false) }
                        androidx.compose.material3.OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(selected?.let { code -> languages.firstOrNull { it.code == code }?.displayName } ?: "Select language")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            scrollState = overflowMenuScrollState(),
                            modifier = overflowMenuScrollModifier()
                        ) {
                            languages.forEach { lang ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(lang.displayName) },
                                    onClick = {
                                        expanded = false
                                        selected = lang.code
                                        status = TranslationUiStatus.Idle
                                        checkDownloaded(lang.code)
                                    }
                                )
                            }
                        }
                    }
                    when (val st = status) {
                        TranslationUiStatus.Idle -> Unit
                        TranslationUiStatus.Downloaded -> Text(
                            "Model for this language is on-device. Fully offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TranslationUiStatus.NotDownloaded -> {
                            Text(
                                "The small model for this language is not stored on-device yet. " +
                                    "It downloads once (one-time, then works offline).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!downloading) {
                                TextButton(onClick = {
                                    val code = selected ?: return@TextButton
                                    scope.launch {
                                        downloading = true
                                        when (val r = viewModel.downloadTranslationModel(code)) {
                                            is PluginResult.Success ->
                                                if (coroutineContext.isActive) {
                                                    downloading = false
                                                    status = when (r.value) {
                                                        is TranslationModelStatus.Downloaded -> TranslationUiStatus.Downloaded
                                                        is TranslationModelStatus.NotDownloaded -> TranslationUiStatus.NotDownloaded
                                                        is TranslationModelStatus.Error -> TranslationUiStatus.Failed(r.value.message)
                                                        is TranslationModelStatus.Downloading -> TranslationUiStatus.Downloading
                                                    }
                                                }
                                            is PluginResult.Failure ->
                                                if (coroutineContext.isActive) {
                                                    downloading = false
                                                    status = TranslationUiStatus.Failed(r.message)
                                                }
                                            is PluginResult.Unavailable ->
                                                if (coroutineContext.isActive) {
                                                    downloading = false
                                                    status = TranslationUiStatus.Failed(r.message)
                                                }
                                        }
                                    }
                                }) {
                                    Icon(Icons.Outlined.Download, contentDescription = null)
                                    Text(" Download model")
                                }
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text("Downloading model…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TranslationUiStatus.Downloading -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Downloading model…", style = MaterialTheme.typography.bodySmall)
                        }
                        is TranslationUiStatus.Failed -> Text(
                            st.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    HorizontalDivider()
                    if (translated.isNullOrBlank()) {
                        Text(
                            "Tapping Translate converts the whole note. The result appears here; " +
                                "use “Replace note” to keep it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                translated.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Row {
                if (!translated.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            onReplace(translated.orEmpty())
                            onDismiss()
                        }
                    ) { Text("Replace note") }
                } else {
                    TextButton(
                        enabled = !busy && selected != null,
                        onClick = {
                            val code = selected ?: return@TextButton
                            busy = true
                            scope.launch {
                                when (val r = viewModel.translateText(code, text)) {
                                    is PluginResult.Success ->
                                        if (coroutineContext.isActive) {
                                            busy = false
                                            when (val o = r.value) {
                                                is TranslationOutcome.Success -> translated = o.translatedText
                                                is TranslationOutcome.ModelNotReady ->
                                                    status = TranslationUiStatus.Failed(o.message)
                                                is TranslationOutcome.Error -> status = TranslationUiStatus.Failed(o.message)
                                            }
                                        }
                                    is PluginResult.Failure ->
                                        if (coroutineContext.isActive) {
                                            busy = false
                                            status = TranslationUiStatus.Failed(r.message)
                                        }
                                    is PluginResult.Unavailable ->
                                        if (coroutineContext.isActive) {
                                            busy = false
                                            status = TranslationUiStatus.Failed(r.message)
                                        }
                                }
                            }
                        }
                    ) { Text("Translate") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private sealed interface TranslationUiStatus {
    data object Idle : TranslationUiStatus
    data object Downloaded : TranslationUiStatus
    data object NotDownloaded : TranslationUiStatus
    data object Downloading : TranslationUiStatus
    data class Failed(val message: String) : TranslationUiStatus
}