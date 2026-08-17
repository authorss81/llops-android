package com.authorss81.noteflow.ui.components

import androidx.compose.animation.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.services.TutorialAction
import com.authorss81.noteflow.services.TutorialCurriculum
import com.authorss81.noteflow.services.TutorialSession
import com.authorss81.noteflow.services.TutorialSlide

/**
 * Compose-observable glue over the pure-JVM [TutorialSession]. Every mutation
 * (recordAction / advance / forceAdvance / back) bumps [tick], a snapshot
 * read whose change schedules the recomposition the mechanical slide/action
 * state needs. [session] stays a plain data machine — fully unit-testable.
 */
class TutorialUiState(initialIndex: Int) {
    val session: TutorialSession = TutorialSession(
        slides = TutorialCurriculum.slides,
        initialIndex = initialIndex
    )

    var tick: Int by mutableIntStateOf(0)
        private set

    fun recordAction(slideId: String): Boolean {
        if (!session.recordAction(slideId)) return false
        tick++
        return true
    }

    fun advance(): Boolean {
        if (!session.advance()) return false
        tick++
        return true
    }

    fun skipStep(): Boolean {
        if (!session.forceAdvance()) return false
        tick++
        return true
    }

    fun back(): Boolean {
        if (!session.back()) return false
        tick++
        return true
    }
}

/** Resolves a curriculum iconKey to a real Material icon (fallback = lightbulb). */
fun tutorialIcon(key: String): ImageVector = when (key) {
    "home" -> Icons.Outlined.Home
    "folder" -> Icons.Outlined.Folder
    "sidebars" -> Icons.Outlined.ViewSidebar
    "views" -> Icons.Outlined.TableRows
    "search" -> Icons.Outlined.Search
    "notes" -> Icons.Outlined.BorderColor
    "type" -> Icons.Outlined.TextFields
    "markdown" -> Icons.Outlined.Code
    "link" -> Icons.Outlined.Link
    "tags" -> Icons.Outlined.LocalOffer
    "today" -> Icons.Outlined.Today
    "mic" -> Icons.Outlined.GraphicEq
    "canvas" -> Icons.Outlined.CropLandscape
    "brush" -> Icons.Outlined.Brush
    "draw" -> Icons.Outlined.Gesture
    "water" -> Icons.Outlined.WaterDrop
    "pressure" -> Icons.Outlined.TouchApp
    "shapes" -> Icons.Outlined.ChangeHistory
    "paper" -> Icons.Outlined.StickyNote2
    "layers" -> Icons.Outlined.Layers
    "eye" -> Icons.Outlined.Visibility
    "palette" -> Icons.Outlined.Palette
    "rainbow" -> Icons.Outlined.AutoAwesome
    "picker" -> Icons.Outlined.Colorize
    "modes" -> Icons.Outlined.AutoFixHigh
    "colour" -> Icons.Outlined.FormatColorFill
    "eraser" -> Icons.Outlined.Eraser
    "erase" -> Icons.Outlined.Eraser
    "graph" -> Icons.Outlined.Hub
    "backlinks" -> Icons.Outlined.Link
    "plugin" -> Icons.Outlined.Extension
    "store" -> Icons.Outlined.Storefront
    "capabilities" -> Icons.Outlined.AutoAwesome
    "backup" -> Icons.Outlined.Backup
    "webdav" -> Icons.Outlined.CloudSync
    "nearby" -> Icons.Outlined.NearMe
    "crypto" -> Icons.Outlined.Security
    "vault" -> Icons.Outlined.Lock
    "lockout" -> Icons.Outlined.DoNotTouch
    "recovery" -> Icons.Outlined.Restore
    "done" -> Icons.Outlined.AutoAwesome
    else -> Icons.Outlined.Lightbulb
}

/**
 * Phase 125 — enhanced interactive tutorial.
 *
 * RENDERS the pure session model: one slide per screen, sections, an interactive
 * demo for action slides (progress-checked), a linear progress bar, Prev / Next /
 * Skip-step, a persisted-resume "Skip tutorial", and a truly-persisted
 * "Don't show this again". All animations stay cheap (reduce-motion aware,
 * draw-only) to honour the low-end rule; every user action that changes the slide
 * calls [onProgress] so the host can persist the resume index.
 */
@Composable
fun InteractiveTutorial(
    initialIndex: Int,
    onProgress: (Int) -> Unit,
    onSkip: () -> Unit,
    onComplete: () -> Unit,
    onDontShowAgain: () -> Unit
) {
    val ui = remember(initialIndex) { TutorialUiState(initialIndex) }
    val slide = ui.session.current ?: return
    val actionSlide = slide.action
    val canGoNext = ui.session.canAdvance
    val isLast = ui.session.isLast
    val isFirst = ui.session.isFirst

    // Snapshot read: any session mutation bumps `tick` and recomposes this screen.
    @Suppress("UNUSED_EXPRESSION")
    ui.tick

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(onClick = {}) // Block clicks underneath
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(20.dp)
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Section chip
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    Text(
                        text = "${slide.section.displayName.uppercase()} · " +
                            "${ui.session.slideNumberInSection}/${ui.session.slidesInSection}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Icon medallion
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tutorialIcon(slide.iconKey),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Step ${ui.session.index + 1} of ${ui.session.total}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = slide.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth(0.2f)
                        .padding(bottom = 12.dp)
                )

                Text(
                    text = slide.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                // Interactive demo + progress-check
                if (actionSlide != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TutorialDemoArea(
                        action = actionSlide,
                        onDone = { ui.recordAction(slide.id) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (ui.session.isActionDone(slide.id)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Step complete — ${actionSlide.label} done",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Text(
                            text = "Do this to unlock Next: ${actionSlide.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Tip footer
                slide.tip?.let { tip ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lightbulb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Progress bar + numeric counter
                LinearProgressIndicator(
                    progress = { ui.session.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Section ${ui.session.sectionOrdinal + 1}/${TutorialCurriculum.sectionCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${ui.session.progressPercent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Main actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text(
                            text = "Skip Tutorial",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isFirst) {
                            OutlinedButton(
                                onClick = {
                                    if (ui.back()) onProgress(ui.session.index)
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Back")
                            }
                        }

                        if (actionSlide != null && !canGoNext) {
                            TextButton(
                                onClick = {
                                    if (ui.skipStep()) onProgress(ui.session.index)
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text(
                                    "Skip step",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isLast && canGoNext) {
                                    onComplete()
                                } else if (ui.advance()) {
                                    onProgress(ui.session.index)
                                }
                            },
                            enabled = canGoNext,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Text(
                                text = if (isLast) "Get Started"
                                else if (actionSlide != null) "Next"
                                else "Continue",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Persisted "skip forever"
                TextButton(
                    onClick = onDontShowAgain,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = "Don't show this again",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/** Renders the interactive demo for the current action slide. */
@Composable
private fun TutorialDemoArea(action: TutorialAction, onDone: () -> Unit) {
    when (action) {
        TutorialAction.DrawStroke ->
            PracticePad(PracticePadMode.DRAW, onGestureDone = onDone)
        TutorialAction.EraseStroke ->
            PracticePad(PracticePadMode.ERASE, onGestureDone = onDone)
        TutorialAction.AddLayer ->
            LayerDemoPanel(onLayerAdded = onDone)
        TutorialAction.PickColourMode ->
            ColourModeDemo(onModeSelected = onDone)
        TutorialAction.TypeMarkdown ->
            MarkdownTypeDemo(onTyped = onDone)
    }
}