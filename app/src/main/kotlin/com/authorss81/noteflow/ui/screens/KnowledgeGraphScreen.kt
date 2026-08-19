package com.authorss81.noteflow.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.services.graph.GraphEdgeRef
import com.authorss81.noteflow.services.graph.GraphLayoutMath
import com.authorss81.noteflow.services.graph.GraphPhysicsConfig
import com.authorss81.noteflow.services.graph.GraphTierSelector
import com.authorss81.noteflow.services.graph.GraphVertex
import com.authorss81.noteflow.services.graph.KnowledgeGraphEdgePolicy
import com.authorss81.noteflow.theme.LocalReduceMotion
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import com.authorss81.noteflow.utils.DeviceCompatibilityManager
import com.authorss81.noteflow.utils.DeviceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GraphNode(
    val page: NotePageEntity,
    /** Orbital start position (before the physics layout settles). */
    val start: Offset,
    /** Final deterministic force-directed position. */
    val end: Offset,
    val radius: Float,
    val clusterId: Int,
    val color: Color
)

data class GraphEdge(
    val sourceId: String,
    val targetId: String
)

/**
 * Phase 38 (Knowledge Graph & Spatial Navigation): the graph is now a
 * deterministic spatial universe —
 *
 *  * **Clusters** — pages are grouped by tag communities + wikilink
 *    communities (union-find, [GraphLayoutMath.assignClusters]) and coloured
 *    per cluster so the "universe" reads at a glance.
 *  * **Tag filters** — a chip row shows/hides (dims) nodes by tag, AND/OR
 *    combination, without re-running physics.
 *  * **Link pulses** — edges touching the selected node (or matching the search
 *    text) carry a moving particle pulse; disabled under reduce-motion.
 *  * **Collision bounding** — layout runs through
 *    [GraphLayoutMath.resolveCollisionsAndBounds] so nodes push apart and all
 *    edges stay inside the world box.
 *  * **Low-RAM fallback** — on LOW_END devices (or when the vault exceeds the
 *    cull cap) physics iterations and node count are reduced via
 *    [GraphTierSelector]; the reduction is surfaced in a small, non-alarming
 *    notice, never silent.
 *
 * Physics constants live in [GraphPhysicsConfig] (single source of truth).
 * The layout is computed ONCE off the main thread (deterministic); the UI only
 * tweens from the orbital start to the settled layout, then goes idle — far
 * cheaper than a live per-frame N² sim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphScreen(
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current

    var searchQuery by remember { mutableStateOf("") }
    var filterTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var requireAllTags by remember { mutableStateOf(true) }
    var lowEndFallback by remember { mutableStateOf(false) }
    var lowEndNotice by remember { mutableStateOf(false) }

    var allPages by remember { mutableStateOf<List<NotePageEntity>>(emptyList()) }
    var nodes by remember { mutableStateOf<List<GraphNode>>(emptyList()) }
    var edges by remember { mutableStateOf<List<GraphEdge>>(emptyList()) }
    var allTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val layoutProgress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    // Reading layoutProgress.value inside the Canvas draw lambda is snapshot-
    // observed by Compose, so the canvas redraws each settle-tween frame.

    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    // Cluster colour ladder — small, deterministic, tonal (Compose primitives,
    // no image assets). Index = clusterId % size. Read the scheme at
    // composition level (MaterialTheme.colorScheme is a @Composable getter, so
    // it can't run inside the remember lambda).
    val scheme = MaterialTheme.colorScheme
    val clusterColors = remember(scheme) {
        listOf(
            scheme.primary,
            scheme.tertiary,
            scheme.secondary,
            scheme.primaryContainer,
            scheme.tertiaryContainer,
            scheme.secondaryContainer
        )
    }

    // 29.1 + Phase 38: build the graph once. Edge scan runs via
    // WikiLinkParser.buildWikiLinkEdges (cached per unlock epoch, scan-set
    // capped). Physics layout is deterministic pure-JVM math.
    LaunchedEffect(Unit) {
        // R2-b2b1-UI-01 (phase-134): getAllActivePages decrypts the WHOLE vault —
        // seconds on big vaults. It was a bare repository call: a lock() disposing
        // the pool mid-decrypt threw an uncaught closed-pool ISE inside this
        // composition scoped coroutine. Now the read is guarded (armed-empty +
        // notice) and the results are only applied while the auth gate is still up.
        val active = viewModel.loadAllActivePages()
        if (!viewModel.authenticated.value) return@LaunchedEffect
        allPages = active

        // Device tier → physics workload. This is the phase-38 low-end lever.
        val tier = DeviceCompatibilityManager.getDeviceTier(context, viewModel.settings)
        val lowEnd = tier == DeviceTier.LOW_END
        val profile = GraphTierSelector.profileFor(lowEnd, active.size)

        // Deterministic cull BEFORE layout: keep the most recent [cap] pages so
        // an enormous vault still settles in time on weak hardware.
        val keptIds = GraphTierSelector.cullToCap(
            active.map { it.id to it.updatedAt },
            profile.nodeCap
        )
        lowEndFallback = active.size > keptIds.size
        lowEndNotice = lowEndFallback || lowEnd

        val kept = active.filter { it.id in keptIds }
        val pageUpdatedAt = kept.associate { it.id to it.updatedAt }

        // R2-b2b5-FEA-01 (phase-152): the rendered edge set is built ONLY over
        // pairs whose BOTH endpoints survived the node cull, deduped, then
        // capped to a tiered top-K by recency — never the whole-vault edge set
        // (~10⁶ edges for a ~2k-page interlinked vault → frozen UI per frame).
        // The culled list feeds BOTH the draw loops and the physics layout.
        val edgeBudget = KnowledgeGraphEdgePolicy.edgeCapFor(lowEnd, kept.size)
        val wikiEdges = WikiLinkParser.buildWikiLinkEdges(
            active,
            // B1-AUTH-05 (phase-69): legacy source-file reads are confined to the
            // app-private imports root.
            ImportExportService.getImportsDir(context)
        )
        val culledEdges = KnowledgeGraphEdgePolicy.cullEdgesToSurvivors(
            wikiEdges.map { GraphEdgeRef(it.sourcePageId, it.targetPageId) },
            keptIds,
            pageUpdatedAt,
            edgeBudget
        )
        val graphEdges = culledEdges.map { GraphEdge(it.sourceId, it.targetId) }
        edges = graphEdges
        val edgeRefs = culledEdges

        // Aggregate tag chips from the indexed tags column only — never a fresh
        // full-text scan.
        val tags = active
            .flatMap { it.tags.split(',').map(String::trim).filter(String::isNotEmpty) }
            .distinct()
            .sorted()
        allTags = tags

        // Cluster communities (tags + wikilinks) for the KEPT page set.
        val clusterMap = GraphLayoutMath.assignClusters(
            kept.map {
                it.id to it.tags.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            },
            edgeRefs
        )

        val count = kept.size
        if (count == 0) return@LaunchedEffect

        // Starting positions: deterministic orbital ring.
        val ringRadius = (count * 52f).coerceAtLeast(240f)
            .coerceAtMost(GraphPhysicsConfig.BOUNDS_HALF_EXTENT)
        val starting = kept.mapIndexed { index, page ->
            val angle = 2.0 * PI * index / count
            GraphVertex(
                id = page.id,
                x = (ringRadius * cos(angle)).toFloat(),
                y = (ringRadius * sin(angle)).toFloat(),
                clusterId = clusterMap[page.id] ?: 0
            )
        }

        // Deterministic layout (repulsion + spring + gravity + collision bounds)
        // OFF the main thread.
        val settled = withContext(Dispatchers.Default) {
            GraphLayoutMath.layout(starting, edgeRefs, profile.iterations)
        }
        val settledByPage = settled.associateBy { it.id }

        val placed = kept.map { page ->
            val startV = starting.first { it.id == page.id }
            val v = settledByPage[page.id] ?: startV
            val connectionCount = edgeRefs.count { it.sourceId == page.id || it.targetId == page.id }
            val radius = (22f + connectionCount * 5f).coerceAtMost(52f)
            val clusterId = clusterMap[page.id] ?: 0
            GraphNode(
                page = page,
                start = Offset(startV.x, startV.y),
                end = Offset(v.x, v.y),
                radius = radius,
                clusterId = clusterId,
                color = clusterColors[clusterId % clusterColors.size]
            )
        }
        nodes = placed

        layoutProgress.snapTo(0f)
        if (!reduceMotion) {
            launch {
                layoutProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 900, easing = LinearEasing)
                )
            }
        } else {
            layoutProgress.snapTo(1f)
        }
    }

    // Relayout: re-run physics from the current endpoint without resetting
    // pan/zoom (Bolt button in the top bar).
    var relayoutTicket by remember { mutableIntStateOf(0) }
    LaunchedEffect(relayoutTicket) {
        if (relayoutTicket == 0 || nodes.isEmpty()) return@LaunchedEffect
        val reduce = reduceMotion
        val edgeRefs = edges.map { GraphEdgeRef(it.sourceId, it.targetId) }
        val tier = DeviceCompatibilityManager.getDeviceTier(context, viewModel.settings)
        val profile = GraphTierSelector.profileFor(tier == DeviceTier.LOW_END, nodes.size)
        val current = nodes.map { GraphVertex(id = it.page.id, x = it.end.x, y = it.end.y) }
        val settled = withContext(Dispatchers.Default) {
            GraphLayoutMath.layout(current, edgeRefs, profile.iterations)
        }
        val settledByPage = settled.associateBy { it.id }
        nodes = nodes.map { n ->
            val settledV = settledByPage[n.page.id]
            val targetX = settledV?.x ?: n.end.x
            val targetY = settledV?.y ?: n.end.y
            n.copy(start = n.end, end = Offset(targetX, targetY))
        }
        layoutProgress.snapTo(0f)
        if (!reduce) {
            launch {
                layoutProgress.animateTo(1f, animationSpec = tween(600, easing = LinearEasing))
            }
        } else {
            layoutProgress.snapTo(1f)
        }
    }

    // Link particle-pulse phase — only actively informed when pulses are drawn.
    val pulsePhase = rememberInfiniteTransition(label = "linkPulse")
    val pulseT by pulsePhase.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "linkPulseT"
    )

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoomScale = (zoomScale * zoomChange).coerceIn(0.2f, 4f)
        panOffset += panChange
    }

    // Stable refs for the pointer/Canvas lambdas (29.2: keep pointer input
    // stable so the settle tween never cancels gesture detection).
    val currentSelectedNodeId by rememberUpdatedState(selectedNodeId)
    val currentOnOpenPage by rememberUpdatedState(onOpenPage)
    val currentPanOffset by rememberUpdatedState(panOffset)

    val isFilteredOut: (NotePageEntity) -> Boolean = remember(filterTags, requireAllTags) {
        { page ->
            if (filterTags.isEmpty()) return@remember false
            val pageTags = page.tags.split(',').map(String::trim).filter(String::isNotEmpty)
                .map { it.lowercase() }.toSet()
            val selected = filterTags.map { it.lowercase() }.toSet()
            val match = if (requireAllTags) selected.all { it in pageTags }
            else selected.any { it in pageTags }
            !match
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Knowledge Graph", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${nodes.size} Notes · ${edges.size} WikiLinks" +
                                if (lowEndFallback) " · reduced for low-RAM devices" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { relayoutTicket++ }) {
                        Icon(Icons.Outlined.Bolt, contentDescription = "Re-run physics layout")
                    }
                    IconButton(onClick = {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    }) {
                        Icon(Icons.Outlined.CenterFocusWeak, contentDescription = "Reset View")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
        ) {
            Column(Modifier.fillMaxSize()) {
                // Search box (29.1 kept).
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search nodes in graph...") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // AND/OR + clear row for an active tag filter.
                if (filterTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (requireAllTags) "all of" else "any of",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { requireAllTags = !requireAllTags }) {
                            Text(
                                if (requireAllTags) "AND" else "OR",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { filterTags = emptySet() }) {
                            Text("Clear", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // Tag filter chips (Phase 38).
                if (allTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        allTags.take(24).forEach { tag ->
                            FilterChip(
                                selected = tag in filterTags,
                                onClick = {
                                    filterTags = if (tag in filterTags) filterTags - tag
                                    else filterTags + tag
                                },
                                label = { Text("#$tag", maxLines = 1) }
                            )
                        }
                    }
                }

                // Interactive force-directed canvas.
                val labelColor = MaterialTheme.colorScheme.onBackground
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("knowledge_graph")
                        .transformable(state = transformState)
                        .pointerInput(Unit) {
                            detectTapGestures { tapOffset ->
                                val center = Offset(size.width.toFloat() / 2f, size.height.toFloat() / 2f)
                                val canvasPos = (tapOffset - center - currentPanOffset) / zoomScale
                                var hit: GraphNode? = null
                                for (n in nodes) {
                                    val p = Offset(
                                        n.start.x + (n.end.x - n.start.x) * layoutProgress.value,
                                        n.start.y + (n.end.y - n.start.y) * layoutProgress.value
                                    )
                                    val dx = p.x - canvasPos.x
                                    val dy = p.y - canvasPos.y
                                    if (sqrt(dx * dx + dy * dy) <= n.radius + 24f) {
                                        hit = n
                                        break
                                    }
                                }
                                if (hit != null) {
                                    if (currentSelectedNodeId == hit.page.id) {
                                        currentOnOpenPage(hit.page)
                                    } else {
                                        selectedNodeId = hit.page.id
                                    }
                                } else {
                                    selectedNodeId = null
                                }
                            }
                        }
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoomScale,
                                scaleY = zoomScale,
                                translationX = panOffset.x,
                                translationY = panOffset.y
                            )
                    ) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val progress = layoutProgress.value
                        val shownPositions = HashMap<String, Offset>(nodes.size)
                        val nodeById = HashMap<String, GraphNode>(nodes.size)
                        for (n in nodes) {
                            nodeById[n.page.id] = n
                            shownPositions[n.page.id] = Offset(
                                n.start.x + (n.end.x - n.start.x) * progress,
                                n.start.y + (n.end.y - n.start.y) * progress
                            )
                        }

                        // Edges — dimmed when either endpoint is filtered out.
                        // R2-b2b5-FEA-01 (phase-152): the tag-filter verdict is
                        // memoized per page so the per-frame edge loop (and the
                        // pulse loop below) never re-splits a page's tag string
                        // for every incident edge.
                        val selectedId = selectedNodeId
                        val filteredById = HashMap<String, Boolean>(nodes.size)
                        fun pageFiltered(id: String, node: GraphNode): Boolean =
                            filteredById.getOrPut(id) { isFilteredOut(node.page) }
                        for (edge in edges) {
                            val src = nodeById[edge.sourceId] ?: continue
                            val tgt = nodeById[edge.targetId] ?: continue
                            val srcFiltered = pageFiltered(edge.sourceId, src)
                            val tgtFiltered = pageFiltered(edge.targetId, tgt)
                            val isHighlighted = searchQuery.isNotBlank() && (
                                src.page.title.contains(searchQuery, ignoreCase = true) ||
                                    tgt.page.title.contains(searchQuery, ignoreCase = true)
                                )
                            val isSelected = selectedId == edge.sourceId || selectedId == edge.targetId
                            val alpha = when {
                                srcFiltered || tgtFiltered -> 0.06f
                                isSelected -> 0.9f
                                isHighlighted -> 0.75f
                                else -> 0.25f
                            }
                            drawLine(
                                color = labelColor.copy(alpha = alpha),
                                start = center + shownPositions.getValue(edge.sourceId),
                                end = center + shownPositions.getValue(edge.targetId),
                                strokeWidth = if (isSelected || isHighlighted) 3.2f else 1.8f,
                                cap = StrokeCap.Round
                            )
                        }

                        // Pulsing "particles" along links touching the selected
                        // node — disabled under reduce-motion.
                        if (!reduceMotion && selectedId != null) {
                            for (edge in edges) {
                                if (edge.sourceId != selectedId && edge.targetId != selectedId) continue
                                val a = center + (shownPositions[edge.sourceId] ?: continue)
                                val b = center + (shownPositions[edge.targetId] ?: continue)
                                for (k in 0 until 3) {
                                    val f = (pulseT + k / 3f) % 1f
                                    val p = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                                    drawCircle(color = tertiaryColor, radius = 3.4f, center = p)
                                }
                            }
                        }

                        // Nodes.
                        for (n in nodes) {
                            val matched = searchQuery.isNotBlank() &&
                                n.page.title.contains(searchQuery, ignoreCase = true)
                            val isSelected = selectedId == n.page.id
                            val filteredOut = pageFiltered(n.page.id, n)
                            val fade = if (filteredOut) 0.12f else 1f
                            val finalColor = when {
                                isSelected -> errorColor
                                matched -> tertiaryColor
                                else -> n.color
                            }
                            val pos = center + shownPositions.getValue(n.page.id)

                            drawCircle(
                                color = finalColor.copy(alpha = 0.25f * fade),
                                radius = n.radius + (if (isSelected || matched) 14f else 6f),
                                center = pos
                            )
                            drawCircle(
                                color = finalColor.copy(alpha = fade),
                                radius = n.radius,
                                center = pos
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = fade),
                                radius = n.radius,
                                center = pos,
                                style = Stroke(width = 2.5f)
                            )

                            val label = n.page.title.take(18)
                            val measured = textMeasurer.measure(
                                text = label,
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    color = labelColor.copy(alpha = fade)
                                )
                            )
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(
                                    pos.x - measured.size.width / 2f,
                                    pos.y + n.radius + 4f
                                )
                            )
                        }
                    }
                }
            }

            // Low-end fallback notice — clear, non-alarming, one line.
            if (lowEndNotice) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (lowEndFallback) {
                                "Showing the ${nodes.size} most recent notes for this device's memory."
                            } else {
                                "Reduced physics for this device."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { lowEndNotice = false }) {
                            Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Legend / floating node info.
            selectedNodeId?.let { id ->
                val node = nodes.find { it.page.id == id }
                if (node != null) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .fillMaxWidth(0.9f),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(12.dp)
                                            .background(node.color, RoundedCornerShape(6.dp))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(node.page.title, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    "Cluster ${node.clusterId} · tap again to open note",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Button(onClick = { onOpenPage(node.page) }) {
                                Text("Open Note")
                            }
                        }
                    }
                }
            }
        }
    }
}