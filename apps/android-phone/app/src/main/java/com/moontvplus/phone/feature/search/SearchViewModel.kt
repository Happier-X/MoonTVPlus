package com.moontvplus.phone.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.ImageUrlBuilder
import com.moontvplus.phone.data.repository.SearchEvent
import com.moontvplus.phone.data.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val imageUrls: ImageUrlBuilder,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val running: Boolean = false,
        val scanned: Int = 0,
        val totalSources: Int = 0,
        val results: List<AggregatedResult> = emptyList(),
        val error: String? = null,
        val history: List<String> = emptyList(),
        val pickerKey: String? = null,
    ) {
        val picker: AggregatedResult? get() = results.firstOrNull { it.key == pickerKey }
        val progress: Float
            get() = if (totalSources <= 0) 0f else (scanned.toFloat() / totalSources).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadHistory()
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun submit(keyword: String? = null) {
        val query = (keyword ?: _state.value.query).trim()
        if (query.isEmpty()) return

        searchJob?.cancel()
        _state.update {
            it.copy(
                query = query,
                running = true,
                scanned = 0,
                totalSources = 0,
                results = emptyList(),
                error = null,
                pickerKey = null,
            )
        }

        viewModelScope.launch {
            repository.addHistory(query)
            loadHistory()
        }

        searchJob = viewModelScope.launch {
            val collected = mutableListOf<SearchResult>()
            var started = false
            var completed = false
            var failedMessage: String? = null

            fun publish() {
                _state.update { it.copy(results = decorate(SearchAggregator.aggregate(collected))) }
            }

            val streamJob = launch {
                repository.stream(query).collect { event ->
                    when (event) {
                        is SearchEvent.Started -> {
                            started = true
                            _state.update { it.copy(totalSources = event.totalSources) }
                        }
                        is SearchEvent.SourceDone -> {
                            started = true
                            collected += event.results
                            _state.update { it.copy(scanned = it.scanned + 1) }
                            publish()
                        }
                        is SearchEvent.SourceFailed -> {
                            started = true
                            _state.update { it.copy(scanned = it.scanned + 1) }
                        }
                        is SearchEvent.Completed -> {
                            completed = true
                            _state.update { it.copy(scanned = event.completedSources) }
                        }
                        is SearchEvent.Failed -> failedMessage = event.message
                    }
                }
            }

            // 8 秒还没收到 start，说明 SSE 不通，掐掉连接走普通搜索
            val watchdog = launch {
                delay(START_TIMEOUT_MS)
                if (!started) streamJob.cancel()
            }

            streamJob.join()
            watchdog.cancel()

            if (!completed && collected.isEmpty()) {
                fallback(query, collected, failedMessage)
            } else {
                publish()
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** SSE 不通或断流时用一次性搜索兜底。 */
    private suspend fun fallback(
        query: String,
        collected: MutableList<SearchResult>,
        reason: String?,
    ) {
        repository.searchOnce(query).fold(
            onSuccess = { list ->
                collected.clear()
                collected += list
                _state.update {
                    it.copy(
                        running = false,
                        results = decorate(SearchAggregator.aggregate(collected)),
                        error = if (list.isEmpty()) reason ?: "没有找到相关结果" else null,
                        totalSources = 0,
                        scanned = 0,
                    )
                }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(
                        running = false,
                        error = error.message ?: reason ?: "搜索失败",
                    )
                }
            },
        )
    }

    fun stop() {
        // 主动停止不进兜底逻辑
        searchJob?.cancel()
        _state.update { it.copy(running = false) }
    }

    fun openPicker(key: String?) {
        _state.update { it.copy(pickerKey = key) }
    }

    fun removeHistory(keyword: String) {
        viewModelScope.launch {
            repository.removeHistory(keyword)
            loadHistory()
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            repository.history().onSuccess { list ->
                _state.update { it.copy(history = list) }
            }
        }
    }

    /** 海报统一换成站点图片代理地址。 */
    private fun decorate(items: List<AggregatedResult>): List<AggregatedResult> =
        items.map { item -> item.copy(poster = imageUrls.proxied(item.poster)) }

    private companion object {
        const val START_TIMEOUT_MS = 8_000L
    }
}
