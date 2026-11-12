package com.moontvplus.phone.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moontvplus.phone.core.platform.Platform
import com.moontvplus.phone.core.platform.PlatformServices
import com.moontvplus.phone.di.AppGraph
import com.moontvplus.ui.app.MoonTvApp
import com.moontvplus.ui.app.MoonTvController
import com.moontvplus.ui.component.DesktopImageLoader

/** 与 `desktop/build.gradle.kts` 里的默认版本保持一致。 */
private const val APP_VERSION = "1.7.0"

fun main() = application {
    // 共享逻辑层要靠 Platform 拿 UA 与可写目录，必须在建依赖图之前注入
    Platform.init(DesktopPlatformInfo(APP_VERSION))

    val graph = remember { AppGraph(PlatformServices()) }
    val scope = rememberCoroutineScope()
    val controller = remember { MoonTvController(graph, scope) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "MoonTVPlus",
        state = rememberWindowState(width = 1180.dp, height = 800.dp),
    ) {
        // 桌面端海报走共享 OkHttp（带鉴权），入口这里把客户端注入给 UI 层
        LaunchedEffect(graph) { DesktopImageLoader.client = graph.mediaClient }

        MoonTvApp(
            controller = controller,
            darkTheme = true,
        )
    }
}
