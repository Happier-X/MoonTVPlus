package com.moontvplus.phone.feature.player

import androidx.lifecycle.ViewModel
import com.moontvplus.phone.cast.DlnaClient
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.storage.PlayerPreferencesStore
import com.moontvplus.phone.danmaku.DanmakuRepository
import com.moontvplus.phone.playback.PlaybackController
import com.moontvplus.phone.playback.PlaybackEngine
import com.moontvplus.phone.playback.PlayerPlatform
import com.moontvplus.phone.player.PlatformVideoHost
import com.moontvplus.phone.player.PlayerSession
import com.moontvplus.phone.player.PlayerUiController
import com.moontvplus.phone.subtitles.SubtitleRepository

/**
 * 播放页 ViewModel。
 *
 * 业务逻辑已经整体搬进 `shared/jvmSharedMain` 的 [PlayerSession]（Android 与桌面共用、
 * 可直接在 JVM 上单测），这里只做两件事：把 ViewModel 生命周期接到会话上、
 * 把旧的 3 参数构造保留给既有测试与调用方。
 */
class PlayerViewModel private constructor(
    private val session: PlayerSession,
) : ViewModel(), PlayerUiController by session, PlatformVideoHost {

    constructor(
        api: MoonApi,
        serverProvider: ServerProvider,
        engine: PlaybackEngine,
    ) : this(PlayerSession(api, serverProvider, engine))

    constructor(
        api: MoonApi,
        serverProvider: ServerProvider,
        engine: PlaybackEngine,
        platform: PlayerPlatform?,
        preferencesStore: PlayerPreferencesStore?,
        danmakuRepository: DanmakuRepository?,
        subtitleRepository: SubtitleRepository?,
        castClient: DlnaClient?,
    ) : this(
        PlayerSession(
            api = api,
            serverProvider = serverProvider,
            engine = engine,
            platform = platform,
            preferencesStore = preferencesStore,
            danmakuRepository = danmakuRepository,
            subtitleRepository = subtitleRepository,
            castClient = castClient,
        ),
    )

    /** 宿主（PlayerActivity）需要拿 ExoPlayer 绑定 PlayerView。 */
    val controller get() = session.controller

    /**
     * 画面绑定入口。
     *
     * 必须在这里放出平台的播放控制器：界面层拿到的是本 ViewModel，而不是
     * [PlayerSession]；1.9.6 因为只认 `PlayerSession`，画面层拿不到播放器，
     * 黑底配声音。
     */
    override val platformPlaybackController: PlaybackController? get() = session.platformPlaybackController

    fun load(source: String, videoId: String, title: String, index: Int, startPositionMs: Long) =
        session.load(source, videoId, title, index, startPositionMs)

    /** Debug 构建用来直接验证一个流地址。 */
    fun loadDirect(url: String, title: String) = session.loadDirect(url, title)

    /** 只有标题（豆瓣/推荐卡片）时：搜索并优选最佳片源后起播。 */
    fun loadByTitle(title: String, index: Int, startPositionMs: Long) =
        session.loadByTitle(title, index, startPositionMs)

    /** 手动换源（旧入口）。 */
    fun chooseSource(source: SearchResult) = session.chooseSource(source)

    fun setForeground(active: Boolean, wasPlaying: Boolean = false) =
        session.onForeground(active, wasPlaying)

    fun externalUrl(): String? = session.externalUrl()

    override fun onCleared() {
        session.close()
        super.onCleared()
    }
}
