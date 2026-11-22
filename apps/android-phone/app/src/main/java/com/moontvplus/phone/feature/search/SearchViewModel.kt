package com.moontvplus.phone.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.model.AcgItem
import com.moontvplus.phone.core.model.AcgSource
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.ImageUrlBuilder
import com.moontvplus.phone.data.repository.SearchEvent
import com.moontvplus.phone.data.repository.SearchRepository
import com.moontvplus.ui.model.FilterCategory
import com.moontvplus.ui.model.FilterOption
import com.moontvplus.ui.model.KEY_SOURCE
import com.moontvplus.ui.model.KEY_TITLE
import com.moontvplus.ui.model.KEY_YEAR
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repository: SearchRepository,
    private val imageUrls: ImageUrlBuilder,
    private val settingsStore: com.moontvplus.phone.core.storage.SettingsStore,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val running: Boolean = false,
        val scanned: Int = 0,
        val totalSources: Int = 0,
        val results: List<AggregatedResult> = emptyList(),
        /** 未聚合的逐源结果，网页版 viewMode=all 用。 */
        val flatResults: List<AggregatedResult> = emptyList(),
        val error: String? = null,
        val history: List<String> = emptyList(),
        /** 影视搜索 / 动漫磁力 */
        val tab: SearchTab = SearchTab.VIDEO,
        /** 来源 / 标题 / 年份筛选，值 `all` 表示不限。 */
        val filters: Map<String, String> = emptyMap(),
        /** 年份排序：none / desc / asc，对应网页版的那个切换按钮。 */
        val yearOrder: String = "none",
        val acgSource: AcgSource = AcgSource.ACGRIP,
        val acgItems: List<AcgItem> = emptyList(),
        val acgLoading: Boolean = false,
        val acgLoadingMore: Boolean = false,
        val acgError: String? = null,
        /** card / list */
        val displayMode: String = "card",
        /** 聚合同一部片子的多个源 */
        val aggregate: Boolean = true,
    ) {
        val progress: Float
            get() = if (totalSources <= 0) 0f else (scanned.toFloat() / totalSources).coerceIn(0f, 1f)

        /** 网页版的三组筛选，选项按当前结果现算。 */
        val filterCategories: List<FilterCategory>
            get() {
                if (results.isEmpty()) return emptyList()
                val sources = results.flatMap { it.sources }
                    .map { it.source to it.sourceName.ifBlank { it.source } }
                    .distinctBy { it.first }
                    .sortedBy { it.second }
                    .map { FilterOption(it.second, it.first) }
                val titles = results.map { it.title }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .map { FilterOption(it, it) }
                val years = results.map { it.year.ifBlank { "unknown" } }
                    .distinct()
                    .sortedWith(compareByDescending<String> { it.toIntOrNull() ?: -1 })
                    .map { FilterOption(if (it == "unknown") "未知" else it, it) }
                return listOf(
                    FilterCategory(KEY_SOURCE, "来源", listOf(FilterOption("全部来源", "all")) + sources),
                    FilterCategory(KEY_TITLE, "标题", listOf(FilterOption("全部标题", "all")) + titles),
                    FilterCategory(KEY_YEAR, "年份", listOf(FilterOption("全部年份", "all")) + years),
                )
            }

        /** 应用筛选 + 年份排序后的列表。 */
        val visibleResults: List<AggregatedResult>
            get() {
                val source = filters[KEY_SOURCE] ?: "all"
                val title = filters[KEY_TITLE] ?: "all"
                val year = filters[KEY_YEAR] ?: "all"
                val sourceList = if (aggregate) results else flatResults
                val filtered = sourceList.filter { item ->
                    val itemYear = item.year.ifBlank { "unknown" }
                    (source == "all" || item.sources.any { it.source == source }) &&
                        (title == "all" || item.title == title) &&
                        (year == "all" || itemYear == year)
                }
                if (yearOrder == "none") return filtered
                return filtered.sortedWith(
                    compareBy<AggregatedResult> {
                        val value = it.year.toIntOrNull()
                        if (value == null) Int.MAX_VALUE else if (yearOrder == "desc") -value else value
                    }.thenBy { it.title }
                )
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadHistory()
        viewModelScope.launch {
            val stored = settingsStore.snapshot()
            _state.update {
                it.copy(
                    displayMode = stored.searchDisplayMode,
                    aggregate = stored.searchAggregate,
                )
            }
        }
    }

    fun setDisplayMode(mode: String) {
        if (mode != "card" && mode != "list") return
        _state.update { it.copy(displayMode = mode) }
        viewModelScope.launch { settingsStore.setSearchDisplayMode(mode) }
    }

    fun setAggregate(enabled: Boolean) {
        _state.update { it.copy(aggregate = enabled) }
        viewModelScope.launch { settingsStore.setSearchAggregate(enabled) }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun submit(keyword: String? = null) {
        val query = (keyword ?: _state.value.query).trim()
        if (query.isEmpty()) return
        // 磁力 Tab 里按回车就是搜磁力，不切回影视搜索
        if (_state.value.tab == SearchTab.ACG) {
            _state.update { it.copy(query = query) }
            submitAcg()
            return
        }

        searchJob?.cancel()
        _state.update {
            it.copy(
                query = query,
                running = true,
                scanned = 0,
                totalSources = 0,
                results = emptyList(),
                error = null,
                filters = emptyMap(),
                yearOrder = "none",
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
                _state.update {
                    it.copy(
                        results = decorate(SearchAggregator.aggregate(collected)),
                        flatResults = flatten(collected),
                    )
                }
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
                        flatResults = flatten(collected),
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

    fun selectTab(tab: SearchTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
        if (tab == SearchTab.ACG && _state.value.acgItems.isEmpty()) submitAcg()
    }

    fun setFilter(key: String, value: String) {
        _state.update { it.copy(filters = it.filters + (key to value)) }
    }

    /** 年份排序按钮：无 → 降序 → 升序 → 无。 */
    fun cycleYearOrder() {
        _state.update {
            it.copy(
                yearOrder = when (it.yearOrder) {
                    "none" -> "desc"
                    "desc" -> "asc"
                    else -> "none"
                }
            )
        }
    }

    fun setAcgSource(source: AcgSource) {
        if (_state.value.acgSource == source) return
        _state.update { it.copy(acgSource = source, acgItems = emptyList(), acgError = null) }
        submitAcg()
    }

    /** 动漫磁力搜索：先搜第一页，之后由界面触底加载更多。 */
    fun submitAcg() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        val source = _state.value.acgSource
        acgPage = 1
        _state.update { it.copy(acgLoading = true, acgError = null, acgItems = emptyList()) }
        viewModelScope.launch {
            repository.acgSearch(source, query, 1).fold(
                onSuccess = { items -> _state.update { it.copy(acgLoading = false, acgItems = items) } },
                onFailure = { e ->
                    _state.update { it.copy(acgLoading = false, acgError = e.message ?: "搜索失败") }
                },
            )
        }
    }

    fun loadMoreAcg() {
        val state = _state.value
        if (state.acgLoading || state.acgLoadingMore) return
        if (!state.acgSource.supportsPaging) return
        val query = state.query.trim()
        if (query.isEmpty()) return
        val next = acgPage + 1
        _state.update { it.copy(acgLoadingMore = true) }
        viewModelScope.launch {
            repository.acgSearch(state.acgSource, query, next).fold(
                onSuccess = { items ->
                    acgPage = next
                    _state.update {
                        it.copy(
                            acgLoadingMore = false,
                            acgItems = it.acgItems + items.filterNot { item -> it.acgItems.any { old -> old.key == item.key } },
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(acgLoadingMore = false, acgError = e.message) }
                },
            )
        }
    }

    private var acgPage = 1

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

    /** 逐源结果：与网页版「不聚合」视图一致，一条源一张卡。 */
    private fun flatten(items: List<SearchResult>): List<AggregatedResult> =
        items.filter { it.title.isNotBlank() }.map { result ->
            AggregatedResult(
                key = "video:" + result.source + "+" + result.id,
                title = result.title,
                poster = imageUrls.proxied(result.poster),
                year = result.year,
                typeLabel = SearchAggregator.typeLabelOf(result),
                sources = listOf(result),
            )
        }

    private companion object {
        const val START_TIMEOUT_MS = 8_000L
    }
}

/** 搜索页两个 Tab：影视搜索 / 动漫磁力（与网页版一致）。 */
enum class SearchTab(val label: String) {
    VIDEO("影视搜索"),
    ACG("动漫磁力"),
}
