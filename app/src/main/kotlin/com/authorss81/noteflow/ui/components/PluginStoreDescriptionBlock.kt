package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.services.PluginStoreCardPolicy

/**
 * Phase-127: the per-card description block in the Plugin Store.
 *
 * Collapsed by default — the description renders at
 * [PluginStoreCardPolicy.COLLAPSED_MAX_LINES] with `TextOverflow.Ellipsis`, so a
 * long description can no longer inflate its card to multiple wrapped lines and
 * push the rest of the plugin list off screen. The FULL description is revealed
 * only while the user expands THIS card: tapping the description text OR the
 * small "More"/"Less" chevron toggles the [PluginStoreCardPolicy.Reveal] state
 * (held by the caller, in-memory, per card, only while the dialog is open).
 *
 * The install/enable buttons and the metadata badges are NOT part of this block —
 * they stay above/below it and remain one-tap regardless of the expand state.
 */
@Composable
fun PluginStoreDescriptionBlock(
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val showReveal = PluginStoreCardPolicy.needsExpandToggle(description) || expanded

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else PluginStoreCardPolicy.COLLAPSED_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = if (showReveal) Modifier.clickable(onClick = onToggle) else Modifier
        )
        if (showReveal) {
            val reveal = if (expanded) PluginStoreCardPolicy.Reveal.EXPANDED else PluginStoreCardPolicy.Reveal.COLLAPSED
            Row(
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    PluginStoreCardPolicy.toggleLabel(reveal),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.primary
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    if (expanded) {
                        Icons.Outlined.KeyboardArrowUp
                    } else {
                        Icons.Outlined.KeyboardArrowDown
                    },
                    contentDescription =
                        if (expanded) "Show less" else "Show more",
                    modifier = Modifier.size(14.dp),
                    tint = colorScheme.primary
                )
            }
        }
    }
}