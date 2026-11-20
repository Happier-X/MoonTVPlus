package com.moontvplus.ui.player

import androidx.compose.runtime.Composable

@Composable
actual fun PlayerBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // 桌面端用 Esc 关窗口（窗口层的 onPreviewKeyEvent），没有系统返回键
}
