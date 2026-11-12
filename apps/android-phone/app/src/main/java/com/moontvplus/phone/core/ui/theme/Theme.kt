package com.moontvplus.phone.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandGreen = Color(0xFF22C55E)
private val BrandGreenDark = Color(0xFF16A34A)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF052E16),
    secondary = Color(0xFF0EA5E9),
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF111827),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF4B5563),
    error = Color(0xFFDC2626),
)

private val DarkColors = darkColorScheme(
    primary = BrandGreenDark,
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFDCFCE7),
    secondary = Color(0xFF38BDF8),
    surface = Color(0xFF16181C),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE5E7EB),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF23262B),
    onSurfaceVariant = Color(0xFF9CA3AF),
    error = Color(0xFFF87171),
)

@Composable
fun MoonTvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
