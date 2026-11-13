package com.moontvplus.phone.desktop

import com.moontvplus.ui.app.MoonTvController
import com.moontvplus.ui.model.ShellScreen
import com.moontvplus.ui.screen.LibraryTab
import kotlinx.coroutines.delay

/**
 * 无鼠标验证用的只读钩子：按 `MOONTV_DEMO_STEPS` 依次驱动共享外壳，
 * 每步打印当前页面，方便配合截图确认返回行为。不写任何数据，正式环境不设置该变量即不生效。
 *
 * 语法：`open:<片名>;library;history;openFirstFav;section:<区块id>;back;wait:<毫秒>`
 */
internal suspend fun runDemoSteps(controller: MoonTvController) {
    val script = System.getenv("MOONTV_DEMO_STEPS").orEmpty().trim()
    if (script.isEmpty()) return
    delay(2500)

    for (raw in script.split(';')) {
        val step = raw.trim()
        if (step.isEmpty()) continue
        val name = step.substringBefore(':')
        val arg = step.substringAfter(':', "")
        when (name) {
            "open" -> controller.openByTitle(arg)
            "library" -> controller.openLibrary(LibraryTab.FAVORITES)
            "history" -> controller.openLibrary(LibraryTab.HISTORY)
            "openFirstFav" -> controller.library.favorites.firstOrNull()?.target?.let(controller::open)
            "section" -> controller.openSectionTab(sectionToTab(arg))
            "back" -> controller.back()
            "wait" -> Unit
            else -> Unit
        }
        println("[demo] step=$step screen=${controller.screen.describe()} tab=${controller.tab}")
        delay(arg.takeIf { name == "wait" }?.toLongOrNull() ?: 3000L)
    }
    println("[demo] done screen=${controller.screen.describe()}")
}

private fun ShellScreen.describe(): String = when (this) {
    ShellScreen.Main -> "Main"
    ShellScreen.Library -> "Library"
    is ShellScreen.Detail -> "Detail(${title})"
}

private fun sectionToTab(id: String) = when (id) {
    "hotTv" -> com.moontvplus.ui.model.MainTab.TV
    "bangumi" -> com.moontvplus.ui.model.MainTab.ANIME
    "hotVariety" -> com.moontvplus.ui.model.MainTab.SHOW
    else -> com.moontvplus.ui.model.MainTab.MOVIE
}
