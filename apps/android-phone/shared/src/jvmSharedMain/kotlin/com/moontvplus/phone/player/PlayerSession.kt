package com.moontvplus.phone.player

import com.moontvplus.phone.cast.CastDevice
import com.moontvplus.phone.cast.CastState
import com.moontvplus.phone.cast.CastTransportState
import com.moontvplus.phone.cast.DlnaClient
import com.moontvplus.phone.core.model.PlayRecordBody
import com.moontvplus.phone.core.model.SavePlayRecordRequest
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.platform.readLocalTextSource
import com.moontvplus.phone.core.storage.DanmakuPreferences
import com.moontvplus.phone.core.storage.PlayerPreferences
import com.moontvplus.phone.core.storage.PlayerPreferencesStore
import com.moontvplus.phone.core.storage.SkipSegment
import com.moontvplus.phone.core.storage.SubtitleAppearance
import com.moontvplus.phone.danmaku.DanmakuComment
import com.moontvplus.phone.danmaku.DanmakuEpisode
import com.moontvplus.phone.danmaku.DanmakuRepository
import com.moontvplus.phone.danmaku.DanmakuState
import com.moontvplus.phone.data.media.EpisodePlan
import com.moontvplus.phone.data.media.EpisodeUrlResolver
import com.moontvplus.phone.playback.AutoNextCountdown
import com.moontvplus.phone.playback.PlaybackEngine
import com.moontvplus.phone.playback.PlaybackController
import com.moontvplus.phone.playback.PlaybackRequest
import com.moontvplus.phone.playback.PlaybackState
import com.moontvplus.phone.playback.PlaybackStatus
import com.moontvplus.phone.playback.PlayerCapabilities
import com.moontvplus.phone.playback.PlayerPlatform
import com.moontvplus.phone.playback.SkipAction
import com.moontvplus.phone.playback.SkipAdvisor
import com.moontvplus.phone.playback.TrackType
import com.moontvplus.phone.subtitles.SubtitleParser
import com.moontvplus.phone.subtitles.SubtitleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播放会话：Android 与桌面共享的播放状态机。
 *
 * 职责划分：
 * - `PlaybackController`（平台）只负责解码与渲染；
 * - 本类负责起播、自动换源、进度同步、自动下一集、弹幕、字幕、投屏、片头片尾；
 * - Compose 界面只读本类暴露的 StateFlow 并回调方法。
 *
 * 迁到 `jvmSharedMain` 的好处是这套逻辑可以在 JVM 上直接单测（自动换源、续播、
 * 起播失败降级），而不用起模拟器。
 */
class PlayerSession(
    private val api: MoonApi,
    private val serverProvider: ServerProvider,
    engine: PlaybackEngine,
    platform: PlayerPlatform? = null,
    private val preferencesStore: PlayerPreferencesStore? = null,
    private val danmakuRepository: DanmakuRepository? = null,
    private val subtitleRepository: SubtitleRepository? = null,
    private val castClient: DlnaClient? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : PlayerUiController, PlatformVideoHost {

    /** 平台播放器；Android 上需要拿 ExoPlayer 绑定画面，保留公开访问。 */
    val controller = engine.create()

    override val platformPlaybackController: PlaybackController? get() = controller

    override val playback: StateFlow<PlaybackState> = controller.state

    private val _state = MutableStateFlow(PlayerScreenState(castSupported = castClient != null))
    override val state: StateFlow<PlayerScreenState> = _state.asStateFlow()

    private val _danmaku = MutableStateFlow(DanmakuState())
    override val danmaku: StateFlow<DanmakuState> = _danmaku.asStateFlow()

    private val _cast = MutableStateFlow(CastState())
    override val cast: StateFlow<CastState> = _cast.asStateFlow()

    private val _subtitle = MutableStateFlow(SubtitleUiState())
    override val subtitle: StateFlow<SubtitleUiState> = _subtitle.asStateFlow()

    private val _preferences = MutableStateFlow(PlayerPreferences())
    override val preferences: StateFlow<PlayerPreferences> = _preferences.asStateFlow()

    override val capabilities: PlayerCapabilities = engine.capabilities.copy(
        pictureInPicture = engine.capabilities.inlinePlayback && platform?.pipSupported == true,
        brightnessGesture = engine.capabilities.inlinePlayback && platform != null,
        volumeGesture = engine.capabilities.inlinePlayback && platform != null,
        fullscreenToggle = engine.capabilities.inlinePlayback && platform?.orientationSupported == true,
        screenLock = engine.capabilities.inlinePlayback,
        danmaku = engine.capabilities.inlinePlayback,
        casting = castClient != null,
    )

    private val skipAdvisor = SkipAdvisor()

    override val platform: PlayerPlatform? = platform

    private lateinit var original: SearchResult
    private var identity: SearchResult? = null
    private var attempt: Job? = null
    private var sourceSearch: Job? = null
    private var danmakuJob: Job? = null
    private var castSearchJob: Job? = null
    private var titleSearchJob: Job? = null
    private var castPollJob: Job? = null
    private var autoNextJob: Job? = null
    private var bufferingJob: Job? = null
    private var loaded: SearchResult? = null
    private var resumeMs = 0L
    private var foreground = false
    private var shouldResume = true
    private var lastSaved: SavePlayRecordRequest? = null
    private val saveLock = Mutex()
    private var lastSaveJob: Job? = null

    /** 当前播放计划：投屏、外部播放器、切换地址都要它。 */
    private var lastPlan: EpisodePlan? = null

    private var baseSpeed = 1f
    private var speedBoostActive = false
    private var introHandledEpisode = -1
    private var outroHandledEpisode = -1
    private var endedEpisode = -1
    private var bufferingSince: Long? = null
    /** 未过滤的弹幕：改屏蔽词时在本地重新过滤，不用重新拉取 */
    private var rawDanmaku: List<DanmakuComment> = emptyList()

    init {
        if (preferencesStore != null) {
            scope.launch { applyStoredPreferences() }
        }
        scope.launch {
            playback.collect { onPlaybackChanged(it) }
        }
    }

    private suspend fun applyStoredPreferences() {
        val stored = preferencesStore?.load() ?: return
        _preferences.value = stored
        baseSpeed = stored.speed
        controller.setSpeed(stored.speed)
        _subtitle.update { it.copy(appearance = stored.subtitle) }
        _danmaku.update { it.copy(enabled = stored.danmaku.enabled, configured = stored.danmaku.configured) }
        updateState {
            it.copy(
                autoSkipIntro = stored.autoSkipIntro,
                autoSkipOutro = stored.autoSkipOutro,
            )
        }
    }

    // ---- 起播 / 换源 -------------------------------------------------------

    fun load(source: String, videoId: String, title: String, index: Int, startPositionMs: Long) {
        if (::original.isInitialized) return
        original = SearchResult(source = source, id = videoId, title = title)
        _state.value = PlayerScreenState(title = title, castSupported = castClient != null)
        start(original, index.coerceAtLeast(1), startPositionMs.coerceAtLeast(0L), autoSwitch = true)
    }

    /**
     * 只有标题时直接起播（网页版 /play?title=...&prefer=true 的安卓实现）：
     * 先用 /api/search 拿到所有源，按 matchingSources + 权重挑最佳源，再走正常起播。
     *
     * 没有结果时给明确错误并主动把片源列表拉出来，用户可以从「手动换源」重新挑。
     */
    fun loadByTitle(title: String, index: Int, positionMs: Long) {
        if (title.isBlank() || ::original.isInitialized) return
        original = SearchResult(title = title)
        _state.value = PlayerScreenState(title = title, castSupported = castClient != null)
        updateState { it.copy(loading = true, error = null, index = index.coerceAtLeast(1)) }
        titleSearchJob = scope.launch {
            val results = withTimeoutOrNull(15_000) {
                apiResult {
                    val response = api.search(title)
                    if (!response.isSuccessful) emptyList() else response.body()?.results.orEmpty()
                }
            }.orEmpty()
            val reference = SearchResult(title = title)
            val wanted = index.coerceAtLeast(1)
            val matched = matchingSources(reference, results)
                .ifEmpty {
                    // 标题带季号/后缀时严格匹配会全空：退回到按权重取同一批搜索结果
                    results
                        .filter { it.id.isNotBlank() && it.source.isNotBlank() }
                        .sortedByDescending { it.weight ?: 0.0 }
                }
                .filter { it.episodes.size >= wanted || it.episodes.isNotEmpty() }
            val best = matched.firstOrNull()
            if (best == null) {
                updateState {
                    it.copy(
                        loading = false,
                        error = "没有搜到可播放的片源，可以点「手动选源」重新查找",
                    )
                }
                findSources()
                return@launch
            }
            original = best.copy(title = title.ifBlank { best.title })
            identity = best
            start(best, wanted, positionMs.coerceAtLeast(0L), autoSwitch = true)
        }
    }

    /**
     * 直接播放一个地址（不经过片源详情）。
     *
     * 用途：Debug 构建下用测试流验证播放器界面，以及以后「粘贴地址播放」。
     * 没有剧集信息，所以选集/进度同步/片头片尾这些依赖详情的能力不会启用。
     */
    fun loadDirect(url: String, title: String) {
        if (url.isBlank() || ::original.isInitialized) return
        original = SearchResult(source = "direct", id = url, title = title)
        updateState { it.copy(title = title, loading = true, error = null) }
        attempt?.cancel()
        controller.stop()
        loaded = null
        lastPlan = null
        resumeMs = 0L
        attempt = scope.launch {
            val plan = EpisodePlan(url = url, isHls = url.contains(".m3u8", ignoreCase = true))
            if (prepare(plan)) {
                lastPlan = plan
                updateState { it.copy(loading = false, error = null) }
                controller.setSpeed(baseSpeed)
            } else {
                updateState {
                    it.copy(
                        loading = false,
                        error = playback.value.error ?: "无法播放这个地址",
                    )
                }
            }
        }
    }

    override fun retry() {
        val current = _state.value
        start(current.source ?: original, current.index, sameEpisodePosition(), autoSwitch = true)
    }

    /** 手动换源（旧入口，保留给既有调用方/测试）。 */
    fun chooseSource(source: SearchResult) {
        start(source, _state.value.index, sameEpisodePosition(), autoSwitch = false)
    }

    override fun chooseSource(sourceKey: String) {
        val target = _state.value.sources.firstOrNull { it.key == sourceKey } ?: return
        chooseSource(target)
    }

    override fun goTo(index: Int) {
        val current = _state.value
        if (index == current.index || index !in 1..current.episodeCount) return
        start(current.source ?: original, index, 0L, autoSwitch = true)
    }

    override fun playNext() {
        val current = _state.value
        if (current.hasNext) goTo(current.index + 1) else toast("已经是最后一集")
    }

    override fun findSources() {
        if (sourceSearch?.isActive == true) return
        sourceSearch = scope.launch {
            _state.update { it.copy(findingSources = true, sourceError = null) }
            val results = discoverSources()
            updateState {
                it.copy(
                    findingSources = false,
                    sources = (results.orEmpty() + listOfNotNull(identity, it.source))
                        .distinctBy { source -> source.key },
                    sourceError = if (results == null) "获取片源失败，请重试" else null,
                )
            }
            refreshDerived()
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
        autoNextJob?.cancel()
        controller.stop()
        loaded = null
        lastPlan = null
        shouldResume = true
        resumeMs = positionMs
        introHandledEpisode = -1
        outroHandledEpisode = -1
        endedEpisode = -1
        updateState {
            it.copy(
                loading = true,
                error = null,
                source = preferred,
                index = index,
                autoNext = AutoNextCountdown.Off,
                showSkipIntroButton = false,
                showSkipOutroButton = false,
                bufferingHint = null,
                weakNetwork = false,
            )
        }
        refreshDerived()
        attempt = scope.launch {
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
                if (source.key == preferred.key) {
                    updateState { it.copy(title = detail.title.ifBlank { it.title }, detail = detail) }
                    refreshDerived()
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
                        lastPlan = plan
                        updateState {
                            it.copy(
                                loading = false,
                                error = null,
                                title = detail.title.ifBlank { it.title },
                                detail = detail,
                                source = detail,
                                bufferingHint = null,
                            )
                        }
                        refreshDerived()
                        return true
                    }
                    // 已经起播过再出错：给换地址/换源一个新的尝试窗口
                    deadline = System.currentTimeMillis() + 25_000
                    resumeMs = controller.currentPositionMs().takeIf { it > 0L } ?: resumeMs
                    saveProgress()
                    loaded = null
                    lastPlan = null
                    updateState { it.copy(loading = true) }
                    controller.stop()
                }
                failure = "第 ${index} 集播放失败"
                return false
            }

            if (trySource(preferred)) {
                onEpisodeStarted()
                return@launch
            }
            if (autoSwitch) {
                val candidates = discoverSources().orEmpty()
                updateState { it.copy(sources = candidates) }
                refreshDerived()
                for (source in candidates.filter { it.key != preferred.key }.take(3)) {
                    if (trySource(source)) {
                        onEpisodeStarted()
                        return@launch
                    }
                }
                failure = "已尝试的片源均无法播放第 ${index} 集"
            }
            updateState { it.copy(loading = false, error = failure, source = preferred, detail = it.detail ?: old.detail) }
            refreshDerived()
        }
    }

    private suspend fun prepare(plan: EpisodePlan): Boolean {
        if (plan.unsupported) {
            // Media3 解不了 avi/rmvb：直接给出可操作的提示，而不是让用户等超时
            _state.update { it.copy(loading = false, error = "该片源是 avi/rmvb 等不支持的格式，请换源") }
            return false
        }
        if (!capabilities.inlinePlayback) {
            // 桌面端还没接内嵌播放器：地址解析成功就算这一集「可用」，
            // 交给外部播放器或投屏；不在这里等一个永远不会来的 READY。
            lastPlan = plan
            updateState { it.copy(loading = false, error = null, bufferingHint = null) }
            return true
        }
        controller.load(PlaybackRequest(plan.url, plan.isHls, resumeMs, autoPlay = foreground && shouldResume))
        val result = withTimeoutOrNull(8_000) {
            playback.first { it.status in setOf(PlaybackStatus.READY, PlaybackStatus.ENDED, PlaybackStatus.ERROR) }
        }
        return result?.status == PlaybackStatus.READY || result?.status == PlaybackStatus.ENDED
    }

    /** 一集真正起播后：加载弹幕、恢复字幕、应用倍速。 */
    private fun onEpisodeStarted() {
        controller.setSpeed(baseSpeed)
        scope.launch { loadDanmakuForCurrentEpisode(force = false) }
    }

    // ---- 播放控制 ---------------------------------------------------------

    override fun togglePlay() {
        if (playback.value.isPlaying) pause() else play()
    }

    override fun play() {
        shouldResume = true
        controller.play()
    }

    override fun pause() {
        shouldResume = false
        controller.pause()
    }

    override fun seekTo(positionMs: Long) {
        val duration = playback.value.durationMs
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        controller.seekTo(target)
    }

    override fun seekBy(deltaMs: Long) {
        seekTo(playback.value.positionMs + deltaMs)
    }

    override fun setSpeed(speed: Float) {
        baseSpeed = speed.coerceIn(PlayerPreferences.SPEED_MIN, PlayerPreferences.SPEED_MAX)
        speedBoostActive = false
        controller.setSpeed(baseSpeed)
        persist { it.copy(speed = baseSpeed) }
    }

    override fun setVolume(volume: Float) {
        controller.setVolume(volume)
    }

    override fun beginSpeedBoost() {
        if (speedBoostActive) return
        speedBoostActive = true
        controller.setSpeed((baseSpeed * 2f).coerceAtMost(3f))
    }

    override fun endSpeedBoost() {
        if (!speedBoostActive) return
        speedBoostActive = false
        controller.setSpeed(baseSpeed)
    }

    override fun setBrightness(value: Float) {
        platform?.setBrightness(value.coerceIn(0.01f, 1f))
    }

    override fun setSystemVolume(value: Float) {
        platform?.setSystemVolume(value.coerceIn(0f, 1f))
    }

    override fun enterPictureInPicture(): Boolean = platform?.enterPictureInPicture() == true

    // ---- 轨道 / 字幕 ------------------------------------------------------

    override fun selectTrack(type: TrackType, trackId: String?) {
        controller.selectTrack(type, trackId)
        if (type == TrackType.TEXT) {
            _subtitle.update { it.copy(activeExternalId = null) }
        }
    }

    override fun addSubtitle(url: String, label: String?) {
        if (url.isBlank()) return
        val externalId = "ext-${url.hashCode()}"
        scope.launch {
            _subtitle.update { it.copy(loading = true, message = null) }
            val isRemote = url.startsWith("http://") || url.startsWith("https://")
            val document = when {
                isRemote -> subtitleRepository?.load(url)
                    ?: Result.failure(IllegalStateException("当前环境不支持网络字幕"))
                else -> {
                    val text = readLocalTextSource(url)
                    if (text == null) {
                        Result.failure(IllegalStateException("无法读取该字幕文件"))
                    } else {
                        val parsed = SubtitleParser.parse(text, url)
                        if (parsed.empty) {
                            Result.failure(IllegalStateException("字幕内容为空或格式不支持"))
                        } else {
                            Result.success(parsed)
                        }
                    }
                }
            }
            document.fold(
                onSuccess = { parsed ->
                    val name = label?.takeIf { it.isNotBlank() } ?: SubtitleParser.labelOf(url)
                    _subtitle.update {
                        it.copy(
                            loading = false,
                            message = if (parsed.format == com.moontvplus.phone.subtitles.SubtitleDocument.Format.UNKNOWN) {
                                "字幕已加载（格式未识别，按纯文本显示）"
                            } else {
                                "已加载 ${parsed.cues.size} 条字幕"
                            },
                            external = (it.external.filterNot { track -> track.id == externalId } +
                                SubtitleTrackInfo(externalId, name, isExternal = true)).distinctBy { track -> track.id },
                            cuesById = it.cuesById + (externalId to parsed.cues),
                            activeExternalId = externalId,
                        )
                    }
                    // 外挂字幕由 Compose 渲染，关掉播放器自带字幕轨避免两套字幕叠在一起
                    controller.selectTrack(TrackType.TEXT, null)
                },
                onFailure = { error ->
                    _subtitle.update { it.copy(loading = false, message = error.message ?: "字幕加载失败") }
                },
            )
        }
    }

    override fun selectExternalSubtitle(id: String?) {
        if (id != null && _subtitle.value.cuesById[id] == null) return
        _subtitle.update { it.copy(activeExternalId = id) }
        if (id != null) controller.selectTrack(TrackType.TEXT, null)
    }

    override fun removeExternalSubtitle(id: String) {
        _subtitle.update {
            it.copy(
                external = it.external.filterNot { track -> track.id == id },
                cuesById = it.cuesById - id,
                activeExternalId = if (it.activeExternalId == id) null else it.activeExternalId,
            )
        }
    }

    override fun setSubtitleDelay(delayMs: Long) {
        val appearance = _subtitle.value.appearance.copy(delayMs = delayMs.coerceIn(-30_000L, 30_000L))
        _subtitle.update { it.copy(appearance = appearance) }
        persist { it.copy(subtitle = appearance) }
    }

    override fun setSubtitleAppearance(appearance: SubtitleAppearance) {
        val sanitized = appearance.sanitized()
        _subtitle.update { it.copy(appearance = sanitized) }
        persist { it.copy(subtitle = sanitized) }
    }

    // ---- 弹幕 -------------------------------------------------------------

    override fun refreshDanmaku() {
        scope.launch { loadDanmakuForCurrentEpisode(force = true) }
    }

    override fun setDanmakuEnabled(enabled: Boolean) {
        _danmaku.update { it.copy(enabled = enabled) }
        persist { prefs -> prefs.copy(danmaku = prefs.danmaku.copy(enabled = enabled)) }
        if (enabled) scope.launch { loadDanmakuForCurrentEpisode(force = false) }
    }

    override fun updateDanmakuPreferences(block: (PlayerPreferences) -> PlayerPreferences) {
        val updated = persist(block)
        val danmaku = updated.danmaku
        // 屏蔽词改了就地重过滤，已经拉下来的弹幕不用重新请求
        val refiltered = if (rawDanmaku.isNotEmpty()) {
            com.moontvplus.phone.danmaku.DanmakuParser.filter(rawDanmaku, danmaku.blockedWords)
        } else {
            emptyList()
        }
        _danmaku.update {
            it.copy(
                enabled = danmaku.enabled,
                configured = danmaku.configured,
                comments = if (danmaku.enabled) refiltered.ifEmpty { it.comments } else emptyList(),
                message = if (danmaku.configured) it.message else "弹幕未配置：在设置里填写弹弹play AppId / AppSecret",
            )
        }
    }

    private suspend fun loadDanmakuForCurrentEpisode(force: Boolean) {
        danmakuJob?.cancel()
        val detail = loaded ?: return
        val prefs = _preferences.value.danmaku
        if (!prefs.enabled) return
        if (!prefs.configured) {
            _danmaku.update {
                it.copy(
                    enabled = prefs.enabled,
                    configured = false,
                    loading = false,
                    message = "弹幕未配置：在播放器设置里填写弹弹play AppId / AppSecret",
                )
            }
            return
        }
        val repository = danmakuRepository
        if (repository == null) {
            _danmaku.update { it.copy(configured = prefs.configured, message = "当前环境不支持弹幕") }
            return
        }
        val index = _state.value.index
        _danmaku.update { it.copy(loading = true, message = null, configured = true) }
        val matched = runCatching { repository.search(detail.title, index, prefs) }.getOrElse { Result.failure(it) }
        matched.fold(
            onSuccess = { candidates ->
                val episode = repository.bestMatch(candidates, detail.title, index)
                if (episode == null) {
                    _danmaku.update {
                        it.copy(loading = false, comments = emptyList(), loadedForEpisode = index, message = "未找到这一集的弹幕")
                    }
                    return
                }
                val comments = runCatching { repository.comments(episode.episodeId, prefs, force) }
                    .getOrElse { Result.failure(it) }
                comments.fold(
                    onSuccess = { list ->
                        rawDanmaku = list
                        val filtered = com.moontvplus.phone.danmaku.DanmakuParser.filter(list, prefs.blockedWords)
                        _danmaku.update {
                            it.copy(
                                loading = false,
                                comments = filtered,
                                matched = episode,
                                loadedForEpisode = index,
                                message = if (filtered.isEmpty()) "该集没有弹幕" else null,
                            )
                        }
                    },
                    onFailure = { error ->
                        _danmaku.update {
                            it.copy(loading = false, comments = emptyList(), matched = episode, loadedForEpisode = index, message = error.message ?: "弹幕加载失败")
                        }
                    },
                )
            },
            onFailure = { error ->
                _danmaku.update {
                    it.copy(loading = false, message = error.message ?: "弹幕加载失败", loadedForEpisode = index, configured = true)
                }
            },
        )
    }

    // ---- 投屏 -------------------------------------------------------------

    override fun searchCastDevices() {
        val client = castClient ?: run {
            _cast.update { it.copy(message = "当前环境不支持投屏") }
            return
        }
        if (castSearchJob?.isActive == true) return
        castSearchJob = scope.launch {
            _cast.update { it.copy(searching = true, message = null) }
            client.discover().fold(
                onSuccess = { devices ->
                    _cast.update {
                        it.copy(
                            searching = false,
                            devices = devices,
                            message = if (devices.isEmpty()) "没有发现可投屏的设备，确认电视与本机在同一 Wi-Fi" else null,
                        )
                    }
                },
                onFailure = { error ->
                    _cast.update { it.copy(searching = false, message = error.message ?: "搜索设备失败") }
                },
            )
        }
    }

    override fun connectCast(device: CastDevice) {
        val client = castClient ?: return
        val request = currentEpisodeRequest()
        if (request == null) {
            _cast.update { it.copy(message = "当前没有可投屏的地址，请先起播") }
            return
        }
        scope.launch {
            _cast.update { it.copy(message = "正在连接 ${device.name}…") }
            val title = _state.value.displayTitle.ifBlank { "MoonTVPlus" } +
                " 第 ${_state.value.index} 集"
            client.setAvTransportUri(device, request.url, title).fold(
                onSuccess = {
                    client.play(device).fold(
                        onSuccess = {
                            _cast.update {
                                it.copy(
                                    connected = device,
                                    castingTitle = title,
                                    transport = CastTransportState.PLAYING,
                                    message = "已投屏到 ${device.name}",
                                )
                            }
                            // 本地播放暂停，避免两边同时响
                            controller.pause()
                            startCastPolling(device)
                        },
                        onFailure = { error ->
                            _cast.update { it.copy(message = error.message ?: "开始播放失败") }
                        },
                    )
                },
                onFailure = { error ->
                    _cast.update { it.copy(message = error.message ?: "投屏失败") }
                },
            )
        }
    }

    override fun castPlay() = castAction { client, device -> client.play(device) }
    override fun castPause() = castAction { client, device -> client.pause(device) }
    override fun castStop() = castAction { client, device -> client.stop(device) }

    override fun castSeek(positionMs: Long) = castAction { client, device ->
        client.seek(device, positionMs)
    }

    private inline fun castAction(crossinline block: suspend (DlnaClient, CastDevice) -> Result<Unit>) {
        val client = castClient ?: return
        val device = _cast.value.connected ?: return
        scope.launch {
            block(client, device).fold(
                onSuccess = { refreshCastStatus(client, device) },
                onFailure = { error -> _cast.update { it.copy(message = error.message ?: "投屏操作失败") } },
            )
        }
    }

    override fun disconnectCast() {
        val device = _cast.value.connected
        val client = castClient
        castPollJob?.cancel()
        _cast.value = CastState(devices = _cast.value.devices)
        if (device != null && client != null) {
            scope.launch { client.stop(device) }
        }
    }

    private fun startCastPolling(device: CastDevice) {
        val client = castClient ?: return
        castPollJob?.cancel()
        castPollJob = scope.launch {
            while (true) {
                delay(2_000)
                refreshCastStatus(client, device)
            }
        }
    }

    private suspend fun refreshCastStatus(client: DlnaClient, device: CastDevice) {
        val state = client.transportState(device).getOrNull() ?: return
        val position = client.positionInfo(device).getOrNull()
        _cast.update {
            it.copy(
                connected = it.connected ?: device,
                transport = state,
                positionMs = position?.positionMs ?: it.positionMs,
                durationMs = position?.durationMs?.takeIf { duration -> duration > 0 } ?: it.durationMs,
            )
        }
    }

    // ---- 片头片尾 ---------------------------------------------------------

    private fun currentSkip(): com.moontvplus.phone.core.storage.EpisodeSkip {
        val detail = loaded ?: return com.moontvplus.phone.core.storage.EpisodeSkip()
        return _preferences.value.skipOf(detail.key, _state.value.index)
    }

    override fun skipIntro() {
        val intro = currentSkip().intro ?: return
        introHandledEpisode = _state.value.index
        seekTo(intro.endMs)
        updateState { it.copy(showSkipIntroButton = false) }
    }

    override fun skipOutro() {
        outroHandledEpisode = _state.value.index
        updateState { it.copy(showSkipOutroButton = false, autoNext = AutoNextCountdown.Off) }
        val index = _state.value.index
        if (index < _state.value.episodeCount) goTo(index + 1) else pause()
    }

    override fun setAutoSkipIntro(enabled: Boolean) {
        persist { it.copy(autoSkipIntro = enabled) }
        updateState { it.copy(autoSkipIntro = enabled) }
    }

    override fun setAutoSkipOutro(enabled: Boolean) {
        persist { it.copy(autoSkipOutro = enabled) }
        updateState { it.copy(autoSkipOutro = enabled) }
    }

    override fun markIntroHere() {
        val positionMs = playback.value.positionMs
        val segment = skipAdvisor.inferIntro(positionMs)
        if (segment == null) {
            toast("当前位置太靠前，至少播放 3 秒后再标记片头")
            return
        }
        val detail = loaded ?: return
        saveSkip(detail.key, _state.value.index) { it.copy(intro = segment) }
        introHandledEpisode = -1
        toast("已记住本集片头：0:00 – ${formatMs(segment.endMs)}")
    }

    override fun markOutroHere() {
        val positionMs = playback.value.positionMs
        val segment = skipAdvisor.inferOutro(positionMs, playback.value.durationMs)
        if (segment == null) {
            toast("当前位置太靠前，无法标记片尾")
            return
        }
        val detail = loaded ?: return
        saveSkip(detail.key, _state.value.index) { it.copy(outro = segment) }
        outroHandledEpisode = -1
        toast("已记住本集片尾：${formatMs(segment.startMs)} 起")
    }

    override fun clearSkipMarks() {
        val detail = loaded ?: return
        val key = _preferences.value.skipKey(detail.key, _state.value.index)
        persist { it.copy(skips = it.skips - key) }
        introHandledEpisode = -1
        outroHandledEpisode = -1
        updateState { it.copy(intro = null, outro = null, showSkipIntroButton = false, showSkipOutroButton = false) }
        toast("已清除本集的片头片尾记录")
    }

    private fun saveSkip(
        detailKey: String,
        episode: Int,
        transform: (com.moontvplus.phone.core.storage.EpisodeSkip) -> com.moontvplus.phone.core.storage.EpisodeSkip,
    ) {
        val key = _preferences.value.skipKey(detailKey, episode)
        val updatedPrefs = persist { prefs ->
            val current = prefs.skips[key] ?: com.moontvplus.phone.core.storage.EpisodeSkip()
            prefs.copy(skips = prefs.skips + (key to transform(current)))
        }
        val skip = updatedPrefs.skips[key] ?: return
        updateState { it.copy(intro = skip.intro, outro = skip.outro) }
    }

    private fun formatMs(value: Long): String {
        val total = value.coerceAtLeast(0L) / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ---- 自动下一集 --------------------------------------------------------

    override fun cancelAutoNext() {
        autoNextJob?.cancel()
        autoNextJob = null
        updateState { it.copy(autoNext = AutoNextCountdown.Off) }
    }

    private fun startAutoNext() {
        autoNextJob?.cancel()
        autoNextJob = scope.launch {
            var remaining = AutoNextCountdown.DEFAULT_MS
            updateState { it.copy(autoNext = AutoNextCountdown.start(remaining)) }
            while (remaining > 0L) {
                delay(250)
                remaining -= 250
                updateState { it.copy(autoNext = AutoNextCountdown.start(remaining)) }
            }
            updateState { it.copy(autoNext = AutoNextCountdown.Off) }
            val index = _state.value.index
            if (_state.value.hasNext) goTo(index + 1)
        }
    }

    // ---- 播放状态监听 -----------------------------------------------------

    private fun onPlaybackChanged(state: PlaybackState) {
        if (state.status == PlaybackStatus.BUFFERING) {
            if (bufferingSince == null) {
                bufferingSince = System.currentTimeMillis()
                bufferingJob?.cancel()
                bufferingJob = scope.launch {
                    delay(WEAK_NETWORK_HINT_MS)
                    if (playback.value.status == PlaybackStatus.BUFFERING && loaded != null) {
                        updateState {
                            it.copy(weakNetwork = true, bufferingHint = "网络较慢，正在缓冲…（已等待 ${WEAK_NETWORK_HINT_MS / 1000} 秒）")
                        }
                    }
                }
            }
        } else {
            if (bufferingSince != null) {
                bufferingSince = null
                bufferingJob?.cancel()
                bufferingJob = null
                updateState { it.copy(weakNetwork = false, bufferingHint = null) }
            }
        }
        // 播放进度变化时更新片头片尾按钮与自动跳过
        if (state.status == PlaybackStatus.READY || state.status == PlaybackStatus.BUFFERING) {
            evaluateSkip(state.positionMs, state.durationMs)
        }
        if (state.status == PlaybackStatus.ENDED && loaded != null) {
            if (endedEpisode != _state.value.index) {
                endedEpisode = _state.value.index
                saveProgress()
                if (_preferences.value.autoNext && _state.value.hasNext) startAutoNext()
            }
        }
        if (state.status == PlaybackStatus.READY) endedEpisode = -1
    }

    private fun evaluateSkip(positionMs: Long, durationMs: Long) {
        val detail = loaded ?: return
        val prefs = _preferences.value
        val skip = prefs.skipOf(detail.key, _state.value.index)
        val advice = skipAdvisor.advise(
            preferences = prefs,
            episode = skip,
            positionMs = positionMs,
            durationMs = durationMs,
            introHandled = introHandledEpisode == _state.value.index,
            outroHandled = outroHandledEpisode == _state.value.index,
        )
        when (val action = advice.autoAction) {
            is SkipAction.Intro -> {
                introHandledEpisode = _state.value.index
                seekTo(action.targetMs)
            }
            SkipAction.Outro -> {
                outroHandledEpisode = _state.value.index
                if (_state.value.hasNext) {
                    goTo(_state.value.index + 1)
                } else {
                    pause()
                }
                return
            }
            null -> Unit
        }
        if (_state.value.intro != skip.intro || _state.value.outro != skip.outro ||
            _state.value.showSkipIntroButton != advice.showIntroButton ||
            _state.value.showSkipOutroButton != advice.showOutroButton
        ) {
            updateState {
                it.copy(
                    intro = skip.intro,
                    outro = skip.outro,
                    showSkipIntroButton = advice.showIntroButton,
                    showSkipOutroButton = advice.showOutroButton,
                )
            }
        }
    }

    // ---- 生命周期 / 进度 ---------------------------------------------------

    fun setForeground(active: Boolean, wasPlaying: Boolean = false) = onForeground(active, wasPlaying)

    override fun onForeground(active: Boolean, wasPlaying: Boolean) {
        foreground = active
        if (!active) {
            if (loaded != null) shouldResume = wasPlaying
            saveProgress()
            controller.pause()
        } else if (shouldResume && (loaded != null || playback.value.status == PlaybackStatus.BUFFERING)) {
            controller.play()
        }
    }

    override fun clearToast() {
        if (_state.value.toast != null) _state.update { it.copy(toast = null) }
    }

    private fun toast(message: String) {
        updateState { it.copy(toast = message) }
    }

    override fun saveProgress() {
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
        lastSaveJob = scope.launch {
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

    override suspend fun awaitProgress() {
        lastSaveJob?.join()
    }

    override fun currentEpisodeRequest(): PlaybackRequest? {
        val plan = lastPlan ?: return null
        return PlaybackRequest(plan.url, plan.isHls, playback.value.positionMs)
    }

    /** 供 UI 的「外部播放器打开」使用。 */
    fun externalUrl(): String? = lastPlan?.url

    /** 释放资源。Android 的 ViewModel / 桌面窗口关闭时调用。 */
    fun close() {
        attempt?.cancel()
        sourceSearch?.cancel()
        danmakuJob?.cancel()
        castSearchJob?.cancel()
        castPollJob?.cancel()
        autoNextJob?.cancel()
        bufferingJob?.cancel()
        controller.release()
        scope.cancel()
    }

    /** 兼容旧调用：PlayerViewModel 里曾经用 viewModelScope 显式取消。 */
    fun dispose() = close()

    // ---- 内部：偏好与派生状态 ---------------------------------------------

    private fun persist(block: (PlayerPreferences) -> PlayerPreferences): PlayerPreferences {
        val updated = block(_preferences.value).sanitized()
        _preferences.value = updated
        scope.launch { preferencesStore?.update { updated } }
        return updated
    }

    private fun updateState(block: (PlayerScreenState) -> PlayerScreenState) {
        _state.update(block)
    }

    /** 把 sources/detail 变化同步到面板选项上。 */
    private fun refreshDerived() {
        val current = _state.value
        val detail = current.detail
        val currentSourceKey = current.source?.key.orEmpty()
        val sourceOptions = (current.sources + listOfNotNull(current.source, detail))
            .filter { it.key.isNotBlank() }
            .distinctBy { it.key }
            .sortedByDescending { it.weight ?: 0.0 }
            .map {
                PlayerSourceOption(
                    key = it.key,
                    label = it.sourceName.ifBlank { it.source },
                    isCurrent = it.key == currentSourceKey,
                )
            }
        val episodeOptions = detail?.episodesTitles.orEmpty().let { titles ->
            val count = current.episodeCount
            (1..count).map { index ->
                PlayerEpisodeOption(
                    index = index,
                    title = titles.getOrNull(index - 1)?.takeIf { it.isNotBlank() } ?: "第 $index 集",
                    isCurrent = index == current.index,
                )
            }
        }
        val skip = detail?.let { _preferences.value.skipOf(it.key, current.index) }
        updateState {
            it.copy(
                sourceOptions = sourceOptions,
                episodeOptions = episodeOptions,
                episodeTitle = episodeOptions.firstOrNull { option -> option.isCurrent }?.title.orEmpty(),
                intro = skip?.intro,
                outro = skip?.outro,
            )
        }
    }

    private companion object {
        const val WEAK_NETWORK_HINT_MS = 8_000L
    }
}

/** 同一部剧在不同源之间的匹配规则（与网页版一致）。 */
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

internal suspend fun <T> apiResult(call: suspend () -> T): T? = try {
    call()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
