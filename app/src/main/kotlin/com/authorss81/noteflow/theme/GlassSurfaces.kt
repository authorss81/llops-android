package com.authorss81.noteflow.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import com.authorss81.noteflow.services.SettingsManager
import com.authorss81.noteflow.utils.DeviceCompatibilityManager
import com.authorss81.noteflow.utils.DeviceTier

/**
 * Phase 28: gating rules for the GLASS theme's frosted-blur rendering.
 *
 * Compose `Modifier.blur` is backed by RenderEffect, which is only rendered on
 * API 31+ (Android 12); older SDKs silently skip it. It is also GPU-expensive,
 * so LOW_END devices get a translucent (non-blurred) panel by default and the
 * user can opt back in via the blur setting. The decision is pure and
 * unit-testable; it never claims blur where it will not render.
 */
object GlassBlurGate {

    /** RenderEffect-backed Compose blur is API 31+ (Android 12). */
    fun isBlurRenderable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

    /**
     * Full decision: renderable + device tier (LOW_END disabled unless the user
     * opts in) + the persisted user setting.
     */
    fun shouldApplyBlur(
        sdkInt: Int,
        tier: DeviceTier,
        glassBlurEnabled: Boolean
    ): Boolean {
        if (!isBlurRenderable(sdkInt)) return false
        if (!glassBlurEnabled) return false
        if (tier == DeviceTier.LOW_END) return false
        return true
    }
}

/** Phase 34: how a glass surface resolves its fill/edge treatment. */
enum class GlassSurfaceStyle {
    /** Translucent frost + real blur + luminescent edge. (API 31+, not LOW_END.) */
    BLURRED_FROST,

    /** Translucent frost, no blur, luminescent edge. (No-blur capable devices.) */
    TONAL_FROST,

    /** Solid tonal (surfaceContainer) fill, no blur, soft edge. (LOW_END / user-off.) */
    TONAL_SOLID
}

/** Phase 34: pure decision — never claims blur where it won't render, and never
 *  silently degrades: LOW_END devices that cannot blur get a solid tonal panel
 *  (cheapest path), other no-blur devices keep the translucent frost. */
object GlassSurfaceMath {

    fun resolveStyle(
        applyBlur: Boolean,
        tier: DeviceTier,
        tonalTint: Boolean
    ): GlassSurfaceStyle = when {
        applyBlur -> GlassSurfaceStyle.BLURRED_FROST
        tier == DeviceTier.LOW_END -> GlassSurfaceStyle.TONAL_SOLID
        !tonalTint -> GlassSurfaceStyle.TONAL_SOLID
        else -> GlassSurfaceStyle.TONAL_FROST
    }

    /** Theme-responsive edge luminescence (light rim in dark, soft white in light). */
    fun edgeGlowColor(surface: Color): Color =
        if (GlassThemeMath.relativeLuminance(surface) < 0.5) Color.White.copy(alpha = 0.16f)
        else Color.White.copy(alpha = 0.55f)

    /** Theme-responsive depth gradient painted inside the frost. */
    fun depthGradient(surface: Color): List<Color> =
        if (GlassThemeMath.relativeLuminance(surface) < 0.5) {
            listOf(Color.White.copy(alpha = 0.07f), Color.Transparent)
        } else {
            listOf(Color.White.copy(alpha = 0.38f), Color.Transparent)
        }
}

/**
 * Resolves the effective blur decision for the current device + user settings.
 * Pure logic extracted so UI always renders the same frost the tests verify.
 */
@Composable
fun rememberGlassBlurDecision(settings: SettingsManager): Boolean {
    val context = LocalContext.current
    var decision by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(settings.glassBlurEnabled) {
        val tier = DeviceCompatibilityManager.getDeviceTier(context, settings)
        decision = GlassBlurGate.shouldApplyBlur(
            sdkInt = Build.VERSION.SDK_INT,
            tier = tier,
            glassBlurEnabled = settings.glassBlurEnabled
        )
    }
    return decision
}

/**
 * Phase 34: a frosted-glass surface: translucent fill over whatever ambient
 * backdrop is behind it, with a soft outer border, a subtle 0.5 dp inner-edge
 * luminescence, a tonal depth gradient, and (when possible) real `Modifier.blur`.
 *
 * Honest fallbacks (never silent degradation):
 * - API 31+ & not LOW_END & blur enabled  → BLURRED_FROST (real blur).
 * - No blur but capable                   → TONAL_FROST (translucent, no blur).
 * - LOW_END / user disabled blur / tonal  → TONAL_SOLID (solid tonal panel).
 *
 * NOTE: never wrap the drawing canvas with this — the canvas stays fully opaque.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FrostedGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    fillColor: Color? = null,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    blurRadius: Dp = 16.dp,
    applyBlur: Boolean = false,
    tonal: Boolean = true,
    edgeGlow: Boolean = true,
    content: @Composable () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val tier = remember {
        DeviceCompatibilityManager.getDeviceTier(context, SettingsManager(context))
    }
    val style = GlassSurfaceMath.resolveStyle(applyBlur, tier, tonal)

    val effectiveFill = fillColor ?: when (style) {
        GlassSurfaceStyle.BLURRED_FROST -> scheme.surface.copy(alpha = 0.55f)
        GlassSurfaceStyle.TONAL_FROST -> scheme.surfaceVariant.copy(alpha = 0.72f)
        GlassSurfaceStyle.TONAL_SOLID -> scheme.surfaceContainerHigh.copy(alpha = 0.96f)
    }
    val glow = if (edgeGlow) {
        GlassSurfaceMath.edgeGlowColor(effectiveFill)
    } else {
        Color.Transparent
    }
    val gradient = GlassSurfaceMath.depthGradient(effectiveFill)

    var base = modifier
        .clip(shape)
        .background(effectiveFill)
        .background(Brush.verticalGradient(gradient), shape)
        .border(BorderStroke(1.dp, borderColor), shape)

    if (applyBlur) {
        base = base.blur(blurRadius) // BlurredEdge: blurs the backdrop too
    }
    if (edgeGlow) {
        base = base.innerLuminescence(shape, 0.5.dp, glow)
    }

    Box(modifier = base) {
        content()
    }
}

/**
 * Optional phase-34 modifier: draws a 0.5 dp luminescent ring just inside the
 * outer border, giving glass panels a lit inner edge. Purely decorative.
 */
fun Modifier.innerLuminescence(
    shape: Shape,
    width: Dp,
    color: Color
): Modifier = drawWithContent {
    drawContent()
    if (color.alpha <= 0f) return@drawWithContent
    val stroke = width.toPx()
    val rect = Rect(
        left = stroke / 2f,
        top = stroke / 2f,
        right = size.width - stroke / 2f,
        bottom = size.height - stroke / 2f
    )
    if (rect.width <= 0f || rect.height <= 0f) return@drawWithContent
    val radius = when (shape) {
        is RoundedCornerShape -> shape.topStart.toPx(
            shapeSize = Size(size.width, size.height),
            density = this
        )
        else -> 4.dp.toPx()
    }
    drawRoundRect(
        color = color,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = stroke)
    )
}