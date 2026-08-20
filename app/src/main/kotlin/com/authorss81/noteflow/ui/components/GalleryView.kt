package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.GalleryTitleDisplayPolicy
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 165: Gallery grid. Keeps the [LazyVerticalGrid] layout but tunes the
 * cells and gives each page a real, material card:
 *
 * - Cards use a fixed portrait 10:16 ("16:10" family, portrait) aspect ratio so
 *   the adaptive grid keeps balanced proportions on phones AND tablets.
 * - Rounded corners (20 dp), tonal surfaceVariant container with a subtle
 *   primaryContainer wash that fades from the top, gentle 3 dp elevation.
 * - Rich preview: type badge, overflow-ellipsized title, first ~2-3 lines of
 *   page text, pinned indicator, tag chips (max 3) and the updated date.
 * - Card ripple comes from the clickable [Card]; there is no multi-select UI in
 *   the app yet, so no separate selection state is needed.
 *
 * No canvas rasterization happens here — the preview is derived purely from the
 * existing title/extractedText/tags/pinned/date fields (per AGENTS.md hardware
 * reality, no image generation, no heavy shadow/blur layers).
 */
@Composable
fun GalleryView(
    pages: List<NotePageEntity>,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(pages, key = { it.id }) { page ->
            GalleryCardItem(
                page = page,
                viewModel = viewModel,
                onOpenPage = onOpenPage
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryCardItem(
    page: NotePageEntity,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    val tags = remember(page.tags) {
        page.tags.split(",")
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
    }
    val visibleTags = tags.take(3)
    val hiddenTagCount = tags.size - visibleTags.size

    Card(
        onClick = { onOpenPage(page) },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(10f / 16f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle primaryContainer wash bleeding down from the top edge.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                scheme.primaryContainer.copy(alpha = 0.40f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Header: type badge, title, pinned indicator.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = scheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = pageTypeIcon(page),
                            contentDescription = null,
                            tint = scheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(18.dp)
                        )
                    }

                    Text(
                        text = GalleryTitleDisplayPolicy.displayTitle(page.title),
                        style = MaterialTheme.typography.titleSmall.copy(hyphens = Hyphens.None),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f)
                    )

                    if (page.pinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "Pinned",
                            tint = scheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Preview: first lines of text, or a graceful ink placeholder.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val preview = page.extractedText?.trim().orEmpty()
                    if (preview.isNotEmpty()) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(scheme.surface.copy(alpha = 0.6f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = pageTypeIcon(page),
                                    contentDescription = null,
                                    tint = scheme.outline.copy(alpha = 0.7f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = pageTypeLabel(page),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.outline
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                HorizontalDivider(
                    thickness = 1.dp,
                    color = scheme.outlineVariant.copy(alpha = 0.45f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Footer: tag chips, then the updated date.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (visibleTags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            visibleTags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = scheme.secondaryContainer.copy(alpha = 0.85f)
                                ) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (hiddenTagCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = scheme.surfaceVariant.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        text = "+$hiddenTagCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.outline,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = scheme.outline,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = dateFormat.format(Date(page.updatedAt)),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun pageTypeIcon(page: NotePageEntity): ImageVector = when (page.sourceFileType) {
    "pdf" -> Icons.Outlined.PictureAsPdf
    "image" -> Icons.Outlined.Image
    "text" -> Icons.AutoMirrored.Outlined.Article
    else -> Icons.Outlined.Brush
}

private fun pageTypeLabel(page: NotePageEntity): String = when (page.sourceFileType) {
    "pdf" -> "PDF page"
    "image" -> "Image page"
    "text" -> "Empty page"
    else -> "Ink & canvas page"
}