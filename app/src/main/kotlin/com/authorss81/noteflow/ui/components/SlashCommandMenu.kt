package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class SlashCommand(
    val trigger: String,
    val label: String,
    val snippet: String,
    val icon: ImageVector
)

@Composable
fun SlashCommandMenuPopup(
    onSelectCommand: (SlashCommand) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Phase 174: optional "Insert wiki-link" entry that opens the wiki-link
    // suggestion flow instead of appending a static snippet.
    onInsertWikiLink: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme

    val commands = remember {
        listOf(
            SlashCommand("h1", "Heading 1", "# ", Icons.Outlined.Title),
            SlashCommand("h2", "Heading 2", "## ", Icons.Outlined.Title),
            SlashCommand("h3", "Heading 3", "### ", Icons.Outlined.Title),
            SlashCommand("todo", "Checklist Item", "- [ ] ", Icons.Outlined.CheckBox),
            SlashCommand("bullet", "Bullet List", "- ", Icons.Outlined.FormatListBulleted),
            SlashCommand("callout", "Callout Banner", "> [!NOTE]\n> Key message here\n", Icons.Outlined.CallToAction),
            SlashCommand("toggle", "Collapsible Section", "<details>\n<summary>Toggle Title</summary>\n\nHidden details content...\n</details>\n", Icons.Outlined.UnfoldMore),
            SlashCommand("math", "LaTeX Math Block", "$$ e = mc^2 $$\n", Icons.Outlined.Functions),
            SlashCommand("code", "Code Block", "```kotlin\n// Code snippet\n```\n", Icons.Outlined.Code),
            SlashCommand("quote", "Blockquote", "> ", Icons.Outlined.FormatQuote),
            SlashCommand("table", "Markdown Table", "| Header 1 | Header 2 |\n| --- | --- |\n| Cell 1 | Cell 2 |\n", Icons.Outlined.TableChart),
            SlashCommand("divider", "Horizontal Divider", "---\n", Icons.Outlined.HorizontalRule)
        )
    }

    Surface(
        modifier = modifier
            .width(260.dp)
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "Insert Block (Slash Commands)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )

            HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (onInsertWikiLink != null) {
                    item(key = "wikilink") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onInsertWikiLink()
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Link, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Insert Wiki Link", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("[[note title]] — pick from your notes", style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                            }
                        }
                        HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                    }
                }
                items(commands) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectCommand(cmd)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(cmd.icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(cmd.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("/${cmd.trigger}", style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                        }
                    }
                }
            }
        }
    }
}
