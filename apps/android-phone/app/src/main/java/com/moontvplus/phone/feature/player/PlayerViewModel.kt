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
import com.moontvplus.phone.playback.PlaybackEngine
import com.moontvplus.phone.playback.PlaybackRequest
import com.moontvplus.phone.playback.PlaybackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class PlayerViewModel(
    private val api: MoonApi,
    private val serverProvider: ServerProvider,
    engine: PlaybackEngine,
) : ViewModel() {
    val controller = engine.create()
    val playback = controller.state

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val title: String = "",
        val detail: SearchResult? = null,
        val index: Int = 1,
        val source: SearchResult? = null,
        val findingSources: Boolean = false,
        val sources: List<SearchResult> = emptyList(),
        val sourceError: String? = null,
    ) {
        val episodeCount: Int get() = maxOf(detail?.episodesCount ?: 0, index)
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private lateinit var original: SearchResult
    private var identity: SearchResult? = null
    private var attempt: Job? = null
    private var sourceSearch: Job? = null
    private var loaded: SearchResult? = null
    private var resumeMs = 0L
    private var foreground = false
    private var shouldResume = true
    private var lastSaved: SavePlayRecordRequest? = null
    private val saveLock = Mutex()
    private var lastSaveJob: Job? = null

    fun load(source: String, videoId: String, title: String, index: Int, startPositionMs: Long) {
        if (::original.isInitialized) return
        original = SearchResult(source = source, id = videoId, title = title)
        _state.value = UiState(title = title)
        start(original, index.coerceAtLeast(1), startPositionMs.coerceAtLeast(0), autoSwitch = true)
    }

    fun retry() {
        val current = _state.value
        start(current.source ?: original, current.index, sameEpisodePosition(), autoSwitch = true)
    }

    fun chooseSource(source: SearchResult) {
        start(source, _state.value.index, sameEpisodePosition(), autoSwitch = false)
    }

    fun goTo(index: Int) {
        val current = _state.value
        if (index == current.index || index !in 1..current.episodeCount) return
        start(current.source ?: original, index, 0L, autoSwitch = true)
    }

    fun findSources() {
        if (sourceSearch?.isActive == true) return
        sourceSearch = viewModelScope.launch {
            _state.update { it.copy(findingSources = true, sourceError = null) }
            val results = discoverSources()
            _state.update {
                it.copy(
                    findingSources = false,
                    sources = (results.orEmpty() + listOfNotNull(identity, it.source)).distinctBy { source -> source.key },
                    sourceError = if (results == null) "获取片源失败，请重试" else null,
                )
            }
        }
    }

    private suspend fun discoverSources(): List<SearchResult>? {
        val reference = identity ?: original
        val title = reference.title.ifBlank { original.title }
        if (title.isBlank()) return emptyList()
        return withTimeoutOrNull(8_000) {
            apiResult {
                val response = api.search(title)
                if (!response.isSuccessful) null else matchingSources(reference.copy(title = title), response.body()?.results.orEmpty())
            }
        }
    }

    private fun sameEpisodePosition(): Long = if (loaded != null) controller.currentPositionMs() else resumeMs

    private fun start(preferred: SearchResult, index: Int, positionMs: Long, autoSwitch: Boolean) {
        val old = _state.value
        saveProgress()
        attempt?.cancel()
        controller.stop()
        loaded = null
        shouldResume = true
        resumeMs = positionMs
        _state.update { it.copy(loading = true, error = null, source = preferred, index = index) }
        attempt = viewModelScope.launch {
            var failure = "当前片源无法播放第 ${index} 集"
            val attempted = mutableSetOf<String>()
            // 与网页版一致：自动换源有总体时限，不能让用户干等一串坏源
            var deadline = System.currentTimeMillis() + 25_000
            suspend fun trySource(source: SearchResult): Boolean {
                val detail = withTimeoutOrNull(5_000) {
                    apiResult { api.sourceDetail(source.source, source.id).body() }
                } ?: return false
                if (detail.key != source.key) return false
                if (source.key == original.key) identity = detail
                val reference = identity ?: original
                if (matchingSources(reference, listOf(detail)).isEmpty()) return false
                if (source.key == preferred.key) _state.update {
                    it.copy(title = detail.title.ifBlank { it.title }, detail = detail)
                }
                val raw = detail.episodes.getOrNull(index - 1)
                val origin = serverProvider.current()?.origin.orEmpty()
                val firstPlan = EpisodeUrlResolver.resolve(origin, raw, detail.source, detail.proxyMode) ?: return false
                val direct = if (firstPlan.viaServer) {
                    EpisodeUrlResolver.unwrapProxyUrl(firstPlan.url)?.let {
                        EpisodeUrlResolver.resolve(origin, it, detail.source, proxyMode = false)
                    }
                } else null
                for (plan in listOfNotNull(firstPlan, direct)) {
                    if (System.currentTimeMillis() >= deadline) return false
                    if (!attempted.add(plan.url)) continue
                    if (prepare(plan)) {
                        loaded = detail
                        _state.update {
                            it.copy(loading = false, error = null, title = detail.title.ifBlank { it.title }, detail = detail, source = detail)
                        }
                        val outcome = playback.first { it.status == PlaybackStatus.ENDED || it.status == PlaybackStatus.ERROR }
                        if (outcome.status == PlaybackStatus.ENDED) {
                            saveProgress()
                            if (index < detail.episodesCount) {
                                // Leave this attempt before starting the next episode.
                                viewModelScope.launch { goTo(index + 1) }
                            }
                            return true
                        }
                        // 已经起播过再出错：给换地址/换源一个新的尝试窗口
                        deadline = System.currentTimeMillis() + 25_000
                        resumeMs = controller.currentPositionMs().takeIf { it > 0L } ?: resumeMs
                        saveProgress()
                        loaded = null
                        _state.update { it.copy(loading = true) }
                    }
                    controller.stop()
                }
                failure = "第 ${index} 集播放失败"
                return false
            }

            if (trySource(preferred)) return@launch
            if (autoSwitch) {
                val candidates = discoverSources().orEmpty()
                _state.update { it.copy(sources = candidates) }
                for (source in candidates.filter { it.key != preferred.key }.take(3)) {
                    if (trySource(source)) return@launch
                }
                failure = "已尝试的片源均无法播放第 ${index} 集"
            }
            _state.update { it.copy(loading = false, error = failure, source = preferred, detail = it.detail ?: old.detail) }
        }
    }

    private suspend fun prepare(plan: EpisodePlan): Boolean {
        controller.load(PlaybackRequest(plan.url, plan.isHls, resumeMs, autoPlay = foreground && shouldResume))
        val result = withTimeoutOrNull(8_000) {
            playback.first { it.status in setOf(PlaybackStatus.READY, PlaybackStatus.ENDED, PlaybackStatus.ERROR) }
        }
        return result?.status == PlaybackStatus.READY || result?.status == PlaybackStatus.ENDED
    }

    fun setForeground(active: Boolean, wasPlaying: Boolean = false) {
        foreground = active
        if (!active) {
            if (loaded != null) shouldResume = wasPlaying
            saveProgress()
            controller.pause()
        } else if (shouldResume && (loaded != null || playback.value.status == PlaybackStatus.BUFFERING)) {
            controller.play()
        }
    }

    fun saveProgress() {
        val detail = loaded ?: return
        val durationMs = playback.value.durationMs
        if (durationMs <= 0L) return
        val seconds = controller.currentPositionMs().coerceAtMost(durationMs) / 1000.0
        val current = _state.value
        val request = SavePlayRecordRequest(
            detail.key,
            PlayRecordBody(
                title = detail.title.ifBlank { current.title },
                sourceName = detail.sourceName,
                year = detail.year,
                cover = detail.poster,
                index = current.index,
                totalEpisodes = detail.episodesCount,
                playTime = seconds,
                totalTime = durationMs / 1000.0,
                saveTime = System.currentTimeMillis(),
            ),
        )
        val previousJob = lastSaveJob
        lastSaveJob = progressScope.launch {
            previousJob?.join()
            saveLock.withLock {
                val previous = lastSaved
                if (previous?.key == request.key && previous.record.index == request.record.index &&
                    kotlin.math.abs(previous.record.playTime - seconds) < 1.0
                ) return@withLock
                if (withTimeoutOrNull(5_000) { apiResult { api.savePlayRecord(request).isSuccessful } } == true) {
                    lastSaved = request
                }
            }
        }
    }

    suspend fun awaitProgress() {
        lastSaveJob?.join()
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }

    private companion object {
        val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

internal fun matchingSources(reference: SearchResult, candidates: List<SearchResult>): List<SearchResult> {
    return candidates.filter { candidate ->
        if (candidate.id.isBlank() || candidate.source.isBlank()) return@filter false
        if (candidate.key == reference.key) return@filter true
        val bothHaveId = reference.doubanId > 0 && candidate.doubanId > 0
        if (bothHaveId) return@filter reference.doubanId == candidate.doubanId
        reference.title.isNotBlank() && reference.title.trim().equals(candidate.title.trim(), ignoreCase = true) &&
            (reference.year.isBlank() || candidate.year.isBlank() || reference.year.trim() == candidate.year.trim())
    }.distinctBy { it.key }.sortedByDescending { it.weight ?: 0.0 }
}

private suspend fun <T> apiResult(call: suspend () -> T): T? = try {
    call()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
