package com.authorss81.noteflow.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = PaperPrimary,
    onPrimary = PaperOnPrimary,
    primaryContainer = PaperPrimaryContainer,
    onPrimaryContainer = PaperOnPrimaryContainer,
    secondary = PaperSecondary,
    onSecondary = PaperOnSecondary,
    secondaryContainer = Color(0xFFBAE6FD),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFA16207), // Warm Amber
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = PaperBackground,
    onBackground = Color(0xFF1F2937),
    surface = PaperSurface,
    onSurface = Color(0xFF1F2937),
    surfaceVariant = Color(0xFFEDE7DC),
    onSurfaceVariant = Color(0xFF4B4539),
    surfaceDim = Color(0xFFECE8E1),
    surfaceBright = Color(0xFFFFFDF9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2EB),
    surfaceContainer = Color(0xFFF0ECE4),
    surfaceContainerHigh = Color(0xFFEAE5DC),
    surfaceContainerHighest = Color(0xFFE4DFD5),
    outline = Color(0xFF7A7468),
    outlineVariant = Color(0xFFC9C4B9),
    inverseSurface = Color(0xFF1F2937),
    inverseOnSurface = Color(0xFFF9F6F0),
    inversePrimary = Color(0xFFBBD2F7),
    surfaceTint = PaperPrimary,
    scrim = Color(0xFF000000)
)

private val SepiaColorScheme = lightColorScheme(
    primary = SepiaPrimary,
    onPrimary = SepiaOnPrimary,
    primaryContainer = SepiaPrimaryContainer,
    onPrimaryContainer = SepiaOnPrimaryContainer,
    secondary = SepiaSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDE68A),
    onSecondaryContainer = Color(0xFF713F12),
    tertiary = Color(0xFF6C7A3F), // Olive
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECF3D3),
    onTertiaryContainer = Color(0xFF323B16),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = SepiaBackground,
    onBackground = Color(0xFF451A03),
    surface = SepiaSurface,
    onSurface = Color(0xFF451A03),
    surfaceVariant = Color(0xFFEAD9C0),
    onSurfaceVariant = Color(0xFF5F5140),
    surfaceDim = Color(0xFFE8D5B3),
    surfaceBright = Color(0xFFFCF6EA),
    surfaceContainerLowest = Color(0xFFFFFDF7),
    surfaceContainerLow = Color(0xFFF7E9CB),
    surfaceContainer = Color(0xFFEFE0C0),
    surfaceContainerHigh = Color(0xFFE8D7B5),
    surfaceContainerHighest = Color(0xFFE0CEAA),
    outline = Color(0xFF7A6B57),
    outlineVariant = Color(0xFFCBBBA2),
    inverseSurface = Color(0xFF451A03),
    inverseOnSurface = Color(0xFFFBF0D9),
    inversePrimary = Color(0xFFFFD9B3),
    surfaceTint = SepiaPrimary,
    scrim = Color(0xFF000000)
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF0C4A6E),
    secondaryContainer = Color(0xFF075985),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = DarkBackground,
    onBackground = Color(0xFFF8FAFC),
    surface = DarkSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceDim = Color(0xFF0F172A),
    surfaceBright = Color(0xFF334155),
    surfaceContainerLowest = Color(0xFF0B1120),
    surfaceContainerLow = Color(0xFF151F32),
    surfaceContainer = Color(0xFF1E293B),
    surfaceContainerHigh = Color(0xFF283548),
    surfaceContainerHighest = Color(0xFF334155),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFF475569),
    inverseSurface = Color(0xFFF8FAFC),
    inverseOnSurface = Color(0xFF0F172A),
    inversePrimary = Color(0xFF1E3A8A),
    surfaceTint = DarkPrimary,
    scrim = Color(0xFF000000)
)

private val AmoledColorScheme = darkColorScheme(
    primary = AmoledPrimary,
    onPrimary = AmoledOnPrimary,
    primaryContainer = AmoledPrimaryContainer,
    onPrimaryContainer = AmoledOnPrimaryContainer,
    secondary = Color(0xFFA5B4FC),
    onSecondary = Color(0xFF111827),
    secondaryContainer = Color(0xFF3730A3),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF065F46),
    onTertiaryContainer = Color(0xFFA7F3D0),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    background = AmoledBackground,
    onBackground = Color.White,
    surface = AmoledSurface,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF242424),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF333333),
    inverseSurface = Color.White,
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = Color(0xFF0369A1),
    surfaceTint = AmoledPrimary,
    scrim = Color(0xFF000000)
)

/** Phase 28: GLASS (glassmorphism) color scheme — translucent frosted surfaces
 *  over a colorful ambient background. Panel roles are derived from the ambient
 *  with guaranteed on-panel text contrast (see GlassThemeMath). */
private fun glassColorScheme(isDark: Boolean): ColorScheme {
    val ambientBase = if (isDark) GlassAmbientDarkTop else GlassAmbientLightTop
    val roles = GlassThemeMath.derivePanelRoles(ambientBase, isDark)
    val background = if (isDark) GlassAmbientDarkBottom else GlassAmbientLightBottom
    val onBackground = roles.onPanel

    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = if (isDark) Color(0xFF9DB3FF) else Color(0xFF4F6EF7),
        onPrimary = if (isDark) Color(0xFF0B1E3A) else Color.White,
        primaryContainer = if (isDark) Color(0xFF312C7D) else Color(0xFFDCE4FF),
        onPrimaryContainer = if (isDark) Color(0xFFE3E9FF) else Color(0xFF1B2A4A),
        secondary = if (isDark) Color(0xFFB5A8FF) else Color(0xFF7C5CD6),
        onSecondary = if (isDark) Color(0xFF0B1E3A) else Color.White,
        background = background,
        onBackground = onBackground,
        surface = roles.panelFrost,
        onSurface = roles.onPanel,
        surfaceVariant = roles.surfaceElevated,
        onSurfaceVariant = roles.onPanel.copy(alpha = 0.92f),
        surfaceDim = if (isDark) GlassAmbientDarkBottom else GlassAmbientLightTop,
        surfaceBright = roles.surfaceElevated,
        surfaceContainerLowest = background,
        surfaceContainerLow = roles.panelFrost,
        surfaceContainer = roles.surfaceElevated,
        surfaceContainerHigh = roles.surfaceElevated,
        surfaceContainerHighest = if (isDark) roles.surfaceElevated else Color.White.copy(alpha = 0.8f),
        outline = roles.outlineSoft,
        outlineVariant = roles.outlineSoft,
        inverseSurface = roles.onPanel,
        inverseOnSurface = roles.panelSolid,
        inversePrimary = Color(0xFFC9D5FF),
        surfaceTint = roles.onPanel,
        scrim = Color.Black
    )
}

@Composable
private fun schemeFor(mode: AppThemeMode, systemDark: Boolean): ColorScheme {
    return when (mode) {
        AppThemeMode.LIGHT -> LightColorScheme
        AppThemeMode.SEPIA -> SepiaColorScheme
        AppThemeMode.DARK -> DarkColorScheme
        AppThemeMode.AMOLED -> AmoledColorScheme
        AppThemeMode.GLASS -> glassColorScheme(systemDark)
        AppThemeMode.SYSTEM -> if (systemDark) DarkColorScheme else LightColorScheme
        AppThemeMode.DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (systemDark) {
                DarkColorScheme
            } else {
                LightColorScheme
            }
        }
    }
}

@Composable
fun NoteflowTheme(
    themeMode: AppThemeMode = AppThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val colorScheme = schemeFor(themeMode, systemDark)
    val typography = typographyFor(themeMode, systemDark)

    // 22.9: respect the OS reduce-motion / remove-animations setting instead of
    // hardcoding motion on. Re-evaluates when the accessibility state changes.
    val context = LocalContext.current
    var reduceMotion by remember { mutableStateOf(isSystemReduceMotionEnabled(context)) }
    DisposableEffect(context) {
        val accessibilityManager = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager
        val listener = android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener {
            reduceMotion = isSystemReduceMotionEnabled(context)
        }
        accessibilityManager?.addAccessibilityStateChangeListener(listener)
        onDispose {
            accessibilityManager?.removeAccessibilityStateChangeListener(listener)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = Shapes,
            content = content
        )
    }
}
