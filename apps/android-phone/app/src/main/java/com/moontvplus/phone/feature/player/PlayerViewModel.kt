package com.moontvplus.phone.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.model.PlayRecordBody
import com.moontvplus.phone.core.model.SavePlayRecordRequest
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.data.media.EpisodePlan
import com.moontvplus.phone.data.media.EpisodeUrlResolver
import com.moontvplus.phone.playback.PlaybackController
import com.moontvplus.phone.playback.PlaybackEngine
import com.moontvplus.phone.playback.PlaybackRequest
import com.moontvplus.phone.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放页逻辑。
 *
 * 只跟 [PlaybackController] 打交道，代码里不出现 ExoPlayer 类型 ——
 * 具体播放器由平台提供（Android 用 Media3，桌面端以后接 VLC）。
 */
class PlayerViewModel(
    private val api: MoonApi,
    private val serverProvider: ServerProvider,
    engine: PlaybackEngine,
) : ViewModel() {

    /** 播放器跟着 ViewModel 活：旋转屏幕重建 Activity 时不会出现第二个播放器。 */
    val controller: PlaybackController = engine.create()

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val title: String = "",
        val detail: SearchResult? = null,
        val index: Int = 1,
        val plan: EpisodePlan? = null,
        val startPositionMs: Long = 0L,
        val episodeCount: Int = 0,
        val fallbackFromProxy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 播放器自身状态（缓冲 / 进度 / 错误 / 结束），界面直接订阅。 */
    val playback: StateFlow<PlaybackState> = controller.state

    private var lastSavedPlayTime: Double = -1.0

    fun load(source: String, videoId: String, title: String, index: Int, startPositionMs: Long) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    title = title,
                    index = index,
                    startPositionMs = startPositionMs,
                )
            }
            runCatching {
                val response = api.sourceDetail(source, videoId)
                if (!response.isSuccessful) {
                    throw IllegalStateException("无法获取选集（HTTP ${response.code()}）")
                }
                response.body() ?: throw IllegalStateException("服务端返回空详情")
            }.fold(
                onSuccess = { detail ->
                    val plan = buildPlan(detail, index)
                    _state.update {
                        it.copy(
                            loading = false,
                            error = if (plan == null) "这一集没有可用的播放地址" else null,
                            detail = detail,
                            episodeCount = detail.episodes.size,
                            plan = plan,
                        )
                    }
                    if (plan != null) {
                        controller.load(
                            PlaybackRequest(
                                url = plan.url,
                                isHls = plan.isHls,
                                startPositionMs = startPositionMs,
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(loading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    /** 播放失败时降级：解出原始直链 → 换另一种代理形式。 */
    fun fallbackToDirect() {
        val current = _state.value
        val plan = current.plan ?: return
        val detail = current.detail ?: return

        val direct = EpisodeUrlResolver.unwrapProxyUrl(plan.url)
        val next = when {
            direct != null && direct != plan.url -> EpisodePlan(
                url = direct,
                isHls = direct.substringBefore('?').lowercase().endsWith(".m3u8"),
                viaServer = false,
            )
            detail.proxyMode -> {
                val raw = detail.episodes.getOrNull(current.index - 1)
                EpisodeUrlResolver.resolve(origin(), raw, detail.source, proxyMode = true)
            }
            else -> null
        }

        if (next == null || next.url == plan.url) {
            _state.update { it.copy(error = "这一集播不了，可以换源或到网页版播放") }
            return
        }

        _state.update { it.copy(plan = next, fallbackFromProxy = true, error = null) }
        controller.load(PlaybackRequest(next.url, next.isHls))
    }

    fun goTo(index: Int) {
        val detail = _state.value.detail ?: return
        val plan = buildPlan(detail, index)
        _state.update {
            it.copy(
                index = index,
                plan = plan,
                startPositionMs = 0L,
                error = if (plan == null) "这一集没有可用的播放地址" else null,
                fallbackFromProxy = false,
            )
        }
        if (plan != null) controller.load(PlaybackRequest(plan.url, plan.isHls))
    }

    fun pause() = controller.pause()

    fun currentPositionMs(): Long = controller.currentPositionMs()

    /** 每 5 秒 + 离开时上报；进度没变就不重复发。 */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        val current = _state.value
        val detail = current.detail ?: return
        val playTime = positionMs / 1000.0
        if (playTime <= 0.0) return
        if (kotlin.math.abs(playTime - lastSavedPlayTime) < 1.0) return
        lastSavedPlayTime = playTime

        val key = "${detail.source}+${detail.id}"
        val record = PlayRecordBody(
            title = detail.title.ifBlank { current.title },
            sourceName = detail.sourceName,
            year = detail.year,
            cover = detail.poster,
            index = current.index,
            totalEpisodes = detail.episodes.size,
            playTime = playTime,
            totalTime = durationMs / 1000.0,
            saveTime = System.currentTimeMillis(),
        )

        viewModelScope.launch {
            runCatching { api.savePlayRecord(SavePlayRecordRequest(key, record)) }
        }
    }

    private fun buildPlan(detail: SearchResult, index: Int): EpisodePlan? {
        val raw = detail.episodes.getOrNull(index - 1) ?: return null
        return EpisodeUrlResolver.resolve(
            origin = origin(),
            episodeUrl = raw,
            source = detail.source,
            proxyMode = detail.proxyMode,
        )
    }

    private fun origin(): String = serverProvider.current()?.origin.orEmpty()

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
