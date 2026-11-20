package com.moontvplus.phone.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.moontvplus.phone.cast.DlnaClient
import com.moontvplus.phone.core.storage.PlayerPreferencesStore
import com.moontvplus.phone.danmaku.DanmakuRepository
import com.moontvplus.phone.di.AppGraph
import com.moontvplus.phone.player.PlayerSession
import com.moontvplus.phone.subtitles.SubtitleRepository
import com.moontvplus.ui.model.PlayRequest
import com.moontvplus.ui.player.PlayerScreen
import com.moontvplus.ui.theme.MoonTvTheme
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import okhttp3.OkHttpClient

/**
 * 桌面播放窗口。
 *
 * 界面与 Android 完全共用 `PlayerScreen`。桌面端还没有内嵌播放内核（VLC 未接），
 * `VideoSurface` 会明确显示「不支持内嵌播放」并给出外部打开入口；
 * 投屏、选集、换源、字幕、弹幕设置、片头片尾等共享能力仍然可用。
 */
@Composable
fun DesktopPlayerWindow(
    request: PlayRequest,
    graph: AppGraph,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 弹幕/字幕/投屏要走设备自己的地址：业务 client 会改写 host 到站点
    val externalClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    val session = remember(request) {
        PlayerSession(
            api = graph.moonApi,
            serverProvider = graph.serverProvider,
            engine = graph.playbackEngine,
            preferencesStore = PlayerPreferencesStore(graph.textStore, graph.json),
            danmakuRepository = DanmakuRepository(externalClient, graph.textStore, graph.json),
            subtitleRepository = SubtitleRepository(externalClient, graph.textStore),
            castClient = DlnaClient(externalClient),
            scope = scope,
        )
    }

    LaunchedEffect(session) {
        // 先置前台，起播才会自动播放
        session.onForeground(true)
        session.load(
            source = request.source,
            videoId = request.videoId,
            title = request.title,
            index = request.index + 1,
            startPositionMs = request.positionMs,
        )
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    MoonTvTheme(darkTheme = true) {
        PlayerScreen(
            controller = session,
            isFullscreen = false,
            isPip = false,
            onBack = onExit,
            onToggleFullscreen = {},
            onEnterPip = {},
            onPickSubtitleFile = {
                chooseSubtitleFile()?.let { session.addSubtitle(it) }
            },
        )
    }
}

private fun chooseSubtitleFile(): String? = runCatching {
    val chooser = JFileChooser().apply {
        dialogTitle = "选择字幕文件"
        fileFilter = FileNameExtensionFilter("字幕文件 (srt / vtt / ass)", "srt", "vtt", "ass", "ssa")
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
}.getOrNull()
