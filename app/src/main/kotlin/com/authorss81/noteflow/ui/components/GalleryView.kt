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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.GalleryCardActionsPolicy
import com.authorss81.noteflow.services.InkCardPaperPolicy
import com.authorss81.noteflow.services.GalleryCardLayoutPolicy
import com.authorss81.noteflow.services.GalleryTitleDisplayPolicy
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 165: Gallery grid. Keeps the [LazyVerticalGrid] layout but tunes the
 * cells and gives each page a real, material card:
 *
 * - Cards are CONTENT-DRIVEN (phase 184: the old rigid `aspectRatio(10f/16f)`
 *   left a >60% dead band for short notes and clipped the footer at large font
 *   scales). The card height is `heightIn(min = GalleryCardLayoutPolicy...)` —
 *   a notebook-tile floor scaled with the user's font scale, never a strict
 *   ratio — so the adaptive grid keeps balanced proportions on phones AND
 *   tablets while short notes render compact tiles and large fonts never clip.
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
    onEditTags: (NotePageEntity) -> Unit = {},
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
                onOpenPage = onOpenPage,
                onEditTags = onEditTags
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryCardItem(
    page: NotePageEntity,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    onEditTags: (NotePageEntity) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    // Phase 184: minimum card height from the pure-JVM policy, scaled with the
    // user's font scale so large-font content can never be clipped. It is a
    // heightIn FLOOR, not a strict aspect ratio — content taller than the floor
    // grows the card, and a short title + 1-2 preview lines renders a compact
    // notebook tile with no 60% dead band.
    val minCardHeight = GalleryCardLayoutPolicy.minCardHeightDp(LocalDensity.current.fontScale).dp

    val tags = remember(page.tags) {
        page.tags.split(",")
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
    }
    val visibleTags = tags.take(3)
    val hiddenTagCount = tags.size - visibleTags.size

    // Phase 187: ink-note cards whose body is real handwriting (no OCR text to
    // preview) get an authentic notebook-paper texture instead of the flat
    // "pencil icon + ink label" stub. The texture is derived ONLY from card
    // size + policy constants — never from stroke geometry (phase-188 risk #1).
    // The px pitches/colors are computed once per composition (not per frame);
    // the drawBehind loop is bounded by InkCardPaperPolicy (≤ 96 dots).
    val preview = page.extractedText?.trim().orEmpty()
    val isInkPage = InkCardPaperPolicy.isInkCanvasPage(page.sourceFileType)
    val showPaperTexture = isInkPage && preview.isEmpty()
    val density = LocalDensity.current
    val paperSpacingPx = with(density) { InkCardPaperPolicy.GRID_SPACING_DP.dp.toPx() }
    val paperDotRadiusPx = with(density) { InkCardPaperPolicy.DOT_RADIUS_DP.dp.toPx() }
    val paperFill = scheme.surface.copy(alpha = InkCardPaperPolicy.PAPER_BACKGROUND_ALPHA)
    val paperDotColor = scheme.outlineVariant.copy(alpha = InkCardPaperPolicy.GRID_ALPHA)

    Card(
        onClick = { onOpenPage(page) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minCardHeight),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .wrapContentHeight()
                .then(
                    if (showPaperTexture) {
                        Modifier.notebookPaper(
                            paperFill = paperFill,
                            dotColor = paperDotColor,
                            spacingPx = paperSpacingPx,
                            dotRadiusPx = paperDotRadiusPx
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            // Subtle primaryContainer wash bleeding down from the top edge. The
            // wash fills whatever the content column measures (matchParentSize);
            // it never drives the card height.
            Box(
                modifier = Modifier
                    .matchParentSize()
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
                    .fillMaxWidth()
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
                        // M3 `Text` in Compose UI 1.7.6 has NO `hyphens` parameter
                        // (unlike a TextStyle property) — it must be applied through
                        // the style, never as a direct `Text(hyphens = ...)` arg.
                        // `lineHeight` lives here too so the typography is one source
                        // of truth; both are sp units, so they scale with the user's
                        // font scale (ratio preserved, no added clipping at 2x).
                        style = MaterialTheme.typography.titleSmall.copy(
                            hyphens = Hyphens.None,
                            lineHeight = 18.sp
                        ),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        modifier = Modifier.weight(1f)
                    )

                    // Phase 186: pinned badge kept ~18dp (compact) + a ~28dp
                    // MoreVert overflow menu with the SAME quick actions the list
                    // view card exposes (togglePinPage / TagEditorDialog via
                    // onEditTags / trashPage). The title takes `weight(1f)`+
                    // ellipsis so the header row still fits the narrow grid column
                    // at 360dp.
                    if (GalleryCardActionsPolicy.showPinnedBadge(page.pinned)) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = GalleryCardActionsPolicy.pinContentDescription(true),
                            tint = scheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }

                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            scrollState = overflowMenuScrollState(),
                            modifier = overflowMenuScrollModifier()
                        ) {
                            DropdownMenuItem(
                                text = { Text(GalleryCardActionsPolicy.pinMenuLabel(page.pinned)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.togglePinPage(page.id, page.pinned)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(GalleryCardActionsPolicy.EDIT_TAGS_LABEL) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Label,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onEditTags(page)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = GalleryCardActionsPolicy.MOVE_TO_TRASH_LABEL,
                                        color = scheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = scheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.trashPage(page.id)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Preview: first lines of text, or a graceful ink placeholder.
                // Content-driven height (phase 184): the preview no longer has a
                // weight(1f) stretch that would soak up the old dead band — the
                // text block is just its own lines (maxLines = 3) and the
                // placeholder is a compact band with an 84dp FLOOR, so large font
                // scales grow it instead of clipping/overlapping the label
                // (review fix: was a fixed 84dp-high unyielding box that
                // overflowed its label at >=2x font scale).
                if (preview.isNotEmpty()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 84.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
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
                                // Phase 187: ink pages carry the honest policy
                                // label ("Handwritten note") — never a claim that
                                // OCR text exists; the other types keep theirs.
                                text = if (isInkPage) {
                                    InkCardPaperPolicy.HANDWRITTEN_LABEL
                                } else {
                                    pageTypeLabel(page)
                                },
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

/**
 * Phase 187 — notebook-paper card texture behind ink-note bodies.
 *
 * Paints a paper fill ([paperFill]) over the card area, then a dot-grid in
 * [dotColor] at [spacingPx] pitch. The geometry comes from the bounded
 * `InkCardPaperPolicy.gridColumns/gridRows` (≤ 12×8 = 96 dots) so the loop is
 * tiny; [spacingPx]/[dotRadiusPx]/colors are computed once per composition and
 * captured (no per-frame allocation, no `pointsJson` rasterization — the
 * texture is pure card-size + constants).
 */
private fun Modifier.notebookPaper(
    paperFill: Color,
    dotColor: Color,
    spacingPx: Float,
    dotRadiusPx: Float
): Modifier = drawBehind {
    drawRect(color = paperFill, size = size)
    val columns = InkCardPaperPolicy.gridColumns(size.width, spacingPx)
    val rows = InkCardPaperPolicy.gridRows(size.height, spacingPx)
    for (row in 0 until rows) {
        val y = spacingPx * (row + 0.5f)
        for (column in 0 until columns) {
            val x = spacingPx * (column + 0.5f)
            drawCircle(color = dotColor, radius = dotRadiusPx, center = Offset(x, y))
        }
    }
}