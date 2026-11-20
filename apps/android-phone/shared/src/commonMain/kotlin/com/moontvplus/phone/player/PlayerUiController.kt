package com.moontvplus.phone.player

import com.moontvplus.phone.cast.CastDevice
import com.moontvplus.phone.cast.CastState
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.storage.PlayerPreferences
import com.moontvplus.phone.core.storage.SkipSegment
import com.moontvplus.phone.core.storage.SubtitleAppearance
import com.moontvplus.phone.danmaku.DanmakuState
import com.moontvplus.phone.playback.AutoNextCountdown
import com.moontvplus.phone.playback.PlaybackRequest
import com.moontvplus.phone.playback.PlaybackState
import com.moontvplus.phone.playback.PlayerCapabilities
import com.moontvplus.phone.playback.TrackType
import kotlinx.coroutines.flow.StateFlow

/** 播放器底部的面板入口。纯枚举，界面与状态层共用。 */
enum class PlayerPanel {
    NONE, EPISODES, SOURCES, SPEED, TRACKS, SUBTITLES, DANMAKU, CAST, SETTINGS,
}

/** 换源面板里的一项。 */
data class PlayerSourceOption(
    val key: String,
    val label: String,
    val isCurrent: Boolean = false,
)

/** 选集面板里的一项。 */
data class PlayerEpisodeOption(
    val index: Int,
    val title: String,
    val isCurrent: Boolean = false,
)

/**
 * 播放页的全部业务状态。Android 与桌面共用，界面层只读它。
 *
 * 字段刻意保持与旧 `PlayerViewModel.UiState` 兼容（`title/index/source/detail/loading/error`），
 * 这样起播失败自动换源、进度同步这些既有行为不用重写。
 */
data class PlayerScreenState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val detail: SearchResult? = null,
    val index: Int = 1,
    val source: SearchResult? = null,
    val findingSources: Boolean = false,
    val sources: List<SearchResult> = emptyList(),
    val sourceError: String? = null,
    /** 换源面板的展示项（已排序、标出当前源） */
    val sourceOptions: List<PlayerSourceOption> = emptyList(),
    val episodeOptions: List<PlayerEpisodeOption> = emptyList(),
    val episodeTitle: String = "",
    val autoNext: AutoNextCountdown = AutoNextCountdown.Off,
    /** 当前集记忆的片头/片尾 */
    val intro: SkipSegment? = null,
    val outro: SkipSegment? = null,
    val showSkipIntroButton: Boolean = false,
    val showSkipOutroButton: Boolean = false,
    val autoSkipIntro: Boolean = true,
    val autoSkipOutro: Boolean = false,
    val bufferingHint: String? = null,
    val weakNetwork: Boolean = false,
    /** 一次性提示（标记片头成功、字幕加载失败等），界面展示后调用 clearToast */
    val toast: String? = null,
    /** 投屏可用性由会话层判定：Android 才能发现设备，桌面端直接标不可用 */
    val castSupported: Boolean = true,
) {
    val episodeCount: Int get() = maxOf(detail?.episodesCount ?: 0, index)
    val sourceLabel: String
        get() = (source ?: detail)?.let { it.sourceName.ifBlank { it.source } }.orEmpty()

    val hasNext: Boolean get() = episodeCount > index
    val hasPrevious: Boolean get() = index > 1

    /** 首播定标题：详情页给的名字优先，其次搜索页带来的标题。 */
    val displayTitle: String get() = title.ifBlank { detail?.title.orEmpty() }
}

/**
 * 播放页控制器。
 *
 * UI 只依赖这个接口；实现是 `shared/jvmSharedMain` 的 `PlayerSession`
 * （Android / 桌面共用），测试里可以换成假实现。
 */
interface PlayerUiController {

    val state: StateFlow<PlayerScreenState>
    val playback: StateFlow<PlaybackState>
    val danmaku: StateFlow<DanmakuState>
    val cast: StateFlow<CastState>
    val subtitle: StateFlow<SubtitleUiState>
    val preferences: StateFlow<PlayerPreferences>
    val capabilities: PlayerCapabilities
    /** 宿主注入的系统能力（亮度/音量/画中画）；没有注入时为 null，相关手势不会出现。 */
    val platform: com.moontvplus.phone.playback.PlayerPlatform?

    // ---- 播放控制 ----
    fun togglePlay()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun setSpeed(speed: Float)
    /** 播放器内部音量；手势调的是系统音量，见 [setSystemVolume] */
    fun setVolume(volume: Float)
    /** 长按 2 倍速：临时加速，不改持久化偏好 */
    fun beginSpeedBoost()
    fun endSpeedBoost()

    // ---- 系统级手势 ----
    fun setBrightness(value: Float)
    fun setSystemVolume(value: Float)
    fun enterPictureInPicture(): Boolean

    // ---- 剧集 / 片源 ----
    fun retry()
    fun goTo(index: Int)
    fun playNext()
    fun chooseSource(sourceKey: String)
    fun findSources()
    fun cancelAutoNext()

    // ---- 轨道 / 字幕 ----
    fun selectTrack(type: TrackType, trackId: String?)
    /**
     * 加载外挂字幕。[url] 支持 http(s)、content://、file:// 与本地绝对路径。
     * 成功返回后会出现在字幕面板里并被自动选中。
     */
    fun addSubtitle(url: String, label: String? = null)
    fun selectExternalSubtitle(id: String?)
    fun removeExternalSubtitle(id: String)
    fun setSubtitleDelay(delayMs: Long)
    fun setSubtitleAppearance(appearance: SubtitleAppearance)

    // ---- 弹幕 ----
    fun refreshDanmaku()
    fun setDanmakuEnabled(enabled: Boolean)
    fun updateDanmakuPreferences(block: (PlayerPreferences) -> PlayerPreferences)

    // ---- 投屏 ----
    fun searchCastDevices()
    fun connectCast(device: CastDevice)
    fun castPlay()
    fun castPause()
    fun castStop()
    fun castSeek(positionMs: Long)
    fun disconnectCast()

    // ---- 片头片尾 ----
    fun skipIntro()
    fun skipOutro()
    fun setAutoSkipIntro(enabled: Boolean)
    fun setAutoSkipOutro(enabled: Boolean)
    fun markIntroHere()
    fun markOutroHere()
    fun clearSkipMarks()

    // ---- 生命周期 ----
    fun clearToast()
    fun onForeground(active: Boolean, wasPlaying: Boolean = false)
    fun saveProgress()
    suspend fun awaitProgress()
    /** 当前集地址（投屏/外部播放器用），没有则返回 null。 */
    fun currentEpisodeRequest(): PlaybackRequest?
}
