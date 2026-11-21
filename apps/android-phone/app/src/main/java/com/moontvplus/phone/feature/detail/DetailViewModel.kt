package com.moontvplus.phone.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.model.Favorite
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.ImageUrlBuilder
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.data.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val api: MoonApi,
    private val imageUrls: ImageUrlBuilder,
    private val serverProvider: ServerProvider,
    private val library: LibraryRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val detail: SearchResult? = null,
        val poster: String? = null,
        val episodeTitles: List<String> = emptyList(),
        /** 1 起；与 playrecords 的 index 一致 */
        val resumeIndex: Int = 1,
        val resumePositionMs: Long = 0L,
        val hasResume: Boolean = false,
        val favorite: Boolean = false,
        val favoriteBusy: Boolean = false,
    ) {
        val episodes: List<String> get() = detail?.episodes.orEmpty()
        val episodeCount: Int get() = episodes.size
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadedKey: String? = null

    fun load(source: String, videoId: String, force: Boolean = false) {
        val key = "$source+$videoId"
        if (!force && key == loadedKey && _state.value.detail != null) return
        loadedKey = key

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val detailResult = runCatching {
                val response = api.sourceDetail(source, videoId)
                if (!response.isSuccessful) {
                    throw IllegalStateException("详情加载失败（HTTP ${response.code()}）")
                }
                response.body() ?: throw IllegalStateException("服务端返回空详情")
            }

            detailResult.fold(
                onSuccess = { detail ->
                    val record = runCatching {
                        api.playRecords().body()?.get(key)
                    }.getOrNull()?.takeIf { it.index in 1..detail.episodes.size }
                    val resumeIndex = record?.index?.coerceAtLeast(1) ?: 1
                    val resumeMs = ((record?.playTime ?: 0.0) * 1000).toLong()
                    val favorited = runCatching {
                        library.favorites().getOrNull()?.any { it.key == key } == true
                    }.getOrDefault(false)

                    _state.update {
                        it.copy(
                            loading = false,
                            error = null,
                            detail = detail,
                            poster = imageUrls.proxied(detail.poster),
                            episodeTitles = buildEpisodeTitles(detail),
                            resumeIndex = resumeIndex.coerceAtMost(
                                detail.episodes.size.coerceAtLeast(1)
                            ),
                            resumePositionMs = resumeMs.coerceAtLeast(0L),
                            hasResume = record != null && resumeMs > 0L,
                            favorite = favorited,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "详情加载失败",
                        )
                    }
                },
            )
        }
    }

    fun retry(source: String, videoId: String) = load(source, videoId, force = true)

    /** 网页版详情区的收藏按钮；成功后按结果回填，不重新拉整页。 */
    fun toggleFavorite(source: String, videoId: String) {
        val state = _state.value
        val detail = state.detail ?: return
        if (state.favoriteBusy) return
        val key = "$source+$videoId"
        _state.update { it.copy(favoriteBusy = true) }
        viewModelScope.launch {
            val ok = if (state.favorite) {
                library.removeFavorite(key).isSuccess
            } else {
                library.addFavorite(
                    key = key,
                    favorite = Favorite(
                        sourceName = detail.sourceName,
                        totalEpisodes = detail.episodes.size,
                        title = detail.title,
                        year = detail.year,
                        cover = detail.poster,
                        saveTime = System.currentTimeMillis(),
                        searchTitle = detail.title,
                    ),
                ).isSuccess
            }
            _state.update {
                it.copy(
                    favorite = if (ok) !state.favorite else state.favorite,
                    favoriteBusy = false,
                )
            }
        }
    }

    private fun buildEpisodeTitles(detail: SearchResult): List<String> {
        val titles = detail.episodesTitles
        return detail.episodes.indices.map { index ->
            titles.getOrNull(index)
                ?.substringBeforeLast('.')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "第 ${index + 1} 集"
        }
    }

    fun origin(): String = serverProvider.current()?.origin.orEmpty()
}
