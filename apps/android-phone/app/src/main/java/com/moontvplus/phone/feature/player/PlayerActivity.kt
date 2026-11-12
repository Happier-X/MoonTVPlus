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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.ui.PlayerView
import com.moontvplus.phone.core.ui.theme.MoonTvTheme
import com.moontvplus.phone.di.appContainer
import com.moontvplus.phone.playback.ExoPlaybackController
import com.moontvplus.phone.playback.PlaybackController
import com.moontvplus.phone.playback.PlaybackStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class PlayerActivity : ComponentActivity() {

    private val container by lazy { appContainer(this) }

    private val viewModel: PlayerViewModel by viewModels {
        viewModelFactory {
            initializer {
                PlayerViewModel(
                    api = container.graph.moonApi,
                    serverProvider = container.graph.serverProvider,
                    engine = container.graph.playbackEngine,
                )
            }
        }
    }

    /** 具体播放器由平台提供；Android 这里是 Media3 实现，生命周期跟着 ViewModel。 */
    private val controller: PlaybackController get() = viewModel.controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                    controller = controller,
                    onSaveProgress = ::saveProgress,
                    onBack = { finish() },
                    onEnterPip = { enterPip() },
                )
            }
        }
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
        val position = controller.currentPositionMs()
        if (position <= 0) return
        viewModel.saveProgress(position, viewModel.playback.value.durationMs)
    }

    override fun onStop() {
        saveProgress()
        viewModel.pause()
        super.onStop()
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_POSITION = "position"

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
    controller: PlaybackController,
    onSaveProgress: () -> Unit,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    // 播完自动下一集
    LaunchedEffect(playback.status) {
        if (playback.status == PlaybackStatus.ENDED) {
            val next = state.index + 1
            if (next <= state.episodeCount) viewModel.goTo(next)
        }
    }

    // 每 5 秒上报一次进度（离开时 onStop 再补一次）
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5_000)
            onSaveProgress()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 画面绑定到平台播放器：这里是 Android 的 ExoPlayer
        val exoPlayer = (controller as? ExoPlaybackController)?.exoPlayer
        if (exoPlayer != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        player = exoPlayer
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
            )
        }

        if (state.loading || playback.status == PlaybackStatus.BUFFERING) {
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

        val error = state.error ?: playback.error
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
