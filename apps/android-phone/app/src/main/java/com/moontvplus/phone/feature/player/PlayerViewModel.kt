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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
class PlayerViewModel(
    private val api: MoonApi,
    private val serverProvider: ServerProvider,
) : ViewModel() {

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

    private var lastSavedPlayTime: Double = -1.0

    fun load(source: String, videoId: String, title: String, index: Int, startPositionMs: Long) {
        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, error = null, title = title, index = index, startPositionMs = startPositionMs)
            }
            runCatching {
                val response = api.sourceDetail(source, videoId)
                if (!response.isSuccessful) throw IllegalStateException("无法获取选集（HTTP ${response.code()}）")
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
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(loading = false, error = error.message ?: "加载失败")
                    }
                },
            )
        }
    }

    /** 播放失败时依次降级：解出原始直链 → 重试。 */
    fun fallbackToDirect() {
        val current = _state.value
        val plan = current.plan ?: return
        val detail = current.detail ?: return

        val direct = EpisodeUrlResolver.unwrapProxyUrl(plan.url)
        if (direct != null && direct != plan.url) {
            val isHls = direct.substringBefore('?').lowercase().endsWith(".m3u8")
            _state.update {
                it.copy(
                    plan = EpisodePlan(url = direct, isHls = isHls, viaServer = false),
                    fallbackFromProxy = true,
                    error = null,
                )
            }
            return
        }

        // 已经在直连，换成源里另一条可选地址（例如服务端代理）
        if (detail.proxyMode) {
            val origin = origin()
            val raw = detail.episodes.getOrNull(current.index - 1)
            val proxied = EpisodeUrlResolver.resolve(origin, raw, detail.source, proxyMode = true)
            if (proxied != null && proxied.url != plan.url) {
                _state.update { it.copy(plan = proxied, fallbackFromProxy = true, error = null) }
                return
            }
        }

        _state.update { it.copy(error = "这一集播不了，可以换源或到网页版播放") }
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
    }

    fun reportError(message: String) {
        _state.update { it.copy(error = message) }
    }

    /** 每 5 秒 + 离开时上报；进度没变就不重复发。 */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        val current = _state.value
        val detail = current.detail ?: return
        val index = current.index
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
            index = index,
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
}
