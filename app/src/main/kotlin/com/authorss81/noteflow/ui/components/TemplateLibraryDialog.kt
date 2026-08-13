package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel

data class WorkspaceTemplate(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val defaultNotebookName: String,
    val defaultSectionName: String,
    val paperTemplate: String = "grid",
    val pagesToCreate: List<Pair<String, String>> // (Title, Content)
)

@Composable
fun TemplateLibraryDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit,
    onTemplateApplied: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val selectedNotebook by viewModel.selectedNotebook.collectAsStateWithLifecycle()
    var createInNewNotebook by remember { mutableStateOf(false) }

    val templates = remember {
        listOf(
            WorkspaceTemplate(
                id = "project_kanban",
                title = "📊 Project Management Vault",
                description = "Agile kanban workflow with Backlog, Sprint, and Documentation sections.",
                icon = Icons.Outlined.Dashboard,
                defaultNotebookName = "Project Alpha",
                defaultSectionName = "Sprint 1",
                paperTemplate = "grid",
                pagesToCreate = listOf(
                    "Task: Research API Architecture" to "# Research API Architecture\n\n#todo #in-progress\n\n- [x] Review REST endpoints\n- [ ] Draft OpenAPI spec\n- [ ] Security audit",
                    "Task: UI Components System" to "# UI Components System\n\n#todo\n\nDefine design tokens, colors, typography.",
                    "Task: Release Verification" to "# Release Verification\n\n#done\n\n- [x] Build passes\n- [x] Unit tests pass"
                )
            ),
            WorkspaceTemplate(
                id = "daily_journal",
                title = "📅 Daily Journal Vault",
                description = "Daily journal workspace with reflection prompts, habit tracking, and calendar views.",
                icon = Icons.Outlined.CalendarMonth,
                defaultNotebookName = "Daily Journal",
                defaultSectionName = "August 2026",
                paperTemplate = "lined",
                pagesToCreate = listOf(
                    "Daily Reflection - August 8" to "# Daily Journal - Aug 8\n\n#calendar #journal\n\n## 🌅 Morning Intentions\n- Focus on core priorities.\n- Stay hydrated.\n\n## 📝 Evening Highlights\n- Completed Phase 25 implementation!\n\n## 💡 Gratitude\n1. Productive coding session.\n2. Clean architecture.",
                    "Habit Tracker Log" to "# Habit Tracker\n\n#habits\n\n- [x] Morning reading (20m)\n- [x] Exercise (30m)\n- [ ] Evening meditation"
                )
            ),
            WorkspaceTemplate(
                id = "research_math",
                title = "🔬 Research & Study Vault",
                description = "LaTeX math expressions, science notes, and interconnected wiki backlinks.",
                icon = Icons.Outlined.Psychology,
                defaultNotebookName = "Quantum Research",
                defaultSectionName = "Notes",
                paperTemplate = "grid",
                pagesToCreate = listOf(
                    "Schrödinger Equation" to "# Schrödinger Equation\n\n#math #quantum\n\n> [!NOTE]\n> Core equation of non-relativistic quantum mechanics.\n\n$$ i\\hbar \\frac{\\partial}{\\partial t} \\Psi(\\mathbf{r},t) = \\hat{H}\\Psi(\\mathbf{r},t) $$\n\nSee also: [[Wave Function]] and [[Hamiltonian Operator]].",
                    "Wave Function" to "# Wave Function\n\n#quantum\n\nProbability density function $\\rho = |\\Psi|^2$ describing quantum state."
                )
            ),
            WorkspaceTemplate(
                id = "meeting_notes",
                title = "📝 Meeting & Team Sync",
                description = "Structured meeting notes with agenda, participants, decisions, and action items.",
                icon = Icons.Outlined.Groups,
                defaultNotebookName = "Team Workspace",
                defaultSectionName = "Meetings",
                paperTemplate = "meeting",
                pagesToCreate = listOf(
                    "Product Sync - Aug 8" to "# Product Sync\n\n#meeting #action-items\n\n**Date:** August 8, 2026\n**Attendees:** Alex, Sarah, David\n\n## 📋 Agenda\n1. Q3 Roadmap Review\n2. Security & Performance Verification\n\n## 🎯 Key Decisions\n- Ship Phase 25 features ahead of schedule.\n\n## 🚀 Action Items\n- [ ] Alex: Verify CI pipeline\n- [ ] Sarah: Update documentation"
                )
            ),
            WorkspaceTemplate(
                id = "knowledge_base",
                title = "📚 Knowledge Base Vault",
                description = "Obsidian-compatible knowledge graph structure with index and topic tags.",
                icon = Icons.Outlined.Book,
                defaultNotebookName = "Personal Wiki",
                defaultSectionName = "Core Index",
                paperTemplate = "dots",
                pagesToCreate = listOf(
                    "Master Index" to "# Master Knowledge Index\n\n#wiki #index\n\nWelcome to your personal knowledge base.\n\n## Topics\n- [[Computer Science]]\n- [[System Architecture]]\n- [[Mathematics]]"
                )
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Workspace & Vault Templates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = scheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Destination Notebook",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !createInNewNotebook,
                                onClick = { createInNewNotebook = false },
                                label = {
                                    Text(
                                        text = "Current (${selectedNotebook?.name ?: "Active"})",
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = createInNewNotebook,
                                onClick = { createInNewNotebook = true },
                                label = {
                                    Text(
                                        text = "New Notebook",
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(templates) { template ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.applyWorkspaceTemplate(template, createInNewNotebook) {
                                        onTemplateApplied()
                                        onDismiss()
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    template.icon,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = template.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = template.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (createInNewNotebook) "Creates notebook: ${template.defaultNotebookName}"
                                               else "Adds to: ${selectedNotebook?.name ?: "Current Notebook"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
