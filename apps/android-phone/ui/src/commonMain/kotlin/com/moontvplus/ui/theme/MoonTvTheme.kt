package com.moontvplus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 视觉参数对齐 Selene（`lib/services/theme_service.dart` + 各处硬编码色值）：
 * 种子色 `#2C3E50`（标题/主文字）、强调绿 `#27AE60`（主按钮/角标/进度/选中）、
 * 次要文字 `#7F8C8D`、错误红 `#E74C3C`、信息蓝 `#3498DB`。
 * 浅色底 `#F8F9FA`、卡片 `#FFFFFF`；深色底 `#121212`、卡片 `#1E1E1E`。
 */
object SeleneColors {
    /** 强调色 / 主按钮 */
    val Accent = Color(0xFF27AE60)

    /** 鼠标悬停时的浅绿（桌面端） */
    val AccentHover = Color(0xFF52C77A)

    /** 评分圆点 */
    val Rating = Color(0xFFE91E63)

    /** 未选中图标与标签 */
    val InactiveLight = Color(0xFF7F8C8D)
    val InactiveDark = Color(0xFFB0B0B0)

    /** 种子色：标题、主文字 */
    val SeedLight = Color(0xFF2C3E50)

    val BackgroundLight = Color(0xFFF8F9FA)
    val SurfaceLight = Color(0xFFFFFFFF)
    val AppBarLight = Color(0xFFFFFFFF)
    val OutlineLight = Color(0xFFE9ECEF)

    val BackgroundDark = Color(0xFF121212)
    val SurfaceDark = Color(0xFF1E1E1E)
    val AppBarDark = Color(0xFF1E1E1E)
    val OutlineDark = Color(0xFF2C2C2C)

    val ErrorRed = Color(0xFFE74C3C)
    val InfoBlue = Color(0xFF3498DB)

    /** 年份角标底色（#2C3E50 @ 0.8） */
    val YearChip = Color(0xCC2C3E50)
}

private val LightScheme = lightColorScheme(
    primary = SeleneColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCF2E4),
    onPrimaryContainer = Color(0xFF0B3A22),
    secondary = SeleneColors.SeedLight,
    onSecondary = Color.White,
    tertiary = SeleneColors.InfoBlue,
    onTertiary = Color.White,
    background = SeleneColors.BackgroundLight,
    onBackground = SeleneColors.SeedLight,
    surface = SeleneColors.SurfaceLight,
    onSurface = SeleneColors.SeedLight,
    surfaceVariant = Color(0xFFEEF1F4),
    onSurfaceVariant = SeleneColors.InactiveLight,
    outline = SeleneColors.OutlineLight,
    outlineVariant = SeleneColors.OutlineLight,
    error = SeleneColors.ErrorRed,
)

private val DarkScheme = darkColorScheme(
    primary = SeleneColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF14361F),
    onPrimaryContainer = Color(0xFFA9E7BE),
    secondary = Color(0xFFECF0F1),
    onSecondary = SeleneColors.SeedLight,
    tertiary = SeleneColors.InfoBlue,
    onTertiary = Color.White,
    background = SeleneColors.BackgroundDark,
    onBackground = Color.White,
    surface = SeleneColors.SurfaceDark,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = SeleneColors.InactiveDark,
    outline = SeleneColors.OutlineDark,
    outlineVariant = SeleneColors.OutlineDark,
    error = SeleneColors.ErrorRed,
)

@Composable
fun MoonTvTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
