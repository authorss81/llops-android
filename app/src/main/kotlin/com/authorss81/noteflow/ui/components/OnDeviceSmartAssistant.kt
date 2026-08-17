package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.plugins.AssistantOutcome
import com.authorss81.noteflow.plugins.AssistantPlugin
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 16: the On-Device Smart Assistant now runs a REAL local LLM
 * (Qwen2-0.5B via MediaPipe tasks-genai) instead of heuristics. The model is
 * NOT bundled — the user downloads it once into app-private files with an
 * explicit consent + progress UI (a low-end device shows a clear reason).
 *
 * Everything is local once the model is in place; nothing leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnDeviceSmartAssistantBottomSheet(
    page: NotePageEntity,
    content: String,
    viewModel: NoteflowViewModel,
    context: android.content.Context,
    onApplyTags: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableIntStateOf(0) } // 0 Summary, 1 Tags, 2 Action Items, 3 Ask
    var plugin by remember { mutableStateOf<AssistantPlugin?>(null) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var modelDownloaded by remember { mutableStateOf(false) }

    var summary by remember { mutableStateOf<String?>(null) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagsRaw by remember { mutableStateOf<String?>(null) }
    var actionItems by remember { mutableStateOf<String?>(null) }
    var askResult by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val job = scope.launch {
            loading = true
            when (val result = viewModel.assistantPlugin()) {
                is PluginResult.Success -> {
                    plugin = result.value
                    modelDownloaded = result.value.isModelDownloaded(context)
                    val reason = result.value.unavailableReason(context)
                    if (reason != null) blockedMessage = reason
                }
                is PluginResult.Failure -> blockedMessage = result.message
                is PluginResult.Unavailable -> blockedMessage = result.message
            }
            loading = false
        }
        onDispose {
            job.cancel()
            plugin?.close()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "On-Device Smart Assistant",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (modelDownloaded) scheme.primaryContainer else scheme.surfaceVariant
                ) {
                    Text(
                        text = if (modelDownloaded) "100% Local / Offline" else "Local LLM needed",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            when {
                loading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Starting the local assistant…", style = MaterialTheme.typography.bodySmall)
                }
                blockedMessage != null -> {
                    Surface(
                        color = scheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            blockedMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                else -> {
                    if (!modelDownloaded) {
                        ModelDownloadCard(
                            expectedSizeMb = plugin?.expectedModelSizeBytes()?.let { it / (1024 * 1024) } ?: 379L,
                            downloading = downloading,
                            progress = downloadProgress,
                            onDownload = {
                                downloading = true
                                downloadProgress = 0.1f
                                scope.launch {
                                    when (val r = viewModel.assistantDownloadModel { p ->
                                        downloadProgress = p.coerceIn(0f, 1f)
                                    }) {
                                        is PluginResult.Success ->
                                            if (coroutineContext.isActive) {
                                                downloading = false
                                                when (r.value) {
                                                    is AssistantOutcome.Success -> modelDownloaded = true
                                                    is AssistantOutcome.ModelNotReady -> blockedMessage = r.value.message
                                                    is AssistantOutcome.Error -> blockedMessage = r.value.message
                                                }
                                            }
                                        is PluginResult.Failure ->
                                            if (coroutineContext.isActive) {
                                                downloading = false
                                                blockedMessage = r.message
                                            }
                                        is PluginResult.Unavailable ->
                                            if (coroutineContext.isActive) {
                                                downloading = false
                                                blockedMessage = r.message
                                            }
                                    }
                                }
                            }
                        )
                    } else {
                        Text(
                            "Model ready. Ask questions and summarize this note — all on-device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tabs
                    ScrollableTabRow(selectedTabIndex = activeTab, edgePadding = 0.dp) {
                        Tab(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0 },
                            text = { Text("Summarize") },
                            icon = { Icon(Icons.Outlined.ShortText, contentDescription = null) }
                        )
                        Tab(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1 },
                            text = { Text("Auto-Tags") },
                            icon = { Icon(Icons.Outlined.LocalOffer, contentDescription = null) }
                        )
                        Tab(
                            selected = activeTab == 2,
                            onClick = { activeTab = 2 },
                            text = { Text("Action Items") },
                            icon = { Icon(Icons.Outlined.Checklist, contentDescription = null) }
                        )
                        Tab(
                            selected = activeTab == 3,
                            onClick = { activeTab = 3 },
                            text = { Text("Ask") },
                            icon = { Icon(Icons.Outlined.QuestionAnswer, contentDescription = null) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 340.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (activeTab) {
                            0 -> LlmTaskView(
                                idleHint = "A concise summary of the note, written on-device.",
                                result = summary,
                                actionLabel = "Summarize",
                                onRun = {
                                    scope.launch {
                                        when (val r = viewModel.assistantSummarize(content)) {
                                            is PluginResult.Success -> if (coroutineContext.isActive) {
                                                val o = r.value
                                                when (o) {
                                                    is AssistantOutcome.Success -> summary = o.text
                                                    is AssistantOutcome.ModelNotReady -> blockedMessage = o.message
                                                    is AssistantOutcome.Error -> blockedMessage = o.message
                                                }
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            )
                            1 -> LlmTaskView(
                                idleHint = "Suggested #tags derived from the note by the local model.",
                                result = tagsRaw?.takeIf { it.isNotBlank() },
                                actionLabel = "Suggest tags",
                                onRun = {
                                    scope.launch {
                                        when (val r = viewModel.assistantSuggestTags(content)) {
                                            is PluginResult.Success -> if (coroutineContext.isActive) {
                                                val o = r.value
                                                when (o) {
                                                    is AssistantOutcome.Success -> {
                                                        tagsRaw = o.text
                                                        tags = extractTagList(o.text, content)
                                                        if (tags.isNotEmpty()) onApplyTags(tags)
                                                    }
                                                    is AssistantOutcome.ModelNotReady -> blockedMessage = o.message
                                                    is AssistantOutcome.Error -> blockedMessage = o.message
                                                }
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            )
                            2 -> LlmTaskView(
                                idleHint = "Tasks, follow-ups and action items pulled from the note.",
                                result = actionItems,
                                actionLabel = "Extract tasks",
                                onRun = {
                                    scope.launch {
                                        when (val r = viewModel.assistantExtractActionItems(content)) {
                                            is PluginResult.Success -> if (coroutineContext.isActive) {
                                                val o = r.value
                                                when (o) {
                                                    is AssistantOutcome.Success -> actionItems = o.text
                                                    is AssistantOutcome.ModelNotReady -> blockedMessage = o.message
                                                    is AssistantOutcome.Error -> blockedMessage = o.message
                                                }
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            )
                            3 -> AskView(
                                question = question,
                                onQuestionChanged = { question = it },
                                result = askResult,
                                onAsk = {
                                    scope.launch {
                                        when (val r = viewModel.assistantAnswerQuestion(content, question.trim())) {
                                            is PluginResult.Success -> if (coroutineContext.isActive) {
                                                val o = r.value
                                                when (o) {
                                                    is AssistantOutcome.Success -> askResult = o.text
                                                    is AssistantOutcome.ModelNotReady -> blockedMessage = o.message
                                                    is AssistantOutcome.Error -> blockedMessage = o.message
                                                }
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadCard(
    expectedSizeMb: Long,
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Use a small on-device language model to summarize and answer " +
                    "questions about your notes. Nothing is uploaded; once downloaded " +
                    "the model works fully offline.\n\n" +
                    "One-time download, ~$expectedSizeMb MB, saved in app-private storage.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp)
            )
        }
        if (downloading) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "Downloading model… ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(onClick = onDownload, enabled = !downloading) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (downloading) "Downloading…" else "Download model")
        }
    }
}

@Composable
private fun LlmTaskView(
    idleHint: String,
    result: String?,
    actionLabel: String,
    onRun: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(idleHint, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
            Text(actionLabel)
        }
        if (!result.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    result,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun AskView(
    question: String,
    onQuestionChanged: (String) -> Unit,
    result: String?,
    onAsk: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = question,
            onValueChange = onQuestionChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask about this note…") },
            singleLine = false,
            minLines = 2
        )
        Button(
            onClick = onAsk,
            modifier = Modifier.fillMaxWidth(),
            enabled = question.isNotBlank()
        ) {
            Text("Ask")
        }
        if (!result.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    result,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/** Best-effort tag extraction from the model output, falling back to keywords. */
private fun extractTagList(modelText: String, content: String): List<String> {
    val fromModel = Regex("#([a-zA-Z0-9_-]+)").findAll(modelText)
        .map { it.groupValues[1].lowercase() }
        .filter { it.length in 2..24 }
        .toList()
    if (fromModel.isNotEmpty()) return fromModel.distinct().take(8)
    return content.lowercase()
        .split(Regex("[^a-zA-Z0-9_-]+"))
        .filter { it.length > 3 && !stopWords.contains(it) }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(6)
        .map { it.key }
}

private val stopWords = setOf(
    "the", "and", "this", "that", "with", "from", "for", "have", "with", "what",
    "your", "which", "will", "would", "there", "their", "about", "into", "some", "than", "them", "then"
)