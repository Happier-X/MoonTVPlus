package com.moontvplus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 视觉参数取自网页版移动端（`https://tv.370125.xyz`）的 computed style：
 * 品牌绿 `#1F8F4F`（站点名、主按钮）、强调绿 `#27AE60`（进度、选集选中）、
 * 文字 `#2C3E50`、次要文字 `#7F8C8D`、浅色底 `#F8F9FA`、深色底 `#121212`、
 * 深色卡片 `#1E1E1E`、描边 `#E4E9EE` / 深色 `#3D4F5D`。
 */
object WebColors {
    /** 品牌绿：站点名、主按钮 */
    val Brand = Color(0xFF1F8F4F)

    /** 强调绿：进度条、选集选中、角标 */
    val Accent = Color(0xFF27AE60)
    val AccentHover = Color(0xFF45C07C)
    val AccentDeep = Color(0xFF1A7340)

    val Ink = Color(0xFF2C3E50)
    val Muted = Color(0xFF7F8C8D)

    val BackgroundLight = Color(0xFFF8F9FA)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceVariantLight = Color(0xFFEEF1F4)
    val OutlineLight = Color(0xFFE4E9EE)

    val BackgroundDark = Color(0xFF121212)
    val SurfaceDark = Color(0xFF1E1E1E)
    val SurfaceVariantDark = Color(0xFF2A2A2A)
    val OutlineDark = Color(0xFF3D4F5D)
    val OnDark = Color(0xFFFFFFFF)
    val OnDarkMuted = Color(0xFF95A5A6)

    val Danger = Color(0xFFE74C3C)
    val Info = Color(0xFF3498DB)

    /** 评分角标（网页右上角粉色圆点） */
    val Rating = Color(0xFFE91E63)

    /** 海报左上角年份角标 `#2C3E50 @0.8` */
    val YearChip = Color(0xCC2C3E50)

    /** 播放器/图片上的黑色遮罩 */
    val Scrim = Color(0x99000000)

    /** 启动页浅色渐变 */
    val SplashGradientLight = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFF8F9FA),
            Color(0xFFF2F6F4),
            Color(0xFFEAF3ED),
            Color(0xFFF8F9FA),
        ),
    )
}

private val LightScheme = lightColorScheme(
    primary = WebColors.Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F3E0),
    onPrimaryContainer = Color(0xFF114A2A),
    secondary = WebColors.Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAFAF0),
    onSecondaryContainer = Color(0xFF1A7340),
    tertiary = WebColors.Info,
    onTertiary = Color.White,
    background = WebColors.BackgroundLight,
    onBackground = WebColors.Ink,
    surface = WebColors.SurfaceLight,
    onSurface = WebColors.Ink,
    surfaceVariant = WebColors.SurfaceVariantLight,
    onSurfaceVariant = WebColors.Muted,
    outline = WebColors.OutlineLight,
    outlineVariant = WebColors.OutlineLight,
    error = WebColors.Danger,
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = WebColors.AccentHover,
    onPrimary = Color(0xFF082F1A),
    primaryContainer = Color(0xFF165C34),
    onPrimaryContainer = Color(0xFFD3F3E0),
    secondary = WebColors.Accent,
    onSecondary = Color.White,
    secondaryContainer = WebColors.SurfaceVariantDark,
    onSecondaryContainer = WebColors.OnDark,
    tertiary = WebColors.Info,
    onTertiary = Color.White,
    background = WebColors.BackgroundDark,
    onBackground = WebColors.OnDark,
    surface = WebColors.SurfaceDark,
    onSurface = WebColors.OnDark,
    surfaceVariant = WebColors.SurfaceVariantDark,
    onSurfaceVariant = WebColors.OnDarkMuted,
    outline = WebColors.OutlineDark,
    outlineVariant = WebColors.OutlineDark,
    error = WebColors.Danger,
    onError = Color.White,
)

@Composable
fun MoonTvTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
