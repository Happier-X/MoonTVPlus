package com.moontvplus.phone.feature.search

import com.moontvplus.phone.core.model.SearchResult

/** 同一部片子在多个源下的聚合结果。 */
data class AggregatedResult(
    val key: String,
    val title: String,
    val poster: String?,
    val year: String,
    val typeLabel: String?,
    val sources: List<SearchResult>,
) {
    val primary: SearchResult get() = sources.first()
    val sourceCount: Int get() = sources.size
    val maxWeight: Double get() = sources.maxOf { it.weight ?: 0.0 }
    val episodeCount: Int get() = sources.maxOf { it.episodes.size }
}

/**
 * 把多个源的结果聚合：
 * 先按「规范化标题 + 类型 + 年份」分组，组内按权重、集数排序；
 * 组之间按权重、源数量排序。纯函数，单测直接打。
 */
object SearchAggregator {

    private val TYPE_RULES = listOf(
        "综艺" to "综艺",
        "动漫" to "动漫",
        "动画" to "动漫",
        "纪录" to "纪录片",
        "短剧" to "短剧",
        "微短剧" to "短剧",
        "电影" to "电影",
        "movie" to "电影",
        "片" to "电影",
        "show" to "综艺",
        "tv" to "剧集",
        "剧" to "剧集",
        "anime" to "动漫",
        "documentary" to "纪录片",
    )

    fun aggregate(results: List<SearchResult>): List<AggregatedResult> {
        if (results.isEmpty()) return emptyList()

        return results
            .filter { it.title.isNotBlank() }
            .groupBy { groupKey(it) }
            .map { (key, items) ->
                val ordered = items.sortedWith(
                    compareByDescending<SearchResult> { it.weight ?: 0.0 }
                        .thenByDescending { it.episodes.size },
                )
                val best = ordered.first()
                AggregatedResult(
                    key = key,
                    title = best.title,
                    poster = ordered.firstNotNullOfOrNull { it.poster.takeIf(String::isNotBlank) },
                    year = best.year,
                    typeLabel = typeLabelOf(best),
                    sources = ordered,
                )
            }
            .sortedWith(
                compareByDescending<AggregatedResult> { it.maxWeight }
                    .thenByDescending { it.sourceCount }
                    .thenByDescending { it.episodeCount },
            )
    }

    fun groupKey(result: SearchResult): String {
        val title = normalizeTitle(result.title)
        val type = typeLabelOf(result).orEmpty()
        val year = result.year.trim()
        return "$title|$type|$year"
    }

    /** 去掉空白、标点与季号后缀，避免「庆余年 第二季」和「庆余年第二季」分成两组。 */
    fun normalizeTitle(title: String): String = title
        .lowercase()
        .replace(" ", "")
        .replace("　", "")
        .filter { it.isLetterOrDigit() }

    fun typeLabelOf(result: SearchResult): String? {
        val raw = listOfNotNull(result.className, result.typeName)
            .joinToString(" ")
            .trim()
            .lowercase()
        if (raw.isEmpty()) return null
        TYPE_RULES.forEach { (needle, label) ->
            if (raw.contains(needle)) return label
        }
        return null
    }
}
