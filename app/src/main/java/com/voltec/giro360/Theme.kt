package com.voltec.giro360

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Identidade visual do Prime360. */
object Prime {
    val Violet = Color(0xFF8B5CF6)
    val Pink = Color(0xFFEC4899)
    val Bg = Color(0xFF0B0B0F)
    val Surface = Color(0xFF17171F)
    val SurfaceHi = Color(0xFF24242E)
    val Record = Color(0xFFEF4444)
    val TextDim = Color(0xFF9A9AA8)

    val Accent: Brush = Brush.horizontalGradient(listOf(Violet, Pink))
}

val Prime360Colors = darkColorScheme(
    primary = Prime.Violet,
    onPrimary = Color.White,
    secondary = Prime.Pink,
    onSecondary = Color.White,
    background = Prime.Bg,
    onBackground = Color.White,
    surface = Prime.Surface,
    onSurface = Color.White,
    surfaceVariant = Prime.SurfaceHi,
    onSurfaceVariant = Color(0xFFD6D6E0),
)
