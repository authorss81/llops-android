package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.GalleryCardActionsPolicy
import com.authorss81.noteflow.services.InkCardPaperPolicy
import com.authorss81.noteflow.services.GalleryCardLayoutPolicy
import com.authorss81.noteflow.services.GalleryTagRowPolicy
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
 * - Card ripple comes from the clickable card surface; phase 208 adds LONG-PRESS
 *   multi-select (the old "no multi-select UI in the app yet" admission is gone):
 *   long-press enters selection mode, taps then toggle membership, and HomeScreen
 *   renders the contextual bulk-action bar (trash/move/tag).
 *
 * No canvas rasterization happens here — the preview is derived purely from the
 * existing title/extractedText/tags/pinned/date fields (per AGENTS.md hardware
 * reality, no image generation, no heavy shadow/blur layers).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryView(
    pages: List<NotePageEntity>,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    onEditTags: (NotePageEntity) -> Unit = {},
    // Phase 208 fix #3: Move-to-Section / Duplicate verbs (wired to HomeScreen's
    // shared section-picker dialog + viewModel.duplicatePage).
    onMoveToSection: (NotePageEntity) -> Unit = {},
    onDuplicate: (NotePageEntity) -> Unit = {},
    // Phase 208 fix #4: multi-select hooks (all default-inert for compatibility).
    selectionActive: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (NotePageEntity) -> Unit = {},
    onEnterSelection: (NotePageEntity) -> Unit = {},
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
                onEditTags = onEditTags,
                onMoveToSection = onMoveToSection,
                onDuplicate = onDuplicate,
                selectionActive = selectionActive,
                isSelected = page.id in selectedIds,
                onToggleSelect = onToggleSelect,
                onEnterSelection = onEnterSelection
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GalleryCardItem(
    page: NotePageEntity,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    onEditTags: (NotePageEntity) -> Unit,
    onMoveToSection: (NotePageEntity) -> Unit,
    onDuplicate: (NotePageEntity) -> Unit,
    selectionActive: Boolean,
    isSelected: Boolean,
    onToggleSelect: (NotePageEntity) -> Unit,
    onEnterSelection: (NotePageEntity) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    // Phase 184: minimum card height from the pure-JVM policy, scaled with the
    // user's font scale so large-font content can never be clipped. It is a
    // heightIn FLOOR, not a strict aspect ratio — content taller than the floor
    // grows the card, and a short title + 1-2 preview lines renders a compact
    // notebook tile with no 60% dead band.
    val minCardHeight = GalleryCardLayoutPolicy.minCardHeightDp(LocalDensity.current.fontScale).dp

    // Phase 188: tag parsing/capping lives entirely in the pure-JVM
    // GalleryTagRowPolicy — at most MAX_VISIBLE_TAGS (2) chips plus a "+N"
    // badge, single line, so the update timestamp below stays visible at
    // 1.3–1.5x font scale. There is deliberately NO inline tag math here.
    val tags = remember(page.tags) {
        GalleryTagRowPolicy.parseTags(page.tags)
    }
    val visibleTags = GalleryTagRowPolicy.visibleChips(tags)
    val hiddenTagCount = GalleryTagRowPolicy.hiddenChipCount(tags)

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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minCardHeight)
            // Phase 236: stable Robo tap target — Compose testTag surfaces as a
            // resource-id in the a11y tree so gcloud Robo can VIEW_CLICKED it.
            .testTag("noteCard")
            // Phase 208 fix #4: tap opens (or toggles selection while a
            // selection is active); LONG-PRESS enters selection mode.
            .combinedClickable(
                onClick = {
                    if (selectionActive) onToggleSelect(page) else onOpenPage(page)
                },
                onLongClick = { if (!selectionActive) onEnterSelection(page) }
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                scheme.secondaryContainer.copy(alpha = 0.55f)
            } else {
                scheme.surfaceVariant.copy(alpha = 0.55f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        // Phase 188 risk #3: an explicit hairline border keeps cards distinct
        // from near-black surfaces in dark themes (surfaceVariant on dark is
        // close to surface; the phase-187 paper fill flattens it further). The
        // width/alpha come from the pure-JVM policy so the decision is pinned.
        // Phase 208 fix #4: a selected card swaps the hairline for a bold
        // primary border so multi-selection reads at a glance.
        border = BorderStroke(
            if (isSelected) 2.dp else GalleryCardLayoutPolicy.GALLERY_CARD_BORDER_WIDTH_DP.dp,
            if (isSelected) {
                scheme.primary
            } else {
                scheme.outlineVariant.copy(alpha = GalleryCardLayoutPolicy.GALLERY_CARD_BORDER_ALPHA)
            }
        )
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
                    // Phase 188 risk #2: the body column shares the SAME
                    // min-height floor as the card. Both are a FLOOR, never a
                    // cap — nothing in this composition fixes a finite height
                    // (only `heightIn(min = ...)`, never a max-bounded variant,
                    // a fixed height() or a ratio), so a growing font scale
                    // grows the card and the date/tags footer below can never
                    // be clipped. That unbounded min-floor structure IS the
                    // footer guarantee (see the preview comment below).
                    .heightIn(min = minCardHeight)
                    .padding(14.dp)
            ) {
                // Header: type badge, title, pinned indicator.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelected) {
                        // Phase 208 fix #4: selection marker replaces the type
                        // badge while the card is selected.
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = scheme.primary
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = "Selected",
                                tint = scheme.onPrimary,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(18.dp)
                            )
                        }
                    } else {
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
                            // Phase 208 fix #3: Move to Section… + Duplicate —
                            // the same verbs the list-view card menu offers.
                            DropdownMenuItem(
                                text = { Text("Move to Section…") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.DriveFileMove,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onMoveToSection(page)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Duplicate") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDuplicate(page)
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
                // scales grow it instead of clipping/overlapping the label.
                // Phase 188 risk #2: `weight(1f, fill = false)` on BOTH preview
                // paths is a DEFENSIVE slack seat, kept because it becomes the
                // enforcement point the day a finite card height is introduced.
                // Under the current unbounded layout it is inert — Compose only
                // redistributes slack through a flex child when the parent's main
                // axis is FINITE, and this card is never height-capped (the Card
                // and body Column share only `heightIn(min = ...)`). The footer
                // visibility guarantee therefore rests on that min-floor +
                // unbounded-height structure (pinned by
                // Phase188GalleryLayoutBoundsTest), not on this weight. fill=false
                // keeps the phase-184 fix: the preview never stretches tall.
                if (preview.isNotEmpty()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        // Line budget owned by GalleryCardLayoutPolicy.PREVIEW_MAX_LINES
                        // (kept as the literal `3` here — the phase-184 pin requires
                        // the literal; Phase188GalleryLayoutBoundsTest cross-checks the
                        // two stay in sync).
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
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
                        // Phase 188 risk #4: single-line Row, chips weighted
                        // (fill=false keeps short pills natural, long ones
                        // ellipsize) with the "+N" badge last and unweighted — a
                        // Row measures unweighted children first, so the badge can
                        // never be pushed out and the update timestamp below
                        // always stays visible.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            visibleTags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = scheme.secondaryContainer.copy(alpha = 0.85f),
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Text(
                                        text = GalleryTagRowPolicy.chipText(tag),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            GalleryTagRowPolicy.hiddenBadgeText(hiddenTagCount)?.let { badgeText ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = scheme.surfaceVariant.copy(alpha = 0.7f)
                                ) {
                                    Text(
                                        text = badgeText,
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
 * captured (no per-frame allocation, no stroke-geometry rasterization — the
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