package com.moontvplus.phone.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * 安卓侧的旧主题入口，现在直接复用共享的网页版配色（`com.moontvplus.ui.theme`），
 * 播放器、更新弹窗和测试都跟着同一套色板走。
 */
@Composable
fun MoonTvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    com.moontvplus.ui.theme.MoonTvTheme(darkTheme = darkTheme, content = content)
}
