package com.moontvplus.phone.feature.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import com.moontvplus.phone.core.ui.theme.MoonTvTheme
import com.moontvplus.phone.playback.PlayerCapabilities
import com.moontvplus.phone.player.PlayerPanel
import com.moontvplus.ui.player.PlayerCenterPlayButton
import com.moontvplus.ui.player.PlayerControlTags
import com.moontvplus.ui.player.PlayerControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.9.7 回归：控制栏「看得见点不动」。
 *
 * 根因是控制栏父级用 `awaitFirstDown(requireUnconsumed = false).consume()` 把整块指针
 * 都吃掉了，子按钮的 clickable 拿到被消费的 move 事件后判定手势取消，点击永远不触发。
 * 这些测试直接渲染真实的 [PlayerControls]，任何把「父级全量消费指针」加回来的改动都会让它失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerControlsClickTest {

    @get:Rule
    val compose = createComposeRule()

    private val fullCapabilities = PlayerCapabilities(
        inlinePlayback = true,
        pictureInPicture = true,
        speedControl = true,
        trackSelection = true,
        subtitles = true,
        externalSubtitles = true,
        danmaku = true,
        casting = true,
        fullscreenToggle = true,
        screenLock = true,
        seekBar = true,
    )

    private val clicks = mutableListOf<String>()

    @Composable
    private fun Controls(
        playing: Boolean = true,
        speed: Float = 1f,
        danmakuEnabled: Boolean = true,
        hasNext: Boolean = true,
        capabilities: PlayerCapabilities = fullCapabilities,
        withCenterPlay: Boolean = false,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerControls(
                displayTitle = "测试剧集",
                index = 2,
                episodeTitle = "第 2 集",
                sourceLabel = "测试源",
                isPlaying = playing,
                positionMs = 65_000L,
                durationMs = 1_440_000L,
                sliderPosition = null,
                speed = speed,
                danmakuEnabled = danmakuEnabled,
                hasEpisodes = true,
                hasNext = hasNext,
                capabilities = capabilities,
                isFullscreen = false,
                onBack = { clicks += "back" },
                onToggleFullscreen = { clicks += "fullscreen" },
                onEnterPip = { clicks += "pip" },
                onToggleLock = { clicks += "lock" },
                onTogglePlay = { clicks += "play" },
                onSliderPosition = { clicks += "seek" },
                onSliderFinished = { clicks += "seek-finished" },
                onCycleSpeed = { clicks += "speed" },
                onToggleDanmaku = { clicks += "danmaku" },
                onPlayNext = { clicks += "next" },
                onOpenPanel = { clicks += "panel:${it.name}" },
            )
            if (withCenterPlay) {
                PlayerCenterPlayButton(
                    onClick = { clicks += "center-play" },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    /** 宽屏一次放下所有入口，逐个点一遍，确认没有一个按钮被父级吞掉。 */
    @Test
    @Config(qualifiers = "w800dp-h600dp-xxhdpi")
    fun everyControlButtonIsClickable() {
        compose.setContent { MoonTvTheme { Controls(playing = true) } }
        compose.waitForIdle()

        val expected = listOf(
            PlayerControlTags.BACK to "back",
            PlayerControlTags.PIP to "pip",
            PlayerControlTags.LOCK to "lock",
            PlayerControlTags.PLAY_PAUSE to "play",
            PlayerControlTags.SPEED to "speed",
            PlayerControlTags.EPISODES to "panel:EPISODES",
            PlayerControlTags.SOURCES to "panel:SOURCES",
            PlayerControlTags.TRACKS to "panel:TRACKS",
            PlayerControlTags.SUBTITLES to "panel:SUBTITLES",
            PlayerControlTags.DANMAKU to "danmaku",
            PlayerControlTags.CAST to "panel:CAST",
            PlayerControlTags.NEXT to "next",
            PlayerControlTags.SETTINGS to "panel:SETTINGS",
            PlayerControlTags.FULLSCREEN to "fullscreen",
        )
        expected.forEach { (tag, _) ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        expected.forEach { (tag, _) ->
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
        }
        assertEquals(
            "控制栏按钮必须逐个可点，缺失/错序都说明点击被上层吞掉了",
            expected.map { it.second },
            clicks,
        )
    }

    /** 暂停时点击中央大按钮恢复播放。 */
    @Test
    fun centerPlayButtonStartsPlayback() {
        compose.setContent { MoonTvTheme { Controls(playing = false, withCenterPlay = true) } }
        compose.waitForIdle()
        compose.onNodeWithTag(PlayerControlTags.CENTER_PLAY).assertIsDisplayed()
        compose.onNodeWithTag(PlayerControlTags.CENTER_PLAY).performClick()
        compose.waitForIdle()
        assertEquals(listOf("center-play"), clicks)
    }

    /** 进度条不能被旁边的按钮/父级抢走：横向拖动要落到 onSliderPosition。 */
    @Test
    @Config(qualifiers = "w800dp-h600dp-xxhdpi")
    fun progressBarAcceptsDrag() {
        compose.setContent { MoonTvTheme { Controls(playing = true) } }
        compose.waitForIdle()
        // 进度条是底栏最上面的元素（高度 22dp），在它的中线上横向拖动
        compose.onNodeWithTag(PlayerControlTags.BOTTOM_BAR).performTouchInput {
            swipe(
                start = androidx.compose.ui.geometry.Offset(width * 0.2f, 11f),
                end = androidx.compose.ui.geometry.Offset(width * 0.8f, 11f),
            )
        }
        compose.waitForIdle()
        assertTrue("拖动进度条没有触发 onSliderPosition，进度条可能被上层吃掉事件: $clicks", clicks.contains("seek"))
    }

    /** 320x640（最窄的常见手机）：暂停态要有中央大播放按钮 + 单行底部控制条。 */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun pausedLayoutMatchesWebsiteAt320() {
        compose.setContent { MoonTvTheme(true) { Controls(playing = false, withCenterPlay = true) } }
        compose.waitForIdle()
        compose.onNodeWithTag(PlayerControlTags.CENTER_PLAY).assertIsDisplayed()
        compose.onNodeWithTag(PlayerControlTags.BOTTOM_BAR).assertIsDisplayed()
        compose.onNodeWithTag(PlayerControlTags.PLAY_PAUSE).assertIsDisplayed()
        compose.onNodeWithTag(PlayerControlTags.SETTINGS).assertIsDisplayed()
        compose.onNodeWithTag(PlayerControlTags.TOP_BAR).assertIsDisplayed()
        screenshot("paused-320x640")
    }

    /** 播放态（无中央按钮）也存一张，方便对比控制栏布局没有回归。 */
    @Test
    @Config(qualifiers = "w393dp-h851dp-xxhdpi")
    fun playingLayoutScreenshot() {
        compose.setContent { MoonTvTheme(true) { Controls(playing = true) } }
        compose.waitForIdle()
        compose.onNodeWithTag(PlayerControlTags.PLAY_PAUSE).assertIsDisplayed()
        screenshot("playing-393x851")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/reports/player-ui").apply { mkdirs() }
        val root = compose.onNodeWithTag(PlayerControlTags.BOTTOM_BAR).fetchSemanticsNode()
            .root as androidx.compose.ui.platform.ViewRootForTest
        val view = root.view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
