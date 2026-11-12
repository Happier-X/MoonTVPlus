package com.moontvplus.phone.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面端播放器的行为契约。
 *
 * 现在还没有内嵌播放器，实现必须**如实**报告不支持，界面据此走「用外部播放器打开」，
 * 而不是给用户一块永远黑屏的窗口。以后接上 VLC 时这个测试会提醒改契约。
 */
class DesktopPlaybackEngineTest {

    @Test
    fun `桌面端当前不支持内嵌播放`() {
        val engine = createPlaybackEngine()
        assertFalse(engine.supportsInlinePlayback)
    }

    @Test
    fun `载入时给出明确原因而不是静默黑屏`() {
        val controller = createPlaybackEngine().create()
        controller.load(PlaybackRequest("https://cdn.example.com/a.m3u8", isHls = true))

        val state = controller.state.value
        assertEquals(PlaybackStatus.ERROR, state.status)
        assertTrue(state.error.orEmpty().isNotBlank())
    }

    @Test
    fun `释放与定位调用不会抛异常`() {
        val controller = createPlaybackEngine().create()
        controller.play()
        controller.pause()
        controller.seekTo(1_000)
        assertEquals(0L, controller.currentPositionMs())
        controller.release()
    }
}
