package com.authorss81.noteflow.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.authorss81.noteflow.services.InlineImagePathPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded decode — mirrors EditorScreen.decodeBoundedBitmap: samples down
 * (inSampleSize) so huge photos never OOM the heap, which the old unscaled
 * BitmapFactory.decodeFile in PhotoEmbedCard could.
 */
fun decodeBoundedImage(path: String, maxDim: Int = 1600): Bitmap? {
    return try {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    } catch (e: Exception) {
        null
    }
}

/**
 * 21.6: fullscreen viewer for any app-internal image path. Tap anywhere to
 * dismiss. Used by markdown inline images and canvas photo embeds.
 */
@Composable
fun FullscreenImageDialog(path: String?, onDismiss: () -> Unit) {
    if (path == null) return
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decodeBoundedImage(path, maxDim = 2400) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Image preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
            } else {
                Text(
                    "Image unavailable",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * 21.6: inline image rendered from markdown `![alt](path)`. Resolves
 * relative paths against the page's source-file directory, decodes bounded
 * and opens the fullscreen viewer on tap.
 *
 * B1-AUTH-04 (phase-68): resolution is confined to the app-private subtree by
 * [InlineImagePathPolicy] — absolute and `..`-traversing destinations never
 * resolve, so a crafted note cannot read-and-display arbitrary files the
 * process can access.
 */
@Composable
fun MarkdownInlineImage(
    destination: String?,
    alt: String?,
    baseDir: File?,
    modifier: Modifier = Modifier
) {
    var showFullscreen by remember { mutableStateOf(false) }
    val resolvedPath = remember(destination, baseDir) {
        InlineImagePathPolicy.resolve(destination, baseDir)?.absolutePath
    }
    var bitmap by remember(resolvedPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(resolvedPath) {
        bitmap = resolvedPath?.let { path ->
            withContext(Dispatchers.IO) { decodeBoundedImage(path, maxDim = 1600) }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = alt ?: "Inline image",
            contentScale = ContentScale.Fit,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { showFullscreen = true }
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Image: ${alt ?: destination ?: "(unknown)"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (resolvedPath == null && !destination.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "File not found: $destination",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showFullscreen) {
        FullscreenImageDialog(path = resolvedPath, onDismiss = { showFullscreen = false })
    }
}
