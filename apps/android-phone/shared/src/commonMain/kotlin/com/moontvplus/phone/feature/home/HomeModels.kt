package com.moontvplus.phone.feature.home

/** 首页卡片的点击目标。 */
sealed interface HomeTarget {
    /** 豆瓣 / Bangumi / TMDB 条目：带标题去选源页搜可播放源。 */
    data class SourceSearch(
        val title: String,
        val year: String? = null,
        val doubanId: Long = 0L,
    ) : HomeTarget

    /** 短剧推荐：结果里已经带 episodes，直接进详情。 */
    data class Detail(val source: String, val videoId: String) : HomeTarget

    /** 继续观看：进详情并定位到记录里的集数。 */
    data class Resume(val source: String, val videoId: String, val index: Int) : HomeTarget
}

/** 一张海报卡片。 */
data class HomeCard(
    val key: String,
    val title: String,
    val poster: String?,
    val badge: String? = null,
    val subtitle: String? = null,
    val target: HomeTarget,
)

/** 一个横向区块。 */
data class HomeRow(
    val id: String,
    val title: String,
    val cards: List<HomeCard> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = cards.isEmpty()
    /** 失败或空都不展示，单个区块出问题不影响整页 */
    val visible: Boolean get() = loading || (error == null && cards.isNotEmpty())
}

object HomeRowIds {
    const val CONTINUE = "continue"
    const val MOVIES = "hotMovies"
    const val DUANJU = "hotDuanju"
    const val BANGUMI = "bangumiCalendar"
    const val TV = "hotTvShows"
    const val VARIETY = "hotVarietyShows"
    const val UPCOMING = "upcoming"

    val ORDER = listOf(CONTINUE, MOVIES, DUANJU, BANGUMI, TV, VARIETY, UPCOMING)
}
