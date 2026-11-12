package com.moontvplus.ui.model

/** 底部导航的六项，顺序与 Selene 一致。 */
enum class MainTab(val label: String) {
    HOME("首页"),
    MOVIE("电影"),
    TV("剧集"),
    ANIME("动漫"),
    SHOW("综艺"),
    LIVE("直播"),
    ;

    /** 非首页的标签都走豆瓣推荐列表。 */
    val isBrowse: Boolean get() = this != HOME && this != LIVE

    /** 对应 `/api/douban/categories` 的 kind 参数。 */
    val kind: String
        get() = if (this == MOVIE) "movie" else "tv"

    /** 对应 `category` 参数。 */
    val category: String
        get() = when (this) {
            MOVIE -> "热门"
            TV -> "tv"
            ANIME -> "动画"
            SHOW -> "show"
            else -> "热门"
        }

    /** 对应 `type` 参数。 */
    val type: String
        get() = when (this) {
            MOVIE -> "全部"
            TV -> "tv"
            ANIME -> "动画"
            SHOW -> "show"
            else -> "全部"
        }
}

/** 点击海报卡片后要去哪儿。 */
sealed interface OpenTarget {
    /** 豆瓣条目只有标题和豆瓣 id，宿主要用它去搜索里找源。 */
    data class Douban(val title: String, val doubanId: String = "") : OpenTarget

    /** 采集源里的具体影片，可以直接进详情。 */
    data class Video(val source: String, val id: String, val title: String) : OpenTarget

    /** 播放记录：直接续播。 */
    data class Resume(val source: String, val id: String, val title: String, val index: Int) : OpenTarget
}

/** 卡片渲染需要的所有信息，UI 层不再直接依赖网络模型。 */
data class PosterItem(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    /** 左上角年份角标（深蓝底白字，Selene 的 `#2C3E50 @0.8`） */
    val year: String? = null,
    /** 右上角评分（粉色圆点，Selene 的 `#E91E63`） */
    val rate: String? = null,
    /** 右上角集数/更新角标（绿色底白字，Selene 的 `#27AE60`） */
    val badge: String? = null,
    /** 0f..1f，非空时在封面底部画一条绿色进度条（继续观看用）。 */
    val progress: Float? = null,
    val target: OpenTarget? = null,
)

/** 首页的一个横向区块。 */
data class HomeSection(
    val id: String,
    val title: String,
    val items: List<PosterItem>,
    val error: String? = null,
)

/** 宿主收到播放请求：Android 交给原生播放器，桌面端用浏览器打开站点播放页。 */
data class PlayRequest(
    val source: String,
    val videoId: String,
    val title: String,
    /** 0 起的集数下标 */
    val index: Int,
)
