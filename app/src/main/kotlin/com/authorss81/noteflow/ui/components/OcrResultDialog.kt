package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.plugins.OcrOutcome
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.services.ClipboardGuard
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import com.authorss81.noteflow.utils.nestedScrollGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Progress state of an OCR run inside [OcrResultDialog]. */
private sealed interface OcrStage {
    data object Running : OcrStage
    data class Ready(val text: String) : OcrStage
    data class Failed(val message: String) : OcrStage
}

/**
 * Phase 12: run the on-device OCR plugin against an attached image and show the
 * recognized text. Real progress/error/cancel states (the model task is
 * cancelled when the user cancels), and the extracted text can be copied — only
 * through [ClipboardGuard.recordCopy], never a raw clipboard write — or inserted
 * into the note via [onInsertIntoNote].
 *
 * This is pure UI on top of `viewModel.extractTextFromImage`; all recognition
 * logic lives in the OCR plugin package.
 */
@Composable
fun OcrResultDialog(
    imagePath: String,
    viewModel: NoteflowViewModel,
    onInsertIntoNote: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var stage by remember { mutableStateOf<OcrStage>(OcrStage.Running) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(imagePath) {
        job?.cancel()
        stage = OcrStage.Running
        job = scope.launch {
            val result = viewModel.extractTextFromImage(imagePath)
            if (coroutineContext.isActive) {
                stage = when (result) {
                    is PluginResult.Success -> when (val outcome = result.value) {
                        is OcrOutcome.Success -> OcrStage.Ready(outcome.text)
                        is OcrOutcome.NoText -> OcrStage.Failed(outcome.message)
                        is OcrOutcome.Error -> OcrStage.Failed(outcome.message)
                    }
                    is PluginResult.Failure -> OcrStage.Failed(result.message)
                    is PluginResult.Unavailable -> OcrStage.Failed(result.message)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    AlertDialog(
        onDismissRequest = {
            job?.cancel()
            onDismiss()
        },
        // R2-b2b1-UI-02 (phase-140): this dialog shows the FULL recognized note
        // text in a separate window — carry FLAG_SECURE itself in release.
        properties = secureDialogProperties(),
        icon = {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Outlined.TextFields,
                contentDescription = null
            )
        },
        title = { Text("Extract text (OCR)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val s = stage) {
                    is OcrStage.Running -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Recognizing text on-device… (offline, no data leaves the phone)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is OcrStage.Ready -> {
                        Text(
                            "Recognized text:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        SelectionContainer {
                            Text(
                                text = s.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .nestedScrollGuard()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                    is OcrStage.Failed -> {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val s = stage) {
                is OcrStage.Running -> TextButton(onClick = {
                    job?.cancel()
                    onDismiss()
                }) { Text("Cancel") }
                is OcrStage.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        ClipboardGuard.recordCopy()
                        clipboardManager.setText(AnnotatedString(s.text))
                        viewModel.showSnackbar("OCR text copied")
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        onInsertIntoNote(s.text)
                        onDismiss()
                    }) { Text("Insert into note") }
                }
                is OcrStage.Failed -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (stage is OcrStage.Ready) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
