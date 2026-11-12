package com.moontvplus.phone.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moontvplus.phone.core.platform.Platform
import com.moontvplus.phone.core.platform.PlatformServices
import com.moontvplus.phone.di.AppGraph
import androidx.compose.runtime.rememberCoroutineScope

/** 与 `desktop/build.gradle.kts` 里的默认版本保持一致。 */
private const val APP_VERSION = "1.6.0"

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

fun main() = application {
    // 共享逻辑层要靠 Platform 拿 UA 与可写目录，必须在建依赖图之前注入
    Platform.init(DesktopPlatformInfo(APP_VERSION))

    val graph = remember { AppGraph(PlatformServices()) }
    val scope = rememberCoroutineScope()
    val controller = remember { DesktopController(graph, scope) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "MoonTVPlus",
        state = rememberWindowState(width = 1180.dp, height = 800.dp),
    ) {
        MaterialTheme(colorScheme = DarkColors) {
            Surface(modifier = Modifier.fillMaxSize()) {
                DesktopApp(controller)
            }
        }
    }
}
