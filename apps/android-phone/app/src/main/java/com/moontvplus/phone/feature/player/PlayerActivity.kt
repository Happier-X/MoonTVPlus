package com.moontvplus.phone.feature.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.ui.PlayerView
import com.moontvplus.phone.core.ui.theme.MoonTvTheme
import com.moontvplus.phone.di.appContainer
import com.moontvplus.phone.playback.ExoPlaybackController
import com.moontvplus.phone.playback.PlaybackStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PlayerActivity : ComponentActivity() {
    private val container by lazy { appContainer(this) }
    private val viewModel: PlayerViewModel by viewModels {
        viewModelFactory {
            initializer {
                PlayerViewModel(container.graph.moonApi, container.graph.serverProvider, container.graph.playbackEngine)
            }
        }
    }
    private var fullscreen by mutableStateOf(false)
    private var pip by mutableStateOf(false)
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.load(
            intent.getStringExtra("source").orEmpty(), intent.getStringExtra("video_id").orEmpty(),
            intent.getStringExtra("title").orEmpty(), intent.getIntExtra("index", 1),
            intent.getLongExtra("position", 0L),
        )
        if (savedInstanceState?.getBoolean("fullscreen") == true) applyFullscreen(true)
        setContent {
            MoonTvTheme(darkTheme = true) {
                PlayerScreen(
                    viewModel, fullscreen, pip,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        packageManager.hasSystemFeature("android.software.picture_in_picture"),
                    { applyFullscreen(!fullscreen) }, ::back, ::enterPip,
                )
            }
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    private fun applyFullscreen(enabled: Boolean) {
        fullscreen = enabled
        requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) insets.hide(WindowInsetsCompat.Type.systemBars())
        else insets.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun back() {
        if (fullscreen) {
            applyFullscreen(false)
            return
        }
        if (leaving) return
        leaving = true
        viewModel.setForeground(false)
        lifecycleScope.launch {
            withTimeoutOrNull(1_500) { viewModel.awaitProgress() }
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("fullscreen", fullscreen)
        super.onSaveInstanceState(outState)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
    }

    override fun onStop() {
        if (!pip) {
            val wasPlaying = (viewModel.controller as? ExoPlaybackController)?.exoPlayer?.playWhenReady == true
            viewModel.setForeground(false, wasPlaying)
        }
        super.onStop()
    }

    companion object {
        fun intent(context: Context, source: String, videoId: String, title: String, index: Int, positionMs: Long = 0L) =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra("source", source)
                putExtra("video_id", videoId)
                putExtra("title", title)
                putExtra("index", index)
                putExtra("position", positionMs)
            }
    }
}

@Composable
private fun PlayerScreen(
    viewModel: PlayerViewModel,
    fullscreen: Boolean,
    pip: Boolean,
    pipSupported: Boolean,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = !pip, onBack = onBack)

    fun openSources() {
        picker = "sources"
        viewModel.findSources()
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5_000)
            viewModel.saveProgress()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val exoPlayer = (viewModel.controller as? ExoPlaybackController)?.exoPlayer
        if (exoPlayer != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        player = exoPlayer
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.useController = !pip && !state.loading && state.error == null },
                onRelease = { it.player = null },
            )
        }
        if (!pip) {
            if (state.loading || playback.status == PlaybackStatus.BUFFERING) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    if (state.loading) Text("正在尝试播放第 ${state.index} 集", color = Color.White)
                }
            }
            state.error?.let { error ->
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(error, color = Color.White, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::retry) { Text("重试") }
                        OutlinedButton(onClick = ::openSources) { Text("手动换源") }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("返回") }
                    Column(Modifier.weight(1f)) {
                        Text(state.title.ifBlank { "播放" }, color = Color.White, maxLines = 1)
                        val sourceName = state.source?.let { it.sourceName.ifBlank { it.source } }.orEmpty()
                        Text("第 ${state.index} 集 · $sourceName", color = Color.White, maxLines = 1)
                    }
                    TextButton(onClick = onToggleFullscreen) { Text(if (fullscreen) "退出全屏" else "全屏") }
                    if (pipSupported && !fullscreen) TextButton(onClick = onEnterPip) { Text("画中画") }
                }
                Row {
                    TextButton(enabled = state.detail != null, onClick = { picker = "episodes" }) { Text("选集") }
                    TextButton(enabled = state.index < state.episodeCount, onClick = { viewModel.goTo(state.index + 1) }) {
                        Text("下一集")
                    }
                    if (state.error == null) TextButton(onClick = ::openSources) { Text("换源") }
                }
            }
        }
    }

    if (picker != null && !pip) {
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (picker == "episodes") "选集" else "手动换源") },
            text = {
                if (picker == "episodes") {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(state.episodeCount) { offset ->
                            val index = offset + 1
                            val title = state.detail?.episodesTitles?.getOrNull(offset)?.takeIf { it.isNotBlank() }
                            TextButton(onClick = { picker = null; viewModel.goTo(index) }) {
                                Text((title ?: "第 $index 集") + if (index == state.index) "（当前）" else "")
                            }
                        }
                    }
                } else when {
                    state.findingSources -> CircularProgressIndicator()
                    state.sourceError != null -> Text(state.sourceError.orEmpty())
                    state.sources.isEmpty() -> Text("没有找到其他片源")
                    else -> LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(state.sources, key = { it.key }) { source ->
                            val current = source.key == state.source?.key
                            TextButton(onClick = { picker = null; viewModel.chooseSource(source) }) {
                                Text(source.sourceName.ifBlank { source.source } + if (current) "（当前）" else "")
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("关闭") } },
            dismissButton = {
                if (picker == "sources" && !state.findingSources) {
                    TextButton(onClick = viewModel::findSources) { Text("重新查找") }
                }
            },
        )
    }
}
