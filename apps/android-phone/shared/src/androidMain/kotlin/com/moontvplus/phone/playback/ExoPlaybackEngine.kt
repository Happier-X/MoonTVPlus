package com.moontvplus.phone.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Android 侧给共享播放层注入的运行时依赖。
 *
 * 由 `AppContainer` 在创建界面之前调用 [install]；里面不做初始化逻辑，
 * 只是把 Context 与共享 OkHttp 交给实现。
 */
object AndroidPlaybackContext {

    private var applicationContext: Context? = null
    private var clientProvider: () -> OkHttpClient = { error("AndroidPlaybackContext 未安装 OkHttp") }
    private var siteHostProvider: () -> String? = { null }
    private var originProvider: () -> String = { "" }

    fun install(
        context: Context,
        client: () -> OkHttpClient,
        siteHost: () -> String?,
        origin: () -> String,
    ) {
        applicationContext = context.applicationContext
        clientProvider = client
        siteHostProvider = siteHost
        originProvider = origin
    }

    fun contextOrNull(): Context? = applicationContext

    internal fun context(): Context =
        applicationContext ?: error("AndroidPlaybackContext 未安装 Context")

    internal fun client(): OkHttpClient = clientProvider()
    internal fun siteHost(): String? = siteHostProvider()
    internal fun origin(): String = originProvider()

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 站点自己的代理请求不带 Referer；第三方分片补浏览器 UA + Referer，绕过防盗链。
     */
    internal fun dataSourceFactory(): ResolvingDataSource.Factory {
        val base = DefaultDataSource.Factory(
            context(),
            OkHttpDataSource.Factory(client()),
        )
        val siteHost = siteHost()
        val origin = origin()
        return ResolvingDataSource.Factory(base) { dataSpec ->
            if (dataSpec.uri.host.orEmpty() == siteHost) {
                dataSpec
            } else {
                dataSpec.withRequestHeaders(
                    mapOf(
                        "User-Agent" to BROWSER_UA,
                        "Referer" to "$origin/",
                    )
                )
            }
        }
    }
}

actual fun createPlaybackEngine(): PlaybackEngine = ExoPlaybackEngine()

class ExoPlaybackEngine : PlaybackEngine {
    override val supportsInlinePlayback: Boolean = true

    override val capabilities: PlayerCapabilities = PlayerCapabilities(
        inlinePlayback = true,
        speedControl = true,
        trackSelection = true,
        subtitles = true,
        externalSubtitles = true,
        seekBar = true,
    )

    override fun create(): PlaybackController = ExoPlaybackController()
}

/**
 * Media3 / ExoPlayer 实现。
 *
 * 除了这个文件，界面层不应出现任何 Media3 类型；唯一的例外是 Android 的
 * `PlayerView` 需要拿到 [exoPlayer] 绑定画面（见 `ui/src/androidMain` 的 VideoSurface）。
 */
@UnstableApi
class ExoPlaybackController : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var progressJob: Job? = null
    private var currentRequest: PlaybackRequest? = null
    private var externalSubtitles: List<ExternalSubtitle> = emptyList()
    private var baseSpeed: Float = 1f
    private var endHandled = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) endHandled = false
            _state.value = _state.value.copy(
                status = when (playbackState) {
                    // 出错后 ExoPlayer 会回到 IDLE；保留 ERROR，避免错误被静默吞掉。
                    // load() 会先把状态置为 BUFFERING，所以换集时不会继承上一集的错误。
                    Player.STATE_IDLE -> if (_state.value.status == PlaybackStatus.ERROR) {
                        PlaybackStatus.ERROR
                    } else {
                        PlaybackStatus.IDLE
                    }
                    Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                    Player.STATE_READY -> PlaybackStatus.READY
                    Player.STATE_ENDED -> PlaybackStatus.ENDED
                    else -> _state.value.status
                },
                durationMs = durationOrZero(),
                error = if (playbackState == Player.STATE_READY) null else _state.value.error,
            )
            if (playbackState == Player.STATE_BUFFERING) _state.value = _state.value.copy(isPlaying = false)
            syncVolatile()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w("MoonTVPlusPlayer", "playback error", error)
            _state.value = _state.value.copy(
                status = PlaybackStatus.ERROR,
                isPlaying = false,
                error = error.errorCodeName + "：" + (error.message ?: "播放失败"),
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.value = _state.value.copy(
                videoSize = com.moontvplus.phone.playback.VideoSize(videoSize.width, videoSize.height),
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.value = _state.value.copy(tracks = mapTracks(tracks))
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _state.value = _state.value.copy(speed = playbackParameters.speed)
        }
    }

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(AndroidPlaybackContext.context())
        .setLoadControl(
            DefaultLoadControl.Builder()
                // 3 秒缓冲目标：弱网下别等太满，正常网络也不会频繁卡顿
                .setBufferDurationsMs(30_000, 50_000, 2_000, 4_000)
                .build()
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply { addListener(listener) }

    private fun durationOrZero(): Long = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

    private fun mapTracks(tracks: Tracks): List<PlaybackTrack> {
        val result = mutableListOf<PlaybackTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_VIDEO -> TrackType.VIDEO
                C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                C.TRACK_TYPE_TEXT -> TrackType.TEXT
                else -> null
            } ?: return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val quality = format.height.takeIf { it > 0 }?.let { height ->
                    when {
                        height >= 2160 -> "4K"
                        height >= 1440 -> "2K"
                        height >= 1080 -> "1080P"
                        height >= 720 -> "720P"
                        height >= 480 -> "480P"
                        height >= 360 -> "360P"
                        else -> "${height}P"
                    }
                }
                result += PlaybackTrack(
                    id = "$groupIndex:$trackIndex",
                    type = type,
                    label = format.label.orEmpty().ifBlank {
                        format.language.orEmpty().ifBlank { quality ?: "轨道 ${result.size + 1}" }
                    },
                    language = format.language,
                    isSelected = group.isTrackSelected(trackIndex),
                    isExternal = format.id?.startsWith("external-") == true,
                    qualityLabel = quality,
                    widthPx = format.width,
                    heightPx = format.height,
                    bandwidth = format.bitrate.takeIf { it > 0 } ?: 0,
                )
            }
        }
        return result
    }

    override fun load(request: PlaybackRequest) {
        currentRequest = request
        if (request.subtitles.isNotEmpty()) externalSubtitles = request.subtitles
        _state.value = PlaybackState(status = PlaybackStatus.BUFFERING, error = null)
        exoPlayer.setMediaSource(buildSource(request), request.startPositionMs)
        exoPlayer.playWhenReady = request.autoPlay
        exoPlayer.prepare()
        if (request.subtitles.isNotEmpty()) {
            // 外部字幕默认选中，用户仍可在字幕面板里切回内嵌或关闭
            selectTextTrackBestEffort(lastExternalIndex = request.subtitles.lastIndex)
        }
        startProgressTicker()
    }

    @UnstableApi
    private fun buildSource(request: PlaybackRequest): MediaSource {
        val factory = AndroidPlaybackContext.dataSourceFactory()
        val subtitles = (externalSubtitles + request.subtitles).distinctBy { it.url }
        val builder = MediaItem.Builder().setUri(request.url)
        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                        .setMimeType(subtitle.mimeType)
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label ?: "外挂字幕")
                        .setId("external-${subtitle.url.hashCode()}")
                        .build()
                },
            )
        }
        val item = builder.build()
        return if (request.isHls) {
            HlsMediaSource.Factory(factory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        }
    }

    override fun play() {
        exoPlayer.playWhenReady = true
    }

    override fun pause() {
        exoPlayer.playWhenReady = false
        _state.value = _state.value.copy(isPlaying = false)
    }

    override fun stop() {
        progressJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentRequest = null
        endHandled = false
        _state.value = PlaybackState()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
        _state.value = _state.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    private var boosting = false

    override fun setSpeed(speed: Float) {
        baseSpeed = speed.coerceIn(0.25f, 3f)
        // 长按加速期间不要覆盖临时倍速
        if (!boosting) exoPlayer.setPlaybackSpeed(baseSpeed)
    }

    /** 长按 2x：在基础倍速上临时加速，松手恢复。 */
    fun setTemporarySpeedBoost(active: Boolean, boost: Float = 2f) {
        boosting = active
        exoPlayer.setPlaybackSpeed(if (active) (baseSpeed * boost).coerceAtMost(4f) else baseSpeed)
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        exoPlayer.volume = clamped
        _state.value = _state.value.copy(volume = clamped)
    }

    override fun selectTrack(type: TrackType, trackId: String?): Boolean {
        return runCatching {
            val builder = exoPlayer.trackSelectionParameters.buildUpon()
            val mediaType = when (type) {
                TrackType.VIDEO -> C.TRACK_TYPE_VIDEO
                TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
                TrackType.TEXT -> C.TRACK_TYPE_TEXT
            }
            if (trackId == null) {
                if (type != TrackType.TEXT) return false
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            } else {
                val parts = trackId.split(':')
                if (parts.size != 2) return false
                val groupIndex = parts[0].toIntOrNull() ?: return false
                val trackIndex = parts[1].toIntOrNull() ?: return false
                val tracks = exoPlayer.currentTracks
                if (groupIndex !in 0 until tracks.groups.size) return false
                val group = tracks.groups[groupIndex]
                if (trackIndex !in 0 until group.length) return false
                builder.setTrackTypeDisabled(mediaType, false)
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            }
            exoPlayer.trackSelectionParameters = builder.build()
            // 立即把选中态写回，别等 onTracksChanged 的下一帧
            _state.value = _state.value.copy(tracks = _state.value.tracks.map { track ->
                if (track.type != type) track else track.copy(isSelected = track.id == trackId)
            })
            true
        }.getOrDefault(false)
    }

    override fun addExternalSubtitle(subtitle: ExternalSubtitle): Boolean {
        return runCatching {
            externalSubtitles = (externalSubtitles + subtitle).distinctBy { it.url }
            val request = currentRequest ?: return false
            val position = currentPositionMs()
            val wasPlaying = exoPlayer.playWhenReady
            _state.value = _state.value.copy(status = PlaybackStatus.BUFFERING, error = null)
            exoPlayer.setMediaSource(buildSource(request.copy(subtitles = emptyList())), position)
            exoPlayer.playWhenReady = wasPlaying
            exoPlayer.prepare()
            selectTextTrackBestEffort(lastExternalIndex = externalSubtitles.lastIndex)
            true
        }.getOrDefault(false)
    }

    private fun selectTextTrackBestEffort(lastExternalIndex: Int) {
        val textGroups = exoPlayer.currentTracks.groups
            .withIndex()
            .filter { it.value.type == C.TRACK_TYPE_TEXT }
        val target = textGroups.lastOrNull() ?: return
        selectTrack(TrackType.TEXT, "${target.index}:0")
    }

    override fun release() {
        progressJob?.cancel()
        scope.cancel()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    override fun currentPositionMs(): Long = exoPlayer.currentPosition.coerceAtLeast(0L)

    private fun syncVolatile() {
        _state.value = _state.value.copy(
            isPlaying = exoPlayer.isPlaying,
            bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
            speed = exoPlayer.playbackParameters.speed,
            volume = exoPlayer.volume,
            isLive = exoPlayer.isCurrentMediaItemLive,
            selectedVideoTrackId = _state.value.tracks.firstOrNull { it.type == TrackType.VIDEO && it.isSelected }?.id,
            selectedAudioTrackId = _state.value.tracks.firstOrNull { it.type == TrackType.AUDIO && it.isSelected }?.id,
            selectedTextTrackId = _state.value.tracks.firstOrNull { it.type == TrackType.TEXT && it.isSelected }?.id,
        )
    }

    /** 每秒把播放位置推进 StateFlow，UI 与进度上报都读它。 */
    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(
                    positionMs = currentPositionMs(),
                    durationMs = durationOrZero(),
                    bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
                    isPlaying = exoPlayer.isPlaying,
                )
                delay(500)
            }
        }
    }

    /** 播放结束只上报一次，避免倒计时被反复重置。 */
    fun consumeEnded(): Boolean {
        if (_state.value.status != PlaybackStatus.ENDED || endHandled) return false
        endHandled = true
        return true
    }
}
