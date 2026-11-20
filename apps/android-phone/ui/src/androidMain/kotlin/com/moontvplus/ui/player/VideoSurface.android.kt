package com.moontvplus.ui.player

import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.moontvplus.phone.core.storage.SubtitleAppearance
import com.moontvplus.phone.player.PlayerSession
import com.moontvplus.phone.player.PlayerUiController
import com.moontvplus.phone.playback.ExoPlaybackController

@Composable
actual fun VideoSurface(controller: PlayerUiController, modifier: Modifier) {
    val exo = (controller as? PlayerSession)?.controller as? ExoPlaybackController
    val preferences by controller.preferences.collectAsState()
    if (exo == null) {
        // 理论上不会发生：Android 的 engine 一定会创建 ExoPlaybackController
        androidx.compose.foundation.layout.Box(modifier)
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                player = exo.exoPlayer
                useController = false // 控制栏是共享 Compose 实现，不用 Media3 自带的
                setBackgroundColor(Color.BLACK)
            }
        },
        update = { view -> applySubtitleStyle(view.subtitleView, preferences.subtitle) },
        onRelease = { view -> view.player = null },
    )
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
