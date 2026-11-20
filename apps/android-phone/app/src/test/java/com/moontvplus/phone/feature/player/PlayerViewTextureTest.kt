package com.moontvplus.phone.feature.player

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import com.moontvplus.ui.player.createPlayerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.9.6 回归：播放器有声音没画面。
 *
 * 根因是画面层用了 PlayerView 默认的 SurfaceView，嵌在 Compose 的 AndroidView 里
 * 没有参与合成（screencap 全黑、SurfaceFlinger 里只有 Activity 窗口层）。
 * 这个测试守住「画面必须由 TextureView 承载」，避免再次退回黑屏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerViewTextureTest {

    private fun newPlayerView(): PlayerView =
        createPlayerView(RuntimeEnvironment.getApplication(), null)

    @Test
    fun videoSurfaceIsTextureView() {
        val playerView = newPlayerView()
        val surface = playerView.videoSurfaceView
        assertNotNull("PlayerView 没有创建画面层，surface_type 可能被改成了 none", surface)
        assertTrue(
            "画面层必须是 TextureView（当前是 ${surface!!.javaClass.name}）" +
                "：SurfaceView 在 Compose AndroidView 里会黑屏",
            surface is TextureView,
        )
    }

    @Test
    fun viewHierarchyHasNoSurfaceView() {
        val root: View = newPlayerView()
        val surfaceViews = ArrayList<View>()
        fun walk(view: View) {
            if (view is SurfaceView) surfaceViews.add(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        assertEquals(
            "画面层不允许出现 SurfaceView：${surfaceViews.map { it.javaClass.name }}",
            0,
            surfaceViews.size,
        )
    }

    @Test
    fun media3ControllerStaysOffBecauseComposeOwnsTheControls() {
        val playerView = newPlayerView()
        assertTrue("控制栏是共享 Compose 实现，PlayerView 自带的控制栏必须关闭", !playerView.useController)
        // 字幕仍由 Media3 的 SubtitleView 画，别为了换画面把内嵌字幕弄丢
        assertNotNull("PlayerView 的 SubtitleView 不能丢", playerView.subtitleView)
    }
}
