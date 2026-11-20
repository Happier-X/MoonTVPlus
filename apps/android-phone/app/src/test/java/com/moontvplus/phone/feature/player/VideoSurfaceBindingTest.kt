package com.moontvplus.phone.feature.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.playback.PlaybackController
import com.moontvplus.phone.playback.PlaybackEngine
import com.moontvplus.phone.playback.PlaybackRequest
import com.moontvplus.phone.playback.PlaybackState
import com.moontvplus.phone.player.PlatformVideoHost
import com.moontvplus.phone.player.PlayerSession
import com.moontvplus.phone.player.PlayerUiController
import com.moontvplus.ui.player.VideoSurface
import com.moontvplus.ui.player.platformPlaybackOf
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.9.6 黑屏回归。
 *
 * Android 宿主交给界面的是包了一层的 `PlayerViewModel`，而 `VideoSurface` 曾经
 * 写死 `controller as? PlayerSession`：转换失败后画面层只画一块黑底，
 * 音频和进度都正常，屏幕全黑。这里锁定两条：
 * 1. 画面层只能通过 [PlatformVideoHost] 拿平台播放器，包装过的控制器也要能取到；
 * 2. 真取不到播放器时必须显式提示，不能静默黑屏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoSurfaceBindingTest {

    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = StandardTestDispatcher()
    private val player = FakePlayer()

    @Before
    fun mainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    /** 模拟宿主层：界面拿到的是包装对象，不是 PlayerSession 本身。 */
    private class HostWrapper(
        inner: PlayerUiController,
        override val platformPlaybackController: PlaybackController?,
    ) : PlayerUiController by inner, PlatformVideoHost

    private fun session(): PlayerSession {
        val api = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(MoonApi::class.java),
        ) { _, method, _ -> error("测试不发起网络请求：${method.name}") } as MoonApi
        return PlayerSession(api, ServerProvider(), object : PlaybackEngine {
            override val supportsInlinePlayback = true
            override fun create(): PlaybackController = player
        })
    }

    @Test
    fun wrappedHostStillExposesThePlatformPlayer() {
        val host = HostWrapper(session(), player)
        assertSame(
            "包装过的控制器必须还能取到平台播放器；取不到就是 1.9.6 的黑屏",
            player,
            platformPlaybackOf(host),
        )
    }

    @Test
    fun missingPlatformPlayerShowsAHintInsteadOfASilentBlackScreen() {
        compose.setContent {
            VideoSurface(HostWrapper(session(), platformPlaybackController = null), Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.onNodeWithText("视频画面无法初始化（未拿到播放器）").assertIsDisplayed()
    }

    private class FakePlayer : PlaybackController {
        override val state = MutableStateFlow(PlaybackState())
        override fun load(request: PlaybackRequest) = Unit
        override fun stop() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun currentPositionMs() = 0L
    }
}
