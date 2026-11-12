package com.moontvplus.phone.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面端播放器（占位实现）。
 *
 * ExoPlayer 只能在 Android 上跑，桌面端需要接 VLC（libvlc / VLCJ）。
 * 在接上之前，这里如实报告「不支持内嵌播放」，让界面走「用外部播放器打开」的路径，
 * 而不是给用户一块永远黑屏的窗口。
 */
actual fun createPlaybackEngine(): PlaybackEngine = DesktopPlaybackEngine()

class DesktopPlaybackEngine : PlaybackEngine {

    override val supportsInlinePlayback: Boolean = false

    override fun create(): PlaybackController = DesktopPlaybackController()
}

private class DesktopPlaybackController : PlaybackController {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun load(request: PlaybackRequest) {
        _state.value = PlaybackState(
            status = PlaybackStatus.ERROR,
            error = "桌面端暂不支持内嵌播放，请用外部播放器打开这一集",
        )
    }

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun release() = Unit
    override fun currentPositionMs(): Long = 0L
}
