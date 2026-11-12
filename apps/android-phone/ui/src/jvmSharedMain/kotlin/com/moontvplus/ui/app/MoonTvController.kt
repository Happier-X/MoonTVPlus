package com.moontvplus.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moontvplus.phone.core.common.ApiResult
import com.moontvplus.phone.core.common.userMessage
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.model.Favorite
import com.moontvplus.phone.core.model.BangumiSubject
import com.moontvplus.phone.core.model.ContinueItem
import com.moontvplus.phone.core.model.FavoriteItem
import com.moontvplus.phone.core.model.DoubanItem
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.model.TmdbItem
import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.phone.core.storage.AppSettings
import com.moontvplus.phone.data.repository.HomeCache
import com.moontvplus.phone.data.repository.ServerProbeResult
import com.moontvplus.phone.di.AppGraph
import com.moontvplus.ui.model.HomeSection
import com.moontvplus.ui.model.ShellScreen
import com.moontvplus.ui.model.MainTab
import com.moontvplus.ui.model.OpenTarget
import com.moontvplus.ui.model.PosterItem
import com.moontvplus.ui.screen.BrowseUiState
import com.moontvplus.ui.screen.DetailUiState
import com.moontvplus.ui.screen.LibraryTab
import com.moontvplus.ui.screen.LibraryUiState
import com.moontvplus.ui.screen.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 外壳所处的阶段。 */
sealed interface ShellStage {
    data object Loading : ShellStage
    data object NeedServer : ShellStage
    data class NeedLogin(val reason: String? = null) : ShellStage
    data object Ready : ShellStage
}

/**
 * 共享的视图状态：服务器地址 → 登录 → 首页 / 分类。
 *
 * 这一层只依赖 `AppGraph`（jvmShared），所以 Android 与桌面端跑的是同一份逻辑，
 * 平台差异只剩「海报怎么加载」和「播放器怎么接」。
 */
class MoonTvController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    var stage by mutableStateOf<ShellStage>(ShellStage.Loading)
        private set
    var settings by mutableStateOf(AppSettings())
        private set
    var session by mutableStateOf<AuthSession?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    var tab by mutableStateOf(MainTab.HOME)
        private set
    var home by mutableStateOf(HomeUiState(loading = true))
        private set
    var browse by mutableStateOf(BrowseUiState(loading = true))
        private set

    val siteName: String get() = settings.siteName ?: "MoonTVPlus"

    // ---- 启动流程 ----------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        scope.launch { boot() }
    }

    private var started = false

    private suspend fun boot() {
        graph.initialize()
        settings = graph.settingsStore.snapshot()

        val address = ServerAddress.parseStored(settings.serverAddress)
        graph.serverProvider.set(address)
        if (address == null) {
            stage = ShellStage.NeedServer
            return
        }

        graph.sessionManager.load()
        val current = graph.sessionManager.session.value
        if (current == null) {
            stage = ShellStage.NeedLogin()
            return
        }
        if (!graph.sessionManager.ensureFresh()) {
            graph.sessionManager.clear()
            stage = ShellStage.NeedLogin("登录已过期，请重新登录")
            return
        }

        session = current
        stage = ShellStage.Ready
        loadHome()
    }

    /**
     * 宿主自己做完了服务器地址与登录（Android 的登录页要带 Turnstile，留在原生侧），
     * 直接把外壳置为就绪并加载首页。
     */
    fun enterShell() {
        if (initialized) return
        initialized = true
        scope.launch {
            graph.initialize()
            settings = graph.settingsStore.snapshot()
            session = graph.sessionManager.session.value
            stage = ShellStage.Ready
            loadHome()
        }
    }

    private var initialized = false

    fun connect(rawAddress: String) {        scope.launch {
            busy = true
            error = null
            when (val result = graph.serverRepository.probe(rawAddress.trim())) {
                is ServerProbeResult.Success -> {
                    graph.serverRepository.save(result.address, result.config)
                    settings = graph.settingsStore.snapshot()
                    stage = ShellStage.NeedLogin()
                }

                is ServerProbeResult.Failed -> error = result.message
            }
            busy = false
        }
    }

    fun login(username: String, password: String) {
        scope.launch {
            busy = true
            error = null
            when (val result = graph.authRepository.login(username, password)) {
                is ApiResult.Ok -> {
                    session = result.value
                    stage = ShellStage.Ready
                    loadHome()
                }

                is ApiResult.Err -> error = result.error.userMessage()
            }
            busy = false
        }
    }

    fun logout() {
        scope.launch {
            graph.authRepository.logout()
            session = null
            home = HomeUiState()
            stage = ShellStage.NeedLogin()
        }
    }

    fun changeServer() {
        scope.launch {
            graph.authRepository.logout()
            graph.serverRepository.clear()
            graph.serverProvider.set(null)
            settings = graph.settingsStore.snapshot()
            session = null
            home = HomeUiState()
            browse = BrowseUiState()
            stage = ShellStage.NeedServer
        }
    }

    fun clearError() {
        error = null
    }

    // ---- 导航 --------------------------------------------------------------

    fun selectTab(next: MainTab) {
        if (tab == next) return
        tab = next
        when {
            next == MainTab.HOME -> if (home.sections.isEmpty()) refreshHome()
            next.isBrowse -> if (browse.items.isEmpty()) loadBrowse(next)
        }
    }

    // ---- 页面栈 ------------------------------------------------------------

    var screen by mutableStateOf<ShellScreen>(ShellScreen.Main)
        private set
    var detail by mutableStateOf(DetailUiState())
        private set
    var library by mutableStateOf(LibraryUiState())
        private set

    /** 详情页用未代理的原始封面，写收藏时服务端要的是原始地址。 */
    private var detailRawPoster: String? = null

    /** 桌面端没有内嵌播放器时，详情页上要说明怎么播。 */
    var playHint: String? = null

    /** 宿主接住「豆瓣条目需要搜索一下」的请求（搜索页还在原生侧）。 */
    var onNeedSearch: ((String) -> Unit)? = null

    fun open(target: OpenTarget?) {
        when (target) {
            null -> Unit
            is OpenTarget.Video -> openDetail(target.source, target.id, target.title)
            is OpenTarget.Resume -> openDetail(target.source, target.id, target.title)
            is OpenTarget.Douban -> openByTitle(target.title)
        }
    }

    /**
     * 豆瓣/Bangumi 条目只有标题，先搜一次源再进详情。
     * 桌面端直接取权重最高的一条；安卓端如果想自己挑源，可以覆盖 [onNeedSearch]。
     */
    fun openByTitle(title: String) {
        val intercept = onNeedSearch
        if (intercept != null) {
            intercept(title)
            return
        }
        screen = ShellScreen.Detail("", "", title)
        detail = DetailUiState(loading = true, title = title, playHint = "正在搜索片源…")
        scope.launch {
            val result = graph.searchRepository.searchOnce(title)
            val best = result.getOrNull()
                ?.filter { it.id.isNotBlank() && it.episodes.isNotEmpty() }
                ?.maxByOrNull { it.weight ?: 0.0 }

            if (best == null) {
                detail = DetailUiState(
                    loading = false,
                    title = title,
                    error = result.exceptionOrNull()?.message ?: "没有搜到可播放的片源",
                )
                screen = ShellScreen.Detail("", "", title)
                return@launch
            }
            openDetail(
                source = best.source,
                videoId = best.id,
                title = best.title.ifBlank { title },
            )
        }
    }

    fun openDetail(source: String, videoId: String, title: String) {
        screen = ShellScreen.Detail(source, videoId, title)
        loadDetail(source, videoId, title)
    }

    fun openLibrary(tab: LibraryTab = LibraryTab.FAVORITES) {
        screen = ShellScreen.Library
        library = library.copy(tab = tab, loading = true, error = null)
        loadLibrary()
    }

    fun selectLibraryTab(tab: LibraryTab) {
        library = library.copy(tab = tab)
    }

    fun back() {
        screen = ShellScreen.Main
        detail = DetailUiState()
    }

    // ---- 详情页 ------------------------------------------------------------

    private fun loadDetail(source: String, videoId: String, fallbackTitle: String) {
        if (source.isBlank() || videoId.isBlank()) return
        detail = DetailUiState(loading = true, title = fallbackTitle)
        scope.launch {
            val key = "$source+$videoId"
            val result = runCatching {
                val response = graph.moonApi.sourceDetail(source, videoId)
                if (!response.isSuccessful) {
                    throw IllegalStateException("详情加载失败（HTTP ${response.code()}）")
                }
                response.body() ?: throw IllegalStateException("服务端返回空详情")
            }

            result.fold(
                onSuccess = { video ->
                    detailRawPoster = video.poster
                    val record = runCatching { graph.moonApi.playRecords().body()?.get(key) }.getOrNull()
                    val favorite = graph.libraryRepository.isFavorite(key)
                    val episodes = buildEpisodeNames(video)
                    val resumeIndex = ((record?.index ?: 1) - 1)
                        .coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
                    detail = DetailUiState(
                        loading = false,
                        title = video.title.ifBlank { fallbackTitle },
                        posterUrl = imageUrl(video.poster),
                        meta = listOf(video.year, video.typeName, video.vodRemarks)
                            .filterNotNull()
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        desc = video.desc,
                        episodes = episodes,
                        favorite = favorite,
                        resumeIndex = resumeIndex,
                        resumePositionMs = ((record?.playTime ?: 0.0) * 1000).toLong().coerceAtLeast(0L),
                        hasResume = (record?.playTime ?: 0.0) > 0.0 && episodes.isNotEmpty(),
                        playHint = playHint,
                    )
                },
                onFailure = { error ->
                    detail = DetailUiState(
                        loading = false,
                        title = fallbackTitle,
                        error = error.message ?: "详情加载失败",
                    )
                },
            )
        }
    }

    private fun buildEpisodeNames(video: SearchResult): List<String> {
        val titles = video.episodesTitles
        return video.episodes.indices.map { index ->
            titles.getOrNull(index)
                ?.substringBeforeLast('.')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "第 ${index + 1} 集"
        }
    }

    fun toggleFavorite() {
        val current = screen as? ShellScreen.Detail ?: return
        val state = detail
        if (state.favoriteBusy || state.episodes.isEmpty()) return
        detail = state.copy(favoriteBusy = true)
        scope.launch {
            val key = "${current.source}+${current.videoId}"
            val ok = if (state.favorite) {
                graph.libraryRepository.removeFavorite(key).isSuccess
            } else {
                graph.libraryRepository.addFavorite(
                    key = key,
                    favorite = Favorite(
                        sourceName = current.source,
                        totalEpisodes = state.episodes.size,
                        title = state.title,
                        year = detailYear(state.meta),
                        cover = detailRawPoster.orEmpty(),
                        saveTime = System.currentTimeMillis(),
                        searchTitle = state.title,
                    ),
                ).isSuccess
            }
            detail = detail.copy(
                favorite = if (ok) !state.favorite else state.favorite,
                favoriteBusy = false,
            )
        }
    }

    private fun detailYear(meta: String): String =
        meta.split(" · ").firstOrNull { part -> part.isNotEmpty() && part.all { it.isDigit() } }.orEmpty()

    // ---- 收藏 / 播放历史 ----------------------------------------------------

    private fun loadLibrary() {
        scope.launch {
            val favorites = graph.libraryRepository.favorites()
            val records = graph.libraryRepository.playRecords()
            val error = favorites.exceptionOrNull()?.message
                ?: records.exceptionOrNull()?.message

            library = library.copy(
                loading = false,
                favorites = favorites.getOrNull().orEmpty().map { it.toLibraryPoster() },
                history = records.getOrNull().orEmpty().map { it.toHistoryPoster() },
                error = error,
            )
        }
    }

    fun removeLibraryItem(item: PosterItem) {
        val isFavorite = library.tab == LibraryTab.FAVORITES
        val before = library
        library = library.copy(
            favorites = if (isFavorite) library.favorites.filter { it.key != item.key } else library.favorites,
            history = if (isFavorite) library.history else library.history.filter { it.key != item.key },
        )
        val rawKey = item.key.substringAfter(':')
        scope.launch {
            val result = if (isFavorite) {
                graph.libraryRepository.removeFavorite(rawKey)
            } else {
                graph.libraryRepository.removePlayRecord(rawKey)
            }
            if (result.isFailure) {
                // 删失败就把列表还原，避免和服务端不一致
                library = before.copy(error = result.exceptionOrNull()?.message ?: "删除失败")
            }
        }
    }

    private fun FavoriteItem.toLibraryPoster(): PosterItem {
        val meta = listOf(favorite.year, favorite.vodRemarks)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return PosterItem(
            key = "fav:$key",
            title = favorite.title,
            subtitle = meta.ifBlank { null },
            posterUrl = imageUrl(favorite.cover),
            target = OpenTarget.Video(source = source, id = videoId, title = favorite.title),
        )
    }

    private fun ContinueItem.toHistoryPoster(): PosterItem = PosterItem(
        key = "his:$key",
        title = record.title,
        subtitle = "第 ${record.index} 集",
        posterUrl = imageUrl(record.cover),
        progress = record.progress,
        target = OpenTarget.Resume(source = source, id = videoId, title = record.title, index = record.index),
    )

    // ---- 首页 --------------------------------------------------------------

    fun refreshHome() {
        scope.launch { loadHome() }
    }

    private suspend fun loadHome() {
        home = home.copy(loading = true, error = null)

        val cached = graph.homeRepository.cached()
        if (cached != null) home = buildHome(cached, HomeUiState(loading = true))

        scope.launch { loadContinueWatching() }

        val result = graph.homeRepository.refresh(cached)
        home = buildHome(result.cache, home.copy(loading = false)).copy(
            error = if (result.empty) result.errors.values.firstOrNull() else null,
        )
    }

    private suspend fun loadContinueWatching() {
        val records = graph.homeRepository.continueWatching().getOrNull().orEmpty()
        if (records.isEmpty()) {
            home = home.copy(continueWatching = null)
            return
        }
        home = home.copy(
            continueWatching = HomeSection(
                id = "continue",
                title = "继续观看",
                items = records.map { it.toHistoryPoster() },
            ),
        )
    }

    private fun buildHome(cache: HomeCache, base: HomeUiState): HomeUiState {
        val sections = buildList {
            if (cache.movies.isNotEmpty()) {
                add(section("hotMovies", "热门电影", cache.movies.map { it.toPoster() }))
            }
            if (cache.tvShows.isNotEmpty()) {
                add(section("hotTv", "热门剧集", cache.tvShows.map { it.toPoster() }))
            }
            val bangumi = cache.bangumi.flatMap { it.items }
            if (bangumi.isNotEmpty()) {
                add(section("bangumi", "番剧放送", bangumi.map { it.toPoster() }))
            }
            if (cache.variety.isNotEmpty()) {
                add(section("hotVariety", "热门综艺", cache.variety.map { it.toPoster() }))
            }
            if (cache.duanju.isNotEmpty()) {
                add(section("duanju", "短剧", cache.duanju.map { it.toPoster() }))
            }
            if (cache.upcoming.isNotEmpty()) {
                add(section("upcoming", "即将上映", cache.upcoming.map { it.toPoster() }))
            }
        }
        return base.copy(sections = sections)
    }

    private fun section(id: String, title: String, items: List<PosterItem>) =
        HomeSection(id = id, title = title, items = items)

    // ---- 分类页 ------------------------------------------------------------

    private fun loadBrowse(target: MainTab) {
        scope.launch {
            browse = BrowseUiState(loading = true)
            val result = runCatching {
                graph.homeRepository.category(target.kind, target.category, target.type)
            }
            browse = result.fold(
                onSuccess = { items ->
                    BrowseUiState(loading = false, items = items.map { it.toPoster() })
                },
                onFailure = { e ->
                    BrowseUiState(loading = false, error = e.message ?: "加载失败")
                },
            )
        }
    }

    fun refreshBrowse() {
        if (tab.isBrowse) loadBrowse(tab)
    }

    // ---- 模型转换 ----------------------------------------------------------

    private fun imageUrl(raw: String?): String? = graph.imageUrlBuilder.proxied(raw)

    private fun DoubanItem.toPoster(): PosterItem {
        val meta = listOf(rate, year).filter { it.isNotBlank() }.joinToString(" · ")
        return PosterItem(
            key = "douban:$id:$title",
            title = title,
            subtitle = meta.ifBlank { null },
            posterUrl = imageUrl(poster),
            badge = rate.takeIf { it.isNotBlank() },
            target = OpenTarget.Douban(title = title, doubanId = id),
        )
    }

    private fun BangumiSubject.toPoster(): PosterItem = PosterItem(
        key = "bangumi:$id",
        title = displayTitle,
        subtitle = year.ifBlank { null },
        posterUrl = imageUrl(images?.best),
        badge = rating?.score?.takeIf { it > 0 }?.let { String.format("%.1f", it) },
        target = OpenTarget.Douban(title = displayTitle),
    )

    private fun TmdbItem.toPoster(): PosterItem = PosterItem(
        key = "tmdb:$id",
        title = title,
        subtitle = year.ifBlank { null },
        posterUrl = imageUrl(posterUrl),
        badge = voteAverage.takeIf { it > 0 }?.let { String.format("%.1f", it) },
        target = OpenTarget.Douban(title = title),
    )

    private fun SearchResult.toPoster(): PosterItem = PosterItem(
        key = "video:$source+$id",
        title = title,
        subtitle = vodRemarks?.takeIf { it.isNotBlank() } ?: sourceName.ifBlank { null },
        posterUrl = imageUrl(poster),
        target = OpenTarget.Video(source = source, id = id, title = title),
    )

}
