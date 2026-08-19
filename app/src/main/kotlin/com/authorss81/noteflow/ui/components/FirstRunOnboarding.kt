package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.services.OnboardingPolicy

/**
 * Phase 156: the one-time, non-blocking first-run triage for a passwordless
 * vault. Three short steps (Create note / Draw on ink canvas / Plugins &
 * backup) over a privacy-stance banner; swipe-down or "Skip" dismisses and
 * persists the completion flag so it never auto-shows again. Strictly first-run
 * triage — the phase-125 interactive tutorial stays separate (⋮ → Tutorial).
 * Step switching is instant (no motion), so reduce-motion is satisfied by
 * construction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunOnboardingSheet(
    onCreateNote: () -> Unit,
    onOpenPluginStore: () -> Unit,
    onOpenWebDav: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val steps = OnboardingPolicy.steps
    var currentStep by remember { mutableIntStateOf(0) }
    val lastStep = steps.lastIndex

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Welcome to InkFlow",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Three quick steps to get you started",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Skip intro")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Privacy stance — the honest positioning, always visible above the steps.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = scheme.primaryContainer.copy(alpha = 0.55f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer
                    )
                    Column {
                        Text(
                            OnboardingPolicy.PRIVACY_STANCE_TITLE,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onPrimaryContainer
                        )
                        Text(
                            OnboardingPolicy.PRIVACY_STANCE_BODY,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val step = steps[currentStep]
            val stepIcon = when (currentStep) {
                0 -> Icons.Outlined.Edit
                1 -> Icons.Outlined.Brush
                else -> Icons.Outlined.Extension
            }

            StepIcon(stepIcon)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                step.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step indicator dots.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (i == currentStep) 9.dp else 7.dp)
                            .background(
                                color = if (i == currentStep) scheme.primary else scheme.outlineVariant,
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Per-step action that opens the right screen.
            when (currentStep) {
                0 -> Button(
                    onClick = {
                        onCreateNote()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create your first note")
                }
                1 -> Button(
                    onClick = {
                        onCreateNote()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open a note to draw")
                }
                else -> {
                    Button(
                        onClick = {
                            onOpenPluginStore()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open the Plugin Store")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            onOpenWebDav()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Set up an encrypted backup")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Footer: Skip (non-blocking, persists) + Next/Get started.
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Skip")
                }
                Button(
                    onClick = {
                        if (currentStep < lastStep) {
                            currentStep++
                        } else {
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1.5f)
                ) {
                    Text(if (currentStep == lastStep) "Get started" else "Next")
                }
            }
        }
    }
}

@Composable
private fun StepIcon(icon: ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(scheme.primaryContainer.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = scheme.onPrimaryContainer,
            modifier = Modifier.size(30.dp)
        )
    }
}

