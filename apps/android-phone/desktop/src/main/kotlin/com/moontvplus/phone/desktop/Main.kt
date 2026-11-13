package com.moontvplus.phone.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import com.moontvplus.ui.model.PlayRequest
import com.moontvplus.ui.model.ShellScreen
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder

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
        // Esc 等同顶栏「返回」：有上一页就退回去，主界面不拦截
        onKeyEvent = { event ->
            val isEscape = event.key == Key.Escape && event.type == KeyEventType.KeyDown
            if (isEscape && controller.screen != ShellScreen.Main) {
                controller.back()
                true
            } else {
                false
            }
        },
    ) {
        // 桌面端海报走共享 OkHttp（带鉴权），入口这里把客户端注入给 UI 层
        LaunchedEffect(graph) {
            DesktopImageLoader.client = graph.mediaClient
            // 桌面端还没有内嵌播放器，详情页上直说清楚
            controller.playHint = "桌面端暂不支持内嵌播放：点播放会用浏览器打开站点播放页，进度由服务端同步。"
            // 临时演示钩子：无鼠标时用它验证页面（MOONTV_DEMO_STEPS=open:片名;back;library）
            if (System.getenv("MOONTV_DEMO_PLAY") == "1") {
                println("[play] " + webPlayUrl(graph, PlayRequest("demo", "42", "demo", 2)))
            }
            runDemoSteps(controller)
            controller.onNeedSearch = { title ->
                val origin = graph.serverProvider.current()?.origin.orEmpty()
                if (origin.isNotBlank()) {
                    openInBrowser(origin + "/search?q=" + URLEncoder.encode(title, "UTF-8"))
                }
            }
        }

        MoonTvApp(
            controller = controller,
            // 默认跟随系统：Selene 的浅色底是 #F8F9FA，不是纯黑
            darkTheme = isSystemInDarkTheme(),
            onPlay = { request -> openInBrowser(webPlayUrl(graph, request)) },
        )
    }
}

/** 站点网页版播放页：`/play?source=&id=&episode=`，episode 从 0 开始。 */
private fun webPlayUrl(graph: AppGraph, request: PlayRequest): String {
    val origin = graph.serverProvider.current()?.origin.orEmpty().trimEnd('/')
    val source = URLEncoder.encode(request.source, "UTF-8")
    val id = URLEncoder.encode(request.videoId, "UTF-8")
    return "$origin/play?source=$source&id=$id&episode=${request.index.coerceAtLeast(0)}"
}

private fun openInBrowser(url: String) {
    if (url.isBlank()) return
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
        }
    }
}
