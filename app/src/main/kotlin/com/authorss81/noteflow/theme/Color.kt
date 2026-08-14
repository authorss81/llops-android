package com.authorss81.noteflow.theme

import androidx.compose.ui.graphics.Color

// Paper Light Theme
val PaperBackground = Color(0xFFF9F6F0)
val PaperSurface = Color(0xFFFFFFFF)
val PaperPrimary = Color(0xFF1E3A8A) // Deep Ink Blue
val PaperOnPrimary = Color(0xFFFFFFFF)
val PaperPrimaryContainer = Color(0xFFDBEAFE)
val PaperOnPrimaryContainer = Color(0xFF1E3A8A)
val PaperSecondary = Color(0xFF0284C7)
val PaperOnSecondary = Color(0xFFFFFFFF)

// Sepia Theme
val SepiaBackground = Color(0xFFFBF0D9)
val SepiaSurface = Color(0xFFF4E4C1)
val SepiaPrimary = Color(0xFF78350F) // Warm Amber Brown
val SepiaOnPrimary = Color(0xFFFFFFFF)
val SepiaPrimaryContainer = Color(0xFFFEF3C7)
val SepiaOnPrimaryContainer = Color(0xFF78350F)
val SepiaSecondary = Color(0xFFB45309)

// Dark Theme
val DarkBackground = Color(0xFF0F172A)
val DarkSurface = Color(0xFF1E293B)
val DarkPrimary = Color(0xFF60A5FA)
val DarkOnPrimary = Color(0xFF0F172A)
val DarkPrimaryContainer = Color(0xFF1E3A8A)
val DarkOnPrimaryContainer = Color(0xFF93C5FD)

// AMOLED Black Theme
val AmoledBackground = Color(0xFF000000)
val AmoledSurface = Color(0xFF121212)
val AmoledPrimary = Color(0xFF38BDF8)
val AmoledOnPrimary = Color(0xFF000000)
val AmoledPrimaryContainer = Color(0xFF0369A1)
val AmoledOnPrimaryContainer = Color(0xFFE0F2FE)

// GLASS (glassmorphism) theme — colorful ambient backgrounds behind translucent
// frosted panels. Light = soft pastel sky; dark = deep indigo/slate night.
val GlassAmbientLightTop = Color(0xFFDFE9FF)
val GlassAmbientLightBottom = Color(0xFFF6E8FF)
val GlassAmbientDarkTop = Color(0xFF251A4E)
val GlassAmbientDarkBottom = Color(0xFF0B1E3A)

enum class AppThemeMode {
    LIGHT, SEPIA, DARK, AMOLED, SYSTEM, DYNAMIC, GLASS
}

/** 22.9: does the effective theme render with dark surfaces? (used for bar-icon polarity).
 *  GLASS follows the system dark state (its ambient gradient has a light and a dark variant). */
fun isAppDarkTheme(mode: AppThemeMode, systemDark: Boolean): Boolean {
    return when (mode) {
        AppThemeMode.LIGHT, AppThemeMode.SEPIA -> false
        AppThemeMode.DARK, AppThemeMode.AMOLED -> true
        AppThemeMode.SYSTEM, AppThemeMode.DYNAMIC, AppThemeMode.GLASS -> systemDark
    }
}
