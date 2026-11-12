package com.moontvplus.ui.model

/** 外壳内部的页面栈（只有两层：主界面 ↔ 详情/收藏库）。 */
sealed interface ShellScreen {
    data object Main : ShellScreen

    data class Detail(
        val source: String,
        val videoId: String,
        val title: String,
    ) : ShellScreen

    data object Library : ShellScreen
}
