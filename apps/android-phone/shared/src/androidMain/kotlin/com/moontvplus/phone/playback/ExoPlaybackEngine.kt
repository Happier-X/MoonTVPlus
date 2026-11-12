package com.moontvplus.phone.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    override fun create(): PlaybackController = ExoPlaybackController()
}

/**
 * Media3 / ExoPlayer 实现。
 *
 * 除了这个文件，界面层不应出现任何 Media3 类型；
 * 唯一的例外是 Android 的 `PlayerView` 需要拿到 [exoPlayer] 绑定画面。
 */
class ExoPlaybackController : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var progressJob: Job? = null

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(AndroidPlaybackContext.context())
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 50_000, 2_500, 5_000)
                .build()
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    _state.value = _state.value.copy(
                        status = when (playbackState) {
                            Player.STATE_IDLE -> PlaybackStatus.IDLE
                            Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                            Player.STATE_READY -> PlaybackStatus.READY
                            Player.STATE_ENDED -> PlaybackStatus.ENDED
                            else -> _state.value.status
                        },
                        durationMs = duration.coerceAtLeast(0L),
                        error = if (playbackState == Player.STATE_READY) null else _state.value.error,
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.value = _state.value.copy(
                        status = PlaybackStatus.ERROR,
                        error = error.errorCodeName + "：" + (error.message ?: "播放失败"),
                    )
                }
            })
        }

    override fun load(request: PlaybackRequest) {
        val factory = AndroidPlaybackContext.dataSourceFactory()
        val item = MediaItem.fromUri(request.url)
        val mediaSource = if (request.isHls) {
            HlsMediaSource.Factory(factory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        }

        _state.value = PlaybackState(status = PlaybackStatus.BUFFERING, error = null)
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        if (request.startPositionMs > 0L) exoPlayer.seekTo(request.startPositionMs)
        exoPlayer.playWhenReady = true
        startProgressTicker()
    }

    override fun play() {
        exoPlayer.playWhenReady = true
    }

    override fun pause() {
        exoPlayer.playWhenReady = false
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    override fun release() {
        progressJob?.cancel()
        scope.cancel()
        exoPlayer.release()
    }

    override fun currentPositionMs(): Long = exoPlayer.currentPosition.coerceAtLeast(0L)

    /** 每秒把播放位置推进 StateFlow，UI 与进度上报都读它。 */
    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(
                    positionMs = currentPositionMs(),
                    durationMs = exoPlayer.duration.coerceAtLeast(0L),
                )
                delay(1_000)
            }
        }
    }
}
