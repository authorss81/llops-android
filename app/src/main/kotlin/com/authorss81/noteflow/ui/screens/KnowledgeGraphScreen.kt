package com.authorss81.noteflow.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.WikiLinkParser
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GraphNode(
    val page: NotePageEntity,
    var position: Offset,
    var velocity: Offset = Offset.Zero,
    val radius: Float = 28f,
    val color: Color = Color.Unspecified
)

data class GraphEdge(
    val sourceId: String,
    val targetId: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphScreen(
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var searchQuery by remember { mutableStateOf("") }

    var allPages by remember { mutableStateOf<List<NotePageEntity>>(emptyList()) }
    var nodes by remember { mutableStateOf<List<GraphNode>>(emptyList()) }
    var edges by remember { mutableStateOf<List<GraphEdge>>(emptyList()) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    val textMeasurer = rememberTextMeasurer()

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val errorColor = MaterialTheme.colorScheme.error
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    // B2-DOS-11: the edge scan runs via WikiLinkParser.buildWikiLinkEdges — cached
    // per unlock epoch, scan-set capped, on Dispatchers.Default, and cancelled when
    // this LaunchedEffect leaves composition (the panel closes).
    LaunchedEffect(Unit) {
        val active = viewModel.repository.getAllActivePages()
        allPages = active

        val newEdges =
            WikiLinkParser.buildWikiLinkEdges(active).map { GraphEdge(it.sourcePageId, it.targetPageId) }
        edges = newEdges

        // Initialize node positions in an orbital circle layout
        val count = active.size
        if (count > 0) {
            val radius = (count * 60f).coerceAtLeast(250f)
            val initialNodes = active.mapIndexed { index, page ->
                val angle = (2.0 * Math.PI * index / count)
                val cx = (radius * cos(angle)).toFloat()
                val cy = (radius * sin(angle)).toFloat()
                val connectionCount = newEdges.count { it.sourceId == page.id || it.targetId == page.id }
                val nodeRadius = (22f + connectionCount * 6f).coerceAtMost(50f)
                val nodeColor = if (connectionCount > 0) primaryColor else secondaryColor
                GraphNode(
                    page = page,
                    position = Offset(cx, cy),
                    radius = nodeRadius,
                    color = nodeColor
                )
            }
            nodes = initialNodes
        }
    }

    // 29.1: Force-directed graph physics simulation loop.
    // Keyed ONLY on edges loading (not on nodes mutation) to prevent infinite re-keying & 60fps loop restart!
    LaunchedEffect(edges) {
        if (nodes.isEmpty() || edges.isEmpty()) return@LaunchedEffect
        val workingNodes = nodes.toMutableList()
        for (step in 0 until 60) {
            withContext(Dispatchers.Default) {
                val nodeMap = workingNodes.associateBy { it.page.id }

                // 1. Node Repulsion (Coulomb Law)
                for (i in workingNodes.indices) {
                    for (j in i + 1 until workingNodes.size) {
                        val n1 = workingNodes[i]
                        val n2 = workingNodes[j]
                        val dx = n2.position.x - n1.position.x
                        val dy = n2.position.y - n1.position.y
                        val distSq = (dx * dx + dy * dy).coerceAtLeast(100f)
                        val dist = sqrt(distSq.toDouble()).toFloat()
                        val force = 18000f / distSq
                        val fx = (dx / dist) * force
                        val fy = (dy / dist) * force

                        n1.position = n1.position - Offset(fx, fy)
                        n2.position = n2.position + Offset(fx, fy)
                    }
                }

                // 2. Edge Attraction (Hooke Law)
                for (edge in edges) {
                    val source = nodeMap[edge.sourceId]
                    val target = nodeMap[edge.targetId]
                    if (source != null && target != null) {
                        val dx = target.position.x - source.position.x
                        val dy = target.position.y - source.position.y
                        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(1f)
                        val force = (dist - 140f) * 0.05f
                        val fx = (dx / dist) * force
                        val fy = (dy / dist) * force

                        source.position = source.position + Offset(fx, fy)
                        target.position = target.position - Offset(fx, fy)
                    }
                }

                // 3. Centering Gravity
                for (n in workingNodes) {
                    val gx = -n.position.x * 0.02f
                    val gy = -n.position.y * 0.02f
                    n.position = n.position + Offset(gx, gy)
                }
            }

            nodes = workingNodes.map { it.copy() }
            delay(16)
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoomScale = (zoomScale * zoomChange).coerceIn(0.2f, 4f)
        panOffset += panChange
    }

    // Stable states for pointer input
    val currentNodes by rememberUpdatedState(nodes)
    val currentZoomScale by rememberUpdatedState(zoomScale)
    val currentPanOffset by rememberUpdatedState(panOffset)
    val currentSelectedNodeId by rememberUpdatedState(selectedNodeId)
    val currentOnOpenPage by rememberUpdatedState(onOpenPage)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Knowledge Graph", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${nodes.size} Notes · ${edges.size} WikiLinks",
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
            // Search / Highlight overlay
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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

            // Interactive Force-Directed Canvas
            val labelColor = MaterialTheme.colorScheme.onBackground
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("knowledge_graph")
                    .transformable(state = transformState)
                    // 29.2: Key on Unit to prevent pointer input cancellation during physics frames!
                    .pointerInput(Unit) {
                        detectTapGestures { tapOffset ->
                            val center = Offset(size.width.toFloat() / 2f, size.height.toFloat() / 2f)
                            val canvasPos = (tapOffset - center - currentPanOffset) / currentZoomScale

                            val clickedNode = currentNodes.find { node ->
                                val dx = node.position.x - canvasPos.x
                                val dy = node.position.y - canvasPos.y
                                sqrt((dx * dx + dy * dy).toDouble()).toFloat() <= node.radius + 24f
                            }

                            if (clickedNode != null) {
                                if (currentSelectedNodeId == clickedNode.page.id) {
                                    // 29.8: Second tap opens page directly
                                    currentOnOpenPage(clickedNode.page)
                                } else {
                                    // First tap selects node and shows bottom card
                                    selectedNodeId = clickedNode.page.id
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
                    val nodeMap = nodes.associateBy { it.page.id }

                    // Draw Edges (WikiLinks)
                    for (edge in edges) {
                        val source = nodeMap[edge.sourceId]
                        val target = nodeMap[edge.targetId]
                        if (source != null && target != null) {
                            val start = center + source.position
                            val end = center + target.position

                            val isHighlighted = searchQuery.isNotBlank() && (
                                source.page.title.contains(searchQuery, ignoreCase = true) ||
                                target.page.title.contains(searchQuery, ignoreCase = true)
                            )

                            drawLine(
                                color = if (isHighlighted) primaryColor else labelColor.copy(alpha = 0.25f),
                                start = start,
                                end = end,
                                strokeWidth = if (isHighlighted) 3.5f else 1.8f
                            )
                        }
                    }

                    // Draw Nodes
                    for (node in nodes) {
                        val pos = center + node.position
                        val isMatched = searchQuery.isNotBlank() && node.page.title.contains(searchQuery, ignoreCase = true)
                        val isSelected = selectedNodeId == node.page.id

                        val baseNodeColor = if (node.color == Color.Unspecified) primaryColor else node.color
                        val finalColor = when {
                            isSelected -> errorColor
                            isMatched -> tertiaryColor
                            else -> baseNodeColor
                        }

                        // Outer Glow
                        drawCircle(
                            color = finalColor.copy(alpha = 0.25f),
                            radius = node.radius + (if (isSelected || isMatched) 14f else 6f),
                            center = pos
                        )

                        // Main Node Circle
                        drawCircle(
                            color = finalColor,
                            radius = node.radius,
                            center = pos
                        )

                        // Border
                        drawCircle(
                            color = Color.White,
                            radius = node.radius,
                            center = pos,
                            style = Stroke(width = 2.5f)
                        )

                        // Label Text
                        val label = node.page.title.take(18)
                        val measuredText = textMeasurer.measure(
                            text = label,
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = labelColor
                            )
                        )
                        drawText(
                            textLayoutResult = measuredText,
                            topLeft = Offset(pos.x - measuredText.size.width / 2f, pos.y + node.radius + 4f)
                        )
                    }
                }
            }

            // Legend / Floating Node Info
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
                                Text(
                                    node.page.title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Tap again or click button to open note",
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
