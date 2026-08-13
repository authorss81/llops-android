package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnDeviceSmartAssistantBottomSheet(
    page: NotePageEntity,
    content: String,
    viewModel: NoteflowViewModel,
    onApplyTags: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var activeTab by remember { mutableIntStateOf(0) } // 0 = Summary, 1 = Tags, 2 = Action Items, 3 = Outline

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
                    color = scheme.primaryContainer
                ) {
                    Text(
                        text = "100% Offline / Local",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

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
                    text = { Text("Outline") },
                    icon = { Icon(Icons.Outlined.FormatListBulleted, contentDescription = null) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (activeTab) {
                    0 -> SmartSummaryView(content = content)
                    1 -> SmartTagExtractionView(content = content, onApplyTags = onApplyTags)
                    2 -> SmartActionItemsView(content = content)
                    3 -> SmartOutlineView(content = content)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SmartSummaryView(content: String) {
    val summary = remember(content) {
        if (content.isBlank()) return@remember "No text content available to summarize."
        val lines = content.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) return@remember "Note contains mostly headers and formatting."
        val paragraphSummary = lines.take(5).joinToString(" ")
        val keyPoints = lines.take(3).map { "• ${it.trim().removePrefix("- ").removePrefix("* ")}" }
        "**Key Takeaways:**\n" + keyPoints.joinToString("\n") + "\n\n**Overview:**\n" + paragraphSummary
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartTagExtractionView(content: String, onApplyTags: (List<String>) -> Unit) {
    val extractedTags = remember(content) {
        if (content.isBlank()) return@remember emptyList()
        val words = content.lowercase().split(Regex("[^a-zA-Z0-9_-]+"))
            .filter { it.length > 3 && !stopWords.contains(it) }
        val frequency = words.groupingBy { it }.eachCount()
        frequency.entries.sortedByDescending { it.value }
            .take(6)
            .map { it.key }
    }

    Column {
        Text(
            text = "Suggested Hashtags derived from note content:",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (extractedTags.isEmpty()) {
            Text("No keyword tags identified.", style = MaterialTheme.typography.bodyMedium)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                extractedTags.forEach { tag ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text("#$tag") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onApplyTags(extractedTags) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.LocalOffer, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add All Tags to Note")
            }
        }
    }
}

@Composable
private fun SmartActionItemsView(content: String) {
    val actionItems = remember(content) {
        if (content.isBlank()) return@remember emptyList()
        val list = mutableListOf<String>()
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]") || trimmed.startsWith("* [ ]")) {
                list.add(trimmed)
            } else if (trimmed.contains(Regex("(?i)\\b(todo|action|must|assign|deadline|follow up):"))) {
                list.add("• ${trimmed}")
            }
        }
        if (list.isEmpty()) {
            // Extract lines containing imperative verbs
            lines.filter { line ->
                val l = line.lowercase()
                l.contains("need to") || l.contains("should") || l.contains("remember to") || l.contains("create") || l.contains("review")
            }.take(5).forEach { list.add("• $it") }
        }
        list
    }

    Column {
        Text(
            text = "Extracted Action Items & Tasks (${actionItems.size}):",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (actionItems.isEmpty()) {
            Text("No action items detected in note.", style = MaterialTheme.typography.bodyMedium)
        } else {
            actionItems.forEach { item ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartOutlineView(content: String) {
    val headings = remember(content) {
        if (content.isBlank()) return@remember emptyList()
        content.lines()
            .filter { it.trim().startsWith("#") }
            .map { it.trim() }
    }

    Column {
        Text(
            text = "Note Outline & Structure:",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (headings.isEmpty()) {
            Text("No markdown headings (# Heading) found.", style = MaterialTheme.typography.bodyMedium)
        } else {
            headings.forEach { h ->
                val level = h.takeWhile { it == '#' }.length
                val text = h.removePrefix("#").trim()
                Row(modifier = Modifier.padding(start = ((level - 1) * 16).dp, top = 4.dp, bottom = 4.dp)) {
                    Text("• ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private val stopWords = setOf(
    "the", "and", "this", "that", "with", "from", "for", "have", "with", "what",
    "your", "which", "will", "would", "there", "their", "about", "into", "some", "than", "them", "then"
)
