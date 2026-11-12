package com.moontvplus.phone.playback

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR,
}

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.READY
    val ended: Boolean get() = status == PlaybackStatus.ENDED
}

/**
 * 一次播放请求。地址已经由 `EpisodeUrlResolver` 决策完（直链 / 站内代理 / 去广告代理）。
 *
 * [isHls] 决定用哪种媒体源：HLS 需要解析播放列表，渐进式直接读。
 */
data class PlaybackRequest(
    val url: String,
    val isHls: Boolean,
    val startPositionMs: Long = 0L,
)

/**
 * 播放器抽象。
 *
 * 界面只依赖这个接口，不出现任何 ExoPlayer 类型；Android 由 Media3 实现，
 * 桌面端由各自的原生播放器实现（Windows 走 VLC）。
 */
interface PlaybackController {

    val state: StateFlow<PlaybackState>

    /** 载入一集并开始播放（[PlaybackRequest.startPositionMs] 为续播位置）。 */
    fun load(request: PlaybackRequest)

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** 释放底层资源，界面销毁时必须调用。 */
    fun release()

    fun currentPositionMs(): Long
}

/**
 * 平台播放器工厂。
 *
 * [supportsInlinePlayback] 为 false 时（例如桌面端还没接内嵌播放器），
 * 界面应该给出「用外部播放器打开」的入口，而不是显示一块黑屏。
 */
interface PlaybackEngine {
    val supportsInlinePlayback: Boolean
    fun create(): PlaybackController
}

/**
 * 各平台提供具体播放器。
 *
 * Android 的 actual 在 `shared/androidMain`，桌面端的在 `shared/desktopMain`。
 */
expect fun createPlaybackEngine(): PlaybackEngine
