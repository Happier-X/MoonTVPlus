package com.moontvplus.phone.feature.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moontvplus.phone.di.appContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.media3.ui.PlayerView
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.ui.theme.MoonTvTheme
import com.moontvplus.phone.data.media.EpisodePlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient

class PlayerActivity : ComponentActivity() {

    private val container by lazy { appContainer(this) }
    private val mediaClient: OkHttpClient by lazy { container.graph.mediaClient }
    private val serverProvider: ServerProvider by lazy { container.graph.serverProvider }

    private val viewModel: PlayerViewModel by viewModels {
        viewModelFactory {
            initializer {
                PlayerViewModel(container.graph.moonApi, container.graph.serverProvider)
            }
        }
    }
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        player = buildPlayer()

        viewModel.load(
            source = intent.getStringExtra(EXTRA_SOURCE).orEmpty(),
            videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            index = intent.getIntExtra(EXTRA_INDEX, 1),
            startPositionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
        )

        setContent {
            MoonTvTheme(darkTheme = true) {
                PlayerScreen(
                    viewModel = viewModel,
                    player = player,
                    onApplyPlan = ::applyPlan,
                    onSaveProgress = ::saveProgress,
                    onBack = { finish() },
                    onEnterPip = { enterPip() },
                )
            }
        }
    }

    /** 站点自己的代理请求不带 Referer；第三方分片补浏览器 UA + Referer，绕过防盗链。 */
    private fun dataSourceFactory(): ResolvingDataSource.Factory {
        val origin = serverProvider.current()?.origin.orEmpty()
        val siteHost = serverProvider.current()?.base?.host.orEmpty()
        val base = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(mediaClient))
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

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 50_000, 2_500, 5_000)
            .build()

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        viewModel.reportError(
                            error.errorCodeName + "：" + (error.message ?: "播放失败")
                        )
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) onEnded()
                    }
                })
            }
    }

    private fun applyPlan(plan: EpisodePlan, startPositionMs: Long) {
        val exo = player ?: return
        val factory = dataSourceFactory()
        val item = MediaItem.fromUri(plan.url)
        val mediaSource = if (plan.isHls) {
            HlsMediaSource.Factory(factory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        }

        exo.setMediaSource(mediaSource)
        exo.prepare()
        if (startPositionMs > 0L) exo.seekTo(startPositionMs)
        exo.playWhenReady = true
    }

    private fun onEnded() {
        val current = viewModel.state.value
        val next = current.index + 1
        if (next <= current.episodeCount) viewModel.goTo(next)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }
        runCatching { enterPictureInPictureMode(builder.build()) }
    }

    private fun saveProgress() {
        val exo = player ?: return
        val position = exo.currentPosition
        if (position <= 0) return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        viewModel.saveProgress(position, duration)
    }

    override fun onStop() {
        saveProgress()
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_POSITION = "position"

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        fun intent(
            context: Context,
            source: String,
            videoId: String,
            title: String,
            index: Int,
            positionMs: Long = 0L,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_VIDEO_ID, videoId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_INDEX, index)
            putExtra(EXTRA_POSITION, positionMs)
        }
    }
}

@Composable
private fun PlayerScreen(
    viewModel: PlayerViewModel,
    player: ExoPlayer?,
    onApplyPlan: (EpisodePlan, Long) -> Unit,
    onSaveProgress: () -> Unit,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
            )
        }

        // 地址或集数变化时换源；首播按续播位置 seek
        LaunchedEffect(state.plan?.url, state.index) {
            state.plan?.let { onApplyPlan(it, state.startPositionMs) }
        }

        // 每 5 秒上报一次进度（离开时 onStop 再补一次）
        LaunchedEffect(state.plan?.url) {
            while (isActive) {
                delay(5_000)
                onSaveProgress()
            }
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { "播放" },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    text = "第 ${state.index} 集 / 共 ${state.episodeCount} 集",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            OutlinedButton(onClick = onEnterPip) { Text("画中画") }
        }

        val error = state.error
        if (error != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = error,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick = { viewModel.fallbackToDirect() }) { Text("换个地址重试") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val next = state.index + 1
                            if (next <= state.episodeCount) viewModel.goTo(next)
                        },
                    ) { Text("下一集") }
                }
            }
        }
    }
}
