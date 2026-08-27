package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.authorss81.noteflow.services.SettingsManager
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

private val templateAccentColors = listOf(
    "#1E293B", "#334155", "#475569", "#64748B",
    "#0284C7", "#38BDF8", "#7E22CE", "#A855F7",
    "#059669", "#10B981", "#DC2626", "#F97316"
)

// Phase 223: the drafting grids — the only paper templates that expose template
// customization (colour/spacing/opacity) and render a real PaperTemplatePreview
// thumbnail in the picker.
private val draftingPaperTemplates = listOf("perspective_1pt", "perspective_2pt", "isometric")

@Composable
fun TemplateLibraryDialog(
    viewModel: NoteflowViewModel,
    onDismiss: () -> Unit,
    onTemplateApplied: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val selectedNotebook by viewModel.selectedNotebook.collectAsStateWithLifecycle()
    var createInNewNotebook by remember { mutableStateOf(false) }
    val settings = viewModel.settings

    var expandedTemplateId by remember { mutableStateOf<String?>(null) }

    val templates = remember {
        listOf(
            WorkspaceTemplate(
                id = "project_kanban",
                title = "\uD83D\uDCCA Project Management Vault",
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
                title = "\uD83D\uDCC5 Daily Journal Vault",
                description = "Daily journal workspace with reflection prompts, habit tracking, and calendar views.",
                icon = Icons.Outlined.CalendarMonth,
                defaultNotebookName = "Daily Journal",
                defaultSectionName = "August 2026",
                paperTemplate = "lined",
                pagesToCreate = listOf(
                    "Daily Reflection - August 8" to "# Daily Journal - Aug 8\n\n#calendar #journal\n\n## Morning Intentions\n- Focus on core priorities.\n- Stay hydrated.\n\n## Evening Highlights\n- Completed Phase 25 implementation!\n\n## Gratitude\n1. Productive coding session.\n2. Clean architecture.",
                    "Habit Tracker Log" to "# Habit Tracker\n\n#habits\n\n- [x] Morning reading (20m)\n- [x] Exercise (30m)\n- [ ] Evening meditation"
                )
            ),
            WorkspaceTemplate(
                id = "research_math",
                title = "\uD83D\uDD2C Research & Study Vault",
                description = "LaTeX math expressions, science notes, and interconnected wiki backlinks.",
                icon = Icons.Outlined.Psychology,
                defaultNotebookName = "Quantum Research",
                defaultSectionName = "Notes",
                paperTemplate = "grid",
                pagesToCreate = listOf(
                    "Schr\u00F6dinger Equation" to "# Schr\u00F6dinger Equation\n\n#math #quantum\n\n> [!NOTE]\n> Core equation of non-relativistic quantum mechanics.\n\n$$ i\\hbar \\frac{\\partial}{\\partial t} \\Psi(\\mathbf{r},t) = \\hat{H}\\Psi(\\mathbf{r},t) $$\n\nSee also: [[Wave Function]] and [[Hamiltonian Operator]].",
                    "Wave Function" to "# Wave Function\n\n#quantum\n\nProbability density function $\\rho = |\\Psi|^2$ describing quantum state."
                )
            ),
            WorkspaceTemplate(
                id = "meeting_notes",
                title = "\uD83D\uDCDD Meeting & Team Sync",
                description = "Structured meeting notes with agenda, participants, decisions, and action items.",
                icon = Icons.Outlined.Groups,
                defaultNotebookName = "Team Workspace",
                defaultSectionName = "Meetings",
                paperTemplate = "meeting",
                pagesToCreate = listOf(
                    "Product Sync - Aug 8" to "# Product Sync\n\n#meeting #action-items\n\n**Date:** August 8, 2026\n**Attendees:** Alex, Sarah, David\n\n## Agenda\n1. Q3 Roadmap Review\n2. Security & Performance Verification\n\n## Key Decisions\n- Ship Phase 25 features ahead of schedule.\n\n## Action Items\n- [ ] Alex: Verify CI pipeline\n- [ ] Sarah: Update documentation"
                )
            ),
            WorkspaceTemplate(
                id = "knowledge_base",
                title = "\uD83D\uDCDA Knowledge Base Vault",
                description = "Obsidian-compatible knowledge graph structure with index and topic tags.",
                icon = Icons.Outlined.Book,
                defaultNotebookName = "Personal Wiki",
                defaultSectionName = "Core Index",
                paperTemplate = "dots",
                pagesToCreate = listOf(
                    "Master Index" to "# Master Knowledge Index\n\n#wiki #index\n\nWelcome to your personal knowledge base.\n\n## Topics\n- [[Computer Science]]\n- [[System Architecture]]\n- [[Mathematics]]"
                )
            ),
            WorkspaceTemplate(
                id = "cross_grid_notes",
                title = "\uD83D\uDD78\uFE0F Cross-Grid Notes",
                description = "Dots over a faint grid — guides handwriting alignment without heavy lines.",
                icon = Icons.Outlined.Dashboard,
                defaultNotebookName = "Cross-Grid Notes",
                defaultSectionName = "Notes",
                paperTemplate = "cross_grid",
                pagesToCreate = listOf(
                    "Quick Sketch" to "# Quick Sketch\n\n#sketch\n\nUse this for wireframes and rough diagrams — the cross-grid guides alignment."
                )
            ),
            WorkspaceTemplate(
                id = "storyboard",
                title = "\uD83C\uDFAF Storyboard Vault",
                description = "Three captioned panels for sequential art, story planning, or comic strips.",
                icon = Icons.Outlined.Groups,
                defaultNotebookName = "Storyboard",
                defaultSectionName = "Sequences",
                paperTemplate = "storyboard",
                pagesToCreate = listOf(
                    "Sequence 1" to "# Sequence 1\n\n## Panel 1\n\n## Panel 2\n\n## Panel 3\n\n---\n**Notes:** "
                )
            ),
            // Phase 223: drafting-grid vaults — these are the only templates whose
            // cards render a real PaperTemplatePreview thumbnail (the same
            // PerspectiveGridPolicy geometry the full-page renderer uses).
            WorkspaceTemplate(
                id = "perspective_1pt_notes",
                title = "\uD83D\uDDD0\uFE0F 1-Point Perspective Drafting",
                description = "Single vanishing point on the horizon — architectural one-point floor grids and receding-line sketches.",
                icon = Icons.Outlined.Book,
                defaultNotebookName = "1-Pt Drafting",
                defaultSectionName = "Drafts",
                paperTemplate = "perspective_1pt",
                pagesToCreate = listOf(
                    "Room One-Point" to "# Room One-Point\n\n#drafting\n\nOne-point interior: all parallel lines recede to the single vanishing point at the horizon centre."
                )
            ),
            WorkspaceTemplate(
                id = "perspective_2pt_notes",
                title = "\uD83D\uDDD0\uFE0F 2-Point Perspective Drafting",
                description = "Two off-page vanishing points — corner views, streets, and building elevations.",
                icon = Icons.Outlined.Groups,
                defaultNotebookName = "2-Pt Drafting",
                defaultSectionName = "Drafts",
                paperTemplate = "perspective_2pt",
                pagesToCreate = listOf(
                    "Corner Two-Point" to "# Corner Two-Point\n\n#drafting\n\nTwo-point corner: floor lines recede to vanishing points beyond each page edge."
                )
            ),
            WorkspaceTemplate(
                id = "isometric_notes",
                title = "\uD83D\uDDD0\uFE0F Isometric Drafting",
                description = "30° isometric lattice for technical illustrations and exploded views.",
                icon = Icons.Outlined.Dashboard,
                defaultNotebookName = "Isometric",
                defaultSectionName = "Drafts",
                paperTemplate = "isometric",
                pagesToCreate = listOf(
                    "Isometric Sketch" to "# Isometric Sketch\n\n#drafting\n\n30° isometric lattice — left/right diagonals plus verticals for technical drawing."
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
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(templates) { template ->
                        val isExpanded = expandedTemplateId == template.id
                        val hasCustomizablePaper = template.paperTemplate in listOf(
                            "lined", "grid", "dots", "cross_grid"
                        ) || template.paperTemplate in draftingPaperTemplates

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .padding(14.dp)
                                        .clickable {
                                            viewModel.applyWorkspaceTemplate(template, createInNewNotebook) {
                                                onTemplateApplied()
                                                onDismiss()
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Phase 223 review fix: only the DRAFTING
                                    // templates render a thumbnail — the other
                                    // cards no longer show a blank bordered square
                                    // (PaperTemplatePreview is a no-op for them).
                                    if (template.paperTemplate in draftingPaperTemplates) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(scheme.surface, RoundedCornerShape(6.dp))
                                                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(6.dp))
                                        ) {
                                            PaperTemplatePreview(template = template.paperTemplate, modifier = Modifier.fillMaxSize())
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                    }
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
                                    if (hasCustomizablePaper) {
                                        IconButton(
                                            onClick = { expandedTemplateId = if (isExpanded) null else template.id },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Outlined.Tune,
                                                contentDescription = "Customize paper",
                                                modifier = Modifier.size(18.dp),
                                                tint = if (isExpanded) scheme.primary else scheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                if (isExpanded && hasCustomizablePaper) {
                                    TemplateCustomizationControls(
                                        paperTemplate = template.paperTemplate,
                                        settings = settings,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
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

@Composable
private fun TemplateCustomizationControls(
    paperTemplate: String,
    settings: SettingsManager,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme

    var spacingPref by remember {
        mutableStateOf(settings.templatePref(paperTemplate, "spacing", defaultSpacingFor(paperTemplate)))
    }
    var opacityPref by remember {
        mutableStateOf(settings.templatePref(paperTemplate, "opacity", "0.22"))
    }
    var dotRadiusPref by remember {
        mutableStateOf(settings.templatePref(paperTemplate, "dotRadius", "2.0"))
    }
    var colorPref by remember {
        mutableStateOf(settings.templatePref(paperTemplate, "color", "#64748B"))
    }

    Surface(
        color = scheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Paper Style",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Color accent picker
            Text(
                text = "Line Color",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                templateAccentColors.take(8).forEach { hex ->
                    val color = Color(android.graphics.Color.parseColor(hex))
                    val isSelected = colorPref == hex
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (isSelected) Modifier.border(2.dp, scheme.primary, CircleShape)
                                else Modifier.border(1.dp, scheme.outline, CircleShape)
                            )
                            .clickable {
                                colorPref = hex
                                settings.setTemplatePref(paperTemplate, "color", hex)
                            }
                    )
                }
            }

            if (paperTemplate in listOf("lined", "grid", "dots", "cross_grid") || paperTemplate in draftingPaperTemplates) {
                Spacer(modifier = Modifier.height(10.dp))
                // Line spacing
                val spacingOptions = listOf("24" to "24dp", "28" to "28dp", "36" to "36dp")
                Text(
                    text = "Spacing",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    spacingOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = spacingPref == value,
                            onClick = {
                                spacingPref = value
                                settings.setTemplatePref(paperTemplate, "spacing", value)
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (paperTemplate in listOf("grid", "cross_grid") || paperTemplate in draftingPaperTemplates) {
                Spacer(modifier = Modifier.height(10.dp))
                // Grid opacity
                val opacityOptions = listOf("0.12" to "Faint", "0.22" to "Normal", "0.35" to "Bold")
                Text(
                    text = "Grid Opacity",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    opacityOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = opacityPref == value,
                            onClick = {
                                opacityPref = value
                                settings.setTemplatePref(paperTemplate, "opacity", value)
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (paperTemplate in listOf("dots", "cross_grid")) {
                Spacer(modifier = Modifier.height(10.dp))
                // Dot radius
                val radiusOptions = listOf("1.5" to "Small", "2.0" to "Medium", "3.0" to "Large")
                Text(
                    text = "Dot Size",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    radiusOptions.forEach { (value, label) ->
                        FilterChip(
                            selected = dotRadiusPref == value,
                            onClick = {
                                dotRadiusPref = value
                                settings.setTemplatePref(paperTemplate, "dotRadius", value)
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    }
}

private fun defaultSpacingFor(paperTemplate: String): String = when (paperTemplate) {
    "lined" -> "36"
    "grid" -> "28"
    "dots" -> "28"
    "cross_grid" -> "28"
    else -> "28"
}
