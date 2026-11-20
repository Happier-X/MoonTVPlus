package com.moontvplus.phone.feature.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moontvplus.phone.cast.DlnaClient
import com.moontvplus.phone.core.storage.PlayerPreferencesStore
import com.moontvplus.phone.core.ui.theme.MoonTvTheme
import com.moontvplus.phone.danmaku.DanmakuRepository
import com.moontvplus.phone.di.appContainer
import com.moontvplus.phone.subtitles.SubtitleRepository
import com.moontvplus.ui.player.PlayerScreen
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * 播放页宿主。
 *
 * 界面本体在 `:ui` 的 `PlayerScreen`（commonMain），这里只保留 Activity 职责：
 * 生命周期、方向/全屏、亮度与音量桥接、画中画、字幕文件选择、返回结果。
 */
class PlayerActivity : ComponentActivity() {

    private val container by lazy { appContainer(this) }
    private val viewModel: PlayerViewModel by viewModels {
        viewModelFactory {
            initializer {
                val graph = container.graph
                // 弹幕/字幕/投屏要走设备自己的地址：业务 client 会把 host 改写成站点地址
                val externalClient = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                PlayerViewModel(
                    api = graph.moonApi,
                    serverProvider = graph.serverProvider,
                    engine = graph.playbackEngine,
                    platform = AndroidPlayerPlatform(this@PlayerActivity),
                    preferencesStore = PlayerPreferencesStore(graph.textStore, graph.json),
                    danmakuRepository = DanmakuRepository(externalClient, graph.textStore, graph.json),
                    subtitleRepository = SubtitleRepository(externalClient, graph.textStore),
                    castClient = DlnaClient(externalClient),
                )
            }
        }
    }

    private var fullscreen by mutableStateOf(false)
    private var pip by mutableStateOf(false)
    private var leaving = false

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // 读取权限在当前会话内有效；字幕只在这一集使用，不需要长期持久化
        uri?.let { viewModel.addSubtitle(it.toString()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Debug 构建允许用 play_url 直接验证一条流（模拟器截图/回归用）；
        // Release 构建永远走正常的片源详情流程。
        val directUrl = if (com.moontvplus.phone.BuildConfig.DEBUG) {
            intent.getStringExtra("play_url")
        } else {
            null
        }
        if (!directUrl.isNullOrBlank()) {
            viewModel.loadDirect(directUrl, intent.getStringExtra("title").orEmpty())
        } else {
            viewModel.load(
                intent.getStringExtra("source").orEmpty(),
                intent.getStringExtra("video_id").orEmpty(),
                intent.getStringExtra("title").orEmpty(),
                intent.getIntExtra("index", 1),
                intent.getLongExtra("position", 0L),
            )
        }
        if (savedInstanceState?.getBoolean("fullscreen") == true) applyFullscreen(true)

        // 每 5 秒同步一次观看进度（与原实现一致，退出时再等一次落盘）
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                viewModel.saveProgress()
            }
        }

        setContent {
            MoonTvTheme(darkTheme = true) {
                PlayerHost()
            }
        }
    }

    @Composable
    private fun PlayerHost() {
        BackHandler(enabled = !pip) { back() }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerScreen(
                controller = viewModel,
                isFullscreen = fullscreen,
                isPip = pip,
                onBack = ::back,
                onToggleFullscreen = { applyFullscreen(!fullscreen) },
                onEnterPip = ::enterPip,
                onPickSubtitleFile = {
                    pickSubtitle.launch(arrayOf("*/*"))
                },
            )
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { viewModel.enterPictureInPicture() }
        }
    }

    private fun applyFullscreen(enabled: Boolean) {
        fullscreen = enabled
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
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
        viewModel.onForeground(true)
    }

    override fun onStop() {
        if (!pip) {
            viewModel.onForeground(false, wasPlaying = viewModel.playback.value.isPlaying)
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
