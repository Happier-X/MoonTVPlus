package com.moontvplus.ui.player

import androidx.compose.runtime.Composable

/**
 * 播放页内部的返回处理。
 *
 * Android 用 Activity 的 BackHandler：先关面板/解锁，再交给宿主的「退出播放页」；
 * 桌面端没有返回键，是空实现。
 */
@Composable
expect fun PlayerBackHandler(enabled: Boolean, onBack: () -> Unit)
