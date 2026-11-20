package com.moontvplus.phone.playback

import kotlinx.coroutines.flow.StateFlow

/** 播放器生命周期状态。与 ExoPlayer 的 `Player.STATE_*` 一一对应，但不暴露 media3 类型。 */
enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR,
}

data class VideoSize(val widthPx: Int = 0, val heightPx: Int = 0) {
    val isKnown: Boolean get() = widthPx > 0 && heightPx > 0

    /** 未知尺寸时按 16:9 排版，避免播放器区域塌陷成 0 高。 */
    val aspectRatio: Float
        get() = if (isKnown) widthPx.toFloat() / heightPx.toFloat() else 16f / 9f
}

enum class TrackType { VIDEO, AUDIO, TEXT }

/**
 * 一条可选择的轨道。
 *
 * [id] 只在本控制器内有效（Android 端是 media3 的 group/track 组合键），
 * UI 只把它当成不透明字符串回传。
 */
data class PlaybackTrack(
    val id: String,
    val type: TrackType,
    val label: String,
    val language: String? = null,
    val isSelected: Boolean = false,
    val isExternal: Boolean = false,
    /** 视频轨的清晰度标记，例如 "1080P"、"720P"；拿不到就为空 */
    val qualityLabel: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val bandwidth: Int = 0,
) {
    val displayLabel: String
        get() = when {
            type == TrackType.VIDEO && !qualityLabel.isNullOrBlank() -> qualityLabel
            label.isNotBlank() -> label
            !language.isNullOrBlank() -> language
            else -> id
        }
}

/**
 * 播放器对外暴露的全部状态。
 *
 * 前四个字段保持既有顺序：历史代码（含测试）用位置参数构造 `PlaybackState`。
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val videoSize: VideoSize = VideoSize(),
    val tracks: List<PlaybackTrack> = emptyList(),
    val selectedVideoTrackId: String? = null,
    val selectedAudioTrackId: String? = null,
    val selectedTextTrackId: String? = null,
    val isLive: Boolean = false,
    /** HLS 直播/无时长源：进度条改成「直播」样式 */
    val hasDuration: Boolean = durationMs > 0L,
) {
    val isReady: Boolean get() = status == PlaybackStatus.READY
    val ended: Boolean get() = status == PlaybackStatus.ENDED

    val videoTracks: List<PlaybackTrack> get() = tracks.filter { it.type == TrackType.VIDEO }
    val audioTracks: List<PlaybackTrack> get() = tracks.filter { it.type == TrackType.AUDIO }
    val textTracks: List<PlaybackTrack> get() = tracks.filter { it.type == TrackType.TEXT }

    val bufferedFraction: Float
        get() = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val positionFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
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
    val autoPlay: Boolean = true,
    /** 起播时要一并挂上的外挂字幕（mimeType 例如 application/x-subrip） */
    val subtitles: List<ExternalSubtitle> = emptyList(),
)

/** 外挂字幕描述。[mimeType] 决定解析器；默认按 SRT 处理（Safari/Android 都能吃）。 */
data class ExternalSubtitle(
    val url: String,
    val mimeType: String = "application/x-subrip",
    val language: String? = null,
    val label: String? = null,
)

/**
 * 播放器能力声明。
 *
 * UI 只渲染 [PlayerCapabilities] 允许的控件：桌面端还没接内嵌播放器时，
 * 进度条、音轨、画中画等入口会整体隐藏，而不是留一排点不动的假按钮。
 */
data class PlayerCapabilities(
    val inlinePlayback: Boolean = true,
    val pictureInPicture: Boolean = false,
    val brightnessGesture: Boolean = false,
    val volumeGesture: Boolean = false,
    val speedControl: Boolean = true,
    val trackSelection: Boolean = false,
    val subtitles: Boolean = false,
    val externalSubtitles: Boolean = false,
    val danmaku: Boolean = true,
    val casting: Boolean = true,
    val fullscreenToggle: Boolean = false,
    val screenLock: Boolean = false,
    val seekBar: Boolean = true,
)

/**
 * 播放器抽象。
 *
 * 界面只依赖这个接口，不出现任何 ExoPlayer 类型；Android 由 Media3 实现，
 * 桌面端由各自的原生播放器实现（Windows 走 VLC）。
 *
 * 新增能力都给了默认空实现，第三方/测试实现不必一次补齐。
 */
interface PlaybackController {

    val state: StateFlow<PlaybackState>

    /** 载入一集并开始播放（[PlaybackRequest.startPositionMs] 为续播位置）。 */
    fun load(request: PlaybackRequest)

    fun play()
    fun pause()
    fun stop() = pause()
    fun seekTo(positionMs: Long)

    /** 倍速，范围由各平台自行夹紧；不支持的实现留空。 */
    fun setSpeed(speed: Float) = Unit

    /** 0f..1f。播放器内部音量（不改系统音量）。 */
    fun setVolume(volume: Float) = Unit

    /**
     * 选择轨道；[trackId] 为 null 时表示关闭该类型（只对字幕有意义）。
     * 返回是否生效。
     */
    fun selectTrack(type: TrackType, trackId: String?): Boolean = false

    /** 运行期追加外挂字幕，返回是否被接受。 */
    fun addExternalSubtitle(subtitle: ExternalSubtitle): Boolean = false

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
    val capabilities: PlayerCapabilities
        get() = PlayerCapabilities(
            inlinePlayback = supportsInlinePlayback,
            speedControl = supportsInlinePlayback,
            seekBar = supportsInlinePlayback,
        )

    fun create(): PlaybackController
}

/**
 * 各平台提供具体播放器。
 *
 * Android 的 actual 在 `shared/androidMain`，桌面端的在 `shared/desktopMain`。
 */
expect fun createPlaybackEngine(): PlaybackEngine
