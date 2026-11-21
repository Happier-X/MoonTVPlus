package com.moontvplus.ui.model

/** 一个筛选选项，[value] 直接进豆瓣接口参数。 */
data class FilterOption(val label: String, val value: String)

/** 一个筛选维度：类型 / 地区 / 年代 / 平台 / 排序。 */
data class FilterCategory(
    val key: String,
    val label: String,
    val options: List<FilterOption>,
) {
    val defaultValue: String get() = if (key == KEY_SORT) DEFAULT_SORT else "all"

    fun labelOf(value: String): String =
        options.firstOrNull { it.value == value }?.label ?: label
}

const val KEY_TYPE = "type"
const val KEY_LABEL = "label"
const val KEY_REGION = "region"
const val KEY_YEAR = "year"
const val KEY_PLATFORM = "platform"
const val KEY_SORT = "sort"

/** 搜索结果用的三个维度。 */
const val KEY_SOURCE = "source"
const val KEY_TITLE = "title"

/** 豆瓣「综合排序」。 */
const val DEFAULT_SORT = "T"

/** 分类页的四类内容，参数与网页版 `/douban?type=` 一致。 */
enum class BrowseKind(val routeType: String, val label: String, val description: String) {
    MOVIE("movie", "电影", "来自豆瓣的精选内容"),
    TV("tv", "剧集", "来自豆瓣的精选内容"),
    ANIME("anime", "动漫", "来自豆瓣的精选内容"),
    SHOW("show", "综艺", "来自豆瓣的精选内容"),
    ;

    /** 豆瓣接口的 kind。动漫按「番剧」处理，走 tv。 */
    val apiKind: String get() = if (this == MOVIE) "movie" else "tv"

    /** 豆瓣接口的 format：剧集 = 电视剧，综艺 = 综艺，其余留空。 */
    val apiFormat: String
        get() = when (this) {
            TV -> "电视剧"
            SHOW -> "综艺"
            else -> ""
        }

    /** 动漫固定 category=动画（番剧），其他类型用筛选里的「类型」。 */
    val fixedCategory: String get() = if (this == ANIME) "动画" else ""

    companion object {
        fun fromType(type: String): BrowseKind =
            entries.firstOrNull { it.routeType == type } ?: MOVIE
    }
}

/**
 * 网页版 `MultiLevelSelector` 的选项表，逐条照搬，值必须与豆瓣接口一致。
 */
object BrowseFilters {

    fun categories(kind: BrowseKind, currentYear: Int): List<FilterCategory> = buildList {
        if (kind == BrowseKind.ANIME) {
            add(FilterCategory(KEY_LABEL, "类型", labelOptions()))
        } else {
            add(FilterCategory(KEY_TYPE, "类型", typeOptions(kind)))
        }
        add(FilterCategory(KEY_REGION, "地区", regionOptions(kind)))
        add(FilterCategory(KEY_YEAR, "年代", yearOptions(currentYear)))
        if (kind == BrowseKind.TV || kind == BrowseKind.SHOW) {
            add(FilterCategory(KEY_PLATFORM, "平台", platformOptions()))
        }
        add(
            FilterCategory(
                key = KEY_SORT,
                label = "排序",
                options = listOf(
                    FilterOption("综合排序", "T"),
                    FilterOption("近期热度", "U"),
                    FilterOption(
                        if (kind == BrowseKind.TV || kind == BrowseKind.SHOW) "首播时间" else "首映时间",
                        "R",
                    ),
                    FilterOption("高分优先", "S"),
                ),
            )
        )
    }

    /** 初始值：全部 + 综合排序。 */
    fun defaults(kind: BrowseKind, currentYear: Int): Map<String, String> =
        categories(kind, currentYear).associate { it.key to it.defaultValue }

    private fun typeOptions(kind: BrowseKind): List<FilterOption> {
        val all = FilterOption("全部", "all")
        return when (kind) {
            BrowseKind.MOVIE -> listOf(
                all,
                option("喜剧", "comedy"), option("爱情", "romance"), option("动作", "action"),
                option("科幻", "sci-fi"), option("悬疑", "suspense"), option("犯罪", "crime"),
                option("惊悚", "thriller"), option("冒险", "adventure"), option("音乐", "music"),
                option("历史", "history"), option("奇幻", "fantasy"), option("恐怖", "horror"),
                option("战争", "war"), option("传记", "biography"), option("歌舞", "musical"),
                option("武侠", "wuxia"), option("情色", "erotic"), option("灾难", "disaster"),
                option("西部", "western"), option("纪录片", "documentary"), option("短片", "short"),
            )

            BrowseKind.TV -> listOf(
                all,
                option("喜剧", "comedy"), option("爱情", "romance"), option("悬疑", "suspense"),
                option("武侠", "wuxia"), option("古装", "costume"), option("家庭", "family"),
                option("犯罪", "crime"), option("科幻", "sci-fi"), option("恐怖", "horror"),
                option("历史", "history"), option("战争", "war"), option("动作", "action"),
                option("冒险", "adventure"), option("传记", "biography"), option("剧情", "drama"),
                option("奇幻", "fantasy"), option("惊悚", "thriller"), option("灾难", "disaster"),
                option("歌舞", "musical"), option("音乐", "music"),
            )

            BrowseKind.SHOW -> listOf(
                all,
                option("真人秀", "reality"), option("脱口秀", "talkshow"),
                option("音乐", "music"), option("歌舞", "musical"),
            )

            BrowseKind.ANIME -> emptyList()
        }
    }

    /** 动漫的「类型」对应豆瓣的 label 维度。 */
    private fun labelOptions(): List<FilterOption> = listOf(
        FilterOption("全部", "all"),
        option("黑色幽默", "dark_humor"), option("历史", "history"), option("歌舞", "musical"),
        option("励志", "inspirational"), option("恶搞", "parody"), option("治愈", "healing"),
        option("运动", "sports"), option("后宫", "harem"), option("情色", "erotic"),
        option("国漫", "chinese_anime"), option("人性", "human_nature"), option("悬疑", "suspense"),
        option("恋爱", "love"), option("魔幻", "fantasy"), option("科幻", "sci_fi"),
    )

    private fun regionOptions(kind: BrowseKind): List<FilterOption> {
        val all = FilterOption("全部", "all")
        return when (kind) {
            BrowseKind.MOVIE, BrowseKind.ANIME -> listOf(
                all,
                option("华语", "chinese"), option("欧美", "western"), option("韩国", "korean"),
                option("日本", "japanese"), option("中国大陆", "mainland_china"), option("美国", "usa"),
                option("中国香港", "hong_kong"), option("中国台湾", "taiwan"), option("英国", "uk"),
                option("法国", "france"), option("德国", "germany"), option("意大利", "italy"),
                option("西班牙", "spain"), option("印度", "india"), option("泰国", "thailand"),
                option("俄罗斯", "russia"), option("加拿大", "canada"), option("澳大利亚", "australia"),
                option("爱尔兰", "ireland"), option("瑞典", "sweden"), option("巴西", "brazil"),
                option("丹麦", "denmark"),
            )

            BrowseKind.TV, BrowseKind.SHOW -> listOf(
                all,
                option("华语", "chinese"), option("欧美", "western"), option("国外", "foreign"),
                option("韩国", "korean"), option("日本", "japanese"), option("中国大陆", "mainland_china"),
                option("中国香港", "hong_kong"), option("美国", "usa"), option("英国", "uk"),
                option("泰国", "thailand"), option("中国台湾", "taiwan"), option("意大利", "italy"),
                option("法国", "france"), option("德国", "germany"), option("西班牙", "spain"),
                option("俄罗斯", "russia"), option("瑞典", "sweden"), option("巴西", "brazil"),
                option("丹麦", "denmark"), option("印度", "india"), option("加拿大", "canada"),
                option("爱尔兰", "ireland"), option("澳大利亚", "australia"),
            )
        }
    }

    /** 与网页版一样按当前年份动态生成：今年 → 本十年，再往前每十年一档。 */
    private fun yearOptions(currentYear: Int): List<FilterOption> {
        val decade = currentYear / 10 * 10
        val options = mutableListOf(FilterOption("全部", "all"), FilterOption("${decade}年代", "${decade}s"))
        for (year in currentYear downTo decade) {
            options.add(FilterOption(year.toString(), year.toString()))
        }
        var past = decade - 10
        while (past >= 1960) {
            options.add(FilterOption("${past}年代", "${past}s"))
            past -= 10
        }
        options.add(FilterOption("更早", "earlier"))
        return options
    }

    private fun platformOptions(): List<FilterOption> = listOf(
        FilterOption("全部", "all"),
        option("腾讯视频", "tencent"), option("爱奇艺", "iqiyi"), option("优酷", "youku"),
        option("湖南卫视", "hunan_tv"), option("Netflix", "netflix"), option("HBO", "hbo"),
        option("BBC", "bbc"), option("NHK", "nhk"), option("CBS", "cbs"),
        option("NBC", "nbc"), option("tvN", "tvn"),
    )

    private fun option(label: String, value: String) = FilterOption(label, value)
}
