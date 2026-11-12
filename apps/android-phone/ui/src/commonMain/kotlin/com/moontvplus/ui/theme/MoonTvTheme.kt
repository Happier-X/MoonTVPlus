package com.moontvplus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 视觉参数对齐 Selene：
 * 种子色 `#2C3E50`、强调绿 `#27AE60`、浅色底 `#F8F9FA`、深色底 `#121212`、
 * 次文本 `#7F8C8D`（浅色）/ `#B0B0B0`（深色）。
 */
object SeleneColors {
    /** 选中态 / 强调色 */
    val Accent = Color(0xFF27AE60)

    /** 鼠标悬停时的浅绿（桌面端） */
    val AccentHover = Color(0xFF52C77A)

    /** 未选中图标与标签 */
    val InactiveLight = Color(0xFF7F8C8D)
    val InactiveDark = Color(0xFFB0B0B0)

    val SeedLight = Color(0xFF2C3E50)
    val BackgroundLight = Color(0xFFF8F9FA)
    val SurfaceLight = Color(0xFFFFFFFF)
    val AppBarLight = Color(0xFFFFFFFF)

    val BackgroundDark = Color(0xFF121212)
    val SurfaceDark = Color(0xFF1E1E1E)
    val AppBarDark = Color(0xFF1E1E1E)

    val ErrorRed = Color(0xFFE74C3C)
}

private val LightScheme = lightColorScheme(
    primary = SeleneColors.SeedLight,
    onPrimary = Color.White,
    secondary = SeleneColors.Accent,
    onSecondary = Color.White,
    background = SeleneColors.BackgroundLight,
    onBackground = SeleneColors.SeedLight,
    surface = SeleneColors.SurfaceLight,
    onSurface = SeleneColors.SeedLight,
    surfaceVariant = Color(0xFFEEF1F4),
    onSurfaceVariant = SeleneColors.InactiveLight,
    error = SeleneColors.ErrorRed,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFECF0F1),
    onPrimary = SeleneColors.SeedLight,
    secondary = SeleneColors.Accent,
    onSecondary = Color.White,
    background = SeleneColors.BackgroundDark,
    onBackground = Color.White,
    surface = SeleneColors.SurfaceDark,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = SeleneColors.InactiveDark,
    error = SeleneColors.ErrorRed,
)

@Composable
fun MoonTvTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
