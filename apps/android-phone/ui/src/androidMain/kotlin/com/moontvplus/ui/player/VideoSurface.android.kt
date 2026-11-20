package com.moontvplus.ui.player

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.moontvplus.phone.core.storage.SubtitleAppearance
import com.moontvplus.phone.player.PlatformVideoHost
import com.moontvplus.phone.player.PlayerUiController
import com.moontvplus.phone.playback.ExoPlaybackController
import com.moontvplus.phone.playback.PlaybackController
import com.moontvplus.ui.R

private const val TAG = "MoonTVPlusPlayer"

@Composable
actual fun VideoSurface(controller: PlayerUiController, modifier: Modifier) {
    val preferences by controller.preferences.collectAsState()
    val exo = platformPlaybackOf(controller) as? ExoPlaybackController
    if (exo == null) {
        // 绝对不允许静默黑屏：1.9.6 就是拿不到播放器却返回一块黑底，
        // 结果「有声音、没画面」谁都没看出问题在哪。
        Log.e(TAG, "VideoSurface 拿不到 ExoPlaybackController（controller=${controller.javaClass.name}），画面无法绑定")
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("视频画面无法初始化（未拿到播放器）", color = ComposeColor.White)
        }
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { context -> createPlayerView(context, exo.exoPlayer) },
        update = { view -> applySubtitleStyle(view.subtitleView, preferences.subtitle) },
        onRelease = { view -> view.player = null },
    )
}

/**
 * 从控制器上取平台播放器。
 *
 * 宿主可能给的是 [com.moontvplus.phone.player.PlayerSession]，也可能是包了一层的
 * ViewModel，所以只能走 [PlatformVideoHost]，不要写死具体实现类型 —— 1.9.6 就是
 * 写死了 `as? PlayerSession`，拿到 ViewModel 后静默黑屏。
 *
 * 公开出来是为了让测试能直接断言这条链路，别改成 internal。
 */
fun platformPlaybackOf(controller: PlayerUiController): PlaybackController? =
    (controller as? PlatformVideoHost)?.platformPlaybackController

/**
 * 创建承载画面的 [PlayerView]。
 *
 * 只能用这里的工厂，不要在别处直接 `PlayerView(context)`：默认 surface_type 是
 * SurfaceView，嵌在 Compose 的 AndroidView 里容易出现「有声音没画面」。
 * 布局 `res/layout/moontvplus_player_view.xml` 显式声明了 `surface_type=texture_view`，
 * 由 `PlayerViewTextureTest` 守着。
 */
fun createPlayerView(context: Context, player: Player?): PlayerView {
    val view = LayoutInflater.from(context)
        .inflate(R.layout.moontvplus_player_view, null) as PlayerView
    view.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    view.apply {
        this.player = player
        useController = false // 控制栏是共享 Compose 实现，不用 Media3 自带的
        setBackgroundColor(Color.BLACK)
    }
    return view
}

/** 内嵌字幕轨沿用 Media3 的 SubtitleView；外挂字幕由 Compose 层渲染，不在这里画。 */
private fun applySubtitleStyle(view: androidx.media3.ui.SubtitleView?, style: SubtitleAppearance) {
    if (view == null) return
    view.setStyle(
        CaptionStyleCompat(
            style.textColorArgb.toInt(),
            style.backgroundColorArgb.toInt(),
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        ),
    )
    view.setFractionalTextSize(0.055f * style.fontScale)
    view.setBottomPaddingFraction(style.bottomPaddingPercent.coerceIn(0f, 0.4f))
}
