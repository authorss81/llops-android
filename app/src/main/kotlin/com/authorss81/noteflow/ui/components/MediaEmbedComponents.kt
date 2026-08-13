package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.CanvasMediaEmbed
import com.authorss81.noteflow.data.model.MediaEmbedType
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotoEmbedCard(
    modifier: Modifier = Modifier,
    embed: CanvasMediaEmbed,
    zoomScale: Float = 1f,
    onUpdateCaption: (String) -> Unit,
    onDelete: () -> Unit
) {
    var captionText by remember(embed.textContent) { mutableStateOf(embed.textContent ?: "") }
    var isEditingCaption by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = "Photo Embed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Image Card",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Delete Image", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Image Preview
            // 21.6: bounded decode (samples down) instead of raw decodeFile —
            // huge photos would OOM the heap. 22.3: decode off the main thread —
            // a 12MP+ photo sampled down still takes ~50-200ms.
            var bitmap by remember(embed.contentUrlOrPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(embed.contentUrlOrPath) {
                bitmap = try {
                    val path = embed.contentUrlOrPath
                    if (!path.isNullOrEmpty() && File(path).exists()) {
                        withContext(Dispatchers.IO) { decodeBoundedImage(path) }
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            var rotationAngle by remember { mutableFloatStateOf(0f) }
            var imageScale by remember { mutableFloatStateOf(1f) }

            val loadedBitmap = bitmap
            if (loadedBitmap != null) {
                val state = rememberTransformableState { zoomChange, _, rotationChange ->
                    imageScale = (imageScale * zoomChange).coerceIn(0.5f, 4.0f)
                    rotationAngle += rotationChange
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((embed.height - 60).coerceAtLeast(100f).dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.05f))
                        .transformable(state = state)
                        .clickable { showFullscreen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = loadedBitmap.asImageBitmap(),
                        contentDescription = "Photo Attachment",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = imageScale
                                scaleY = imageScale
                                rotationZ = rotationAngle
                            }
                    )
                }

                // Image Manipulation Controls (Rotate / Zoom / Reset)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { rotationAngle = (rotationAngle - 90f) % 360f },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RotateLeft,
                            contentDescription = "Rotate Counter-Clockwise",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { rotationAngle = (rotationAngle + 90f) % 360f },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RotateRight,
                            contentDescription = "Rotate Clockwise",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { imageScale = (imageScale - 0.2f).coerceIn(0.5f, 4.0f) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ZoomOut,
                            contentDescription = "Zoom Out",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { imageScale = (imageScale + 0.2f).coerceIn(0.5f, 4.0f) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ZoomIn,
                            contentDescription = "Zoom In",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            imageScale = 1f
                            rotationAngle = 0f
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Reset Image Rotation and Scale",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        Text("Photo Attachment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Caption Text
            if (isEditingCaption) {
                OutlinedTextField(
                    value = captionText,
                    onValueChange = {
                        captionText = it
                        onUpdateCaption(it)
                    },
                    label = { Text("Photo Caption") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { isEditingCaption = false }) {
                            Icon(Icons.Outlined.Check, contentDescription = "Save Caption")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isEditingCaption = true }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (captionText.isBlank()) "+ Add image caption..." else captionText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (captionText.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                    )
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit Caption", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    if (showFullscreen) {
        FullscreenImageDialog(path = embed.contentUrlOrPath, onDismiss = { showFullscreen = false })
    }
}

@Composable
fun CodeBlockCard(
    modifier: Modifier = Modifier,
    embed: CanvasMediaEmbed,
    zoomScale: Float = 1f,
    onUpdateCode: (String, String) -> Unit, // textContent, codeLanguage
    onDelete: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var codeText by remember(embed.textContent) { mutableStateOf(embed.textContent ?: "// Enter Kotlin/Python/JS code here\nfun main() {\n    println(\"Hello NoteFlow!\")\n}") }
    var language by remember(embed.codeLanguage) { mutableStateOf(embed.codeLanguage ?: "Kotlin") }
    var showLangMenu by remember { mutableStateOf(false) }

    val languages = listOf("Kotlin", "Python", "JavaScript", "SQL", "Markdown", "JSON", "Java", "C++", "HTML")

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 3.dp,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E293B), // Dark Slate Code Canvas
        modifier = modifier
            .width((embed.width * zoomScale).dp)
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header Bar: Language Picker Chip, Copy Code Button, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = "Code Block",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    // Language Selector
                    Box {
                        AssistChip(
                            onClick = { showLangMenu = true },
                            label = { Text(language, style = MaterialTheme.typography.labelSmall, color = Color.White) },
                            modifier = Modifier.height(26.dp)
                        )
                        DropdownMenu(
                            expanded = showLangMenu,
                            onDismissRequest = { showLangMenu = false }
                        ) {
                            for (lang in languages) {
                                DropdownMenuItem(
                                    text = { Text(lang) },
                                    onClick = {
                                        language = lang
                                        onUpdateCode(codeText, lang)
                                        showLangMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Plain text (no syntax highlighting)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            com.authorss81.noteflow.services.ClipboardGuard.recordCopy()
                            clipboardManager.setText(AnnotatedString(codeText))
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Code", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                    }

                    IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Delete Code Block", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Code Text Field (Monospace Font — plain text, no syntax highlighting)
            OutlinedTextField(
                value = codeText,
                onValueChange = {
                    codeText = it
                    onUpdateCode(it, language)
                },
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFF1F5F9)
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A),
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF334155)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 220.dp)
            )
        }
    }
}
