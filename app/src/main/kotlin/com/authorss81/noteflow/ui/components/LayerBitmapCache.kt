package com.authorss81.noteflow.ui.components

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap

class LayerBitmapCache(
    val bitmap: ImageBitmap,
    val canvas: Canvas,
    val paint: android.graphics.Paint = android.graphics.Paint().apply { isAntiAlias = true },
    var hash: Int = 0
)
