package com.moontvplus.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moontvplus.phone.player.PlayerUiController

/**
 * 视频画面。
 *
 * 播放器画面必须由平台 surface 承载：Android 用 Media3 的 `PlayerView`，
 * 桌面端还没有内嵌播放器，就明确显示「用外部播放器打开」，不放一块黑屏假装能播。
 * 控制栏、手势、弹幕、字幕、面板全部在 commonMain 共享。
 */
@Composable
expect fun VideoSurface(controller: PlayerUiController, modifier: Modifier = Modifier)
