package com.moontvplus.phone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 采集源返回的单条搜索结果（也用于短剧推荐）。字段名对齐 `src/lib/types.ts`。 */
@Serializable
data class SearchResult(
    @Serializable(with = FlexStringSerializer::class) val id: String = "",
    val title: String = "",
    val poster: String = "",
    @Serializable(with = FlexStringListSerializer::class)
    val episodes: List<String> = emptyList(),
    @SerialName("episodes_titles")
    @Serializable(with = FlexStringListSerializer::class)
    val episodesTitles: List<String> = emptyList(),
    val source: String = "",
    @SerialName("source_name") val sourceName: String = "",
    val weight: Double? = null,
    @SerialName("class") val className: String? = null,
    @Serializable(with = FlexStringSerializer::class) val year: String = "",
    val desc: String? = null,
    @SerialName("type_name") val typeName: String? = null,
    @SerialName("vod_remarks") val vodRemarks: String? = null,
    @SerialName("douban_id")
    @Serializable(with = FlexLongSerializer::class)
    val doubanId: Long = 0L,
    @SerialName("vod_total") val vodTotal: Int? = null,
    /** 由服务端代理 m3u8 与分片 */
    val proxyMode: Boolean = false,
) {
    /** 列表里的唯一键：同一部剧在不同源下 id 会重复。 */
    val key: String get() = "$source+$id"

    val episodesCount: Int get() = episodes.size
}

/** 豆瓣条目（来自 `/api/douban/categories`）。 */
@Serializable
data class DoubanItem(
    @Serializable(with = FlexStringSerializer::class) val id: String = "",
    val title: String = "",
    val poster: String = "",
    @Serializable(with = FlexStringSerializer::class) val rate: String = "",
    @Serializable(with = FlexStringSerializer::class) val year: String = "",
)

@Serializable
data class DoubanCategoriesResponse(
    val code: Int = 0,
    val message: String? = null,
    val list: List<DoubanItem> = emptyList(),
)

/** Bangumi 每日放送。 */
@Serializable
data class BangumiImages(
    val large: String? = null,
    val common: String? = null,
    val medium: String? = null,
    val small: String? = null,
    val grid: String? = null,
) {
    val best: String?
        get() = listOfNotNull(large, common, medium, small, grid)
            .firstOrNull { it.isNotBlank() }
}

@Serializable
data class BangumiRating(val score: Double? = null)

@Serializable
data class BangumiSubject(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("name_cn") val nameCn: String? = null,
    val rating: BangumiRating? = null,
    @SerialName("air_date") val airDate: String? = null,
    val images: BangumiImages? = null,
) {
    val displayTitle: String
        get() = nameCn?.takeIf { it.isNotBlank() } ?: name.orEmpty()

    val year: String get() = airDate?.substringBefore('-').orEmpty()
}

@Serializable
data class BangumiWeekday(val en: String? = null, val cn: String? = null)

@Serializable
data class BangumiCalendarDay(
    val weekday: BangumiWeekday? = null,
    val items: List<BangumiSubject> = emptyList(),
)

/** TMDB 条目（即将上映）。 */
@Serializable
data class TmdbItem(
    val id: Int = 0,
    val title: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("media_type") val mediaType: String = "movie",
) {
    val posterUrl: String
        get() = posterPath?.takeIf { it.isNotBlank() }?.let { "$TMDB_IMAGE_BASE$it" }.orEmpty()

    val year: String get() = releaseDate?.substringBefore('-').orEmpty()

    private companion object {
        const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    }
}

/** 带 `code/data` 外壳的通用响应（短剧推荐、TMDB 即将上映）。 */
@Serializable
data class CodedListResponse<T>(
    val code: Int = 0,
    val message: String? = null,
    val data: List<T> = emptyList(),
)

/** 播放记录，key 是 `"<source>+<id>"`，见 `GET /api/playrecords`。 */
@Serializable
data class PlayRecord(
    val title: String = "",
    @SerialName("source_name") val sourceName: String = "",
    @Serializable(with = FlexStringSerializer::class) val year: String = "",
    val cover: String = "",
    val index: Int = 1,
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
    @SerialName("play_time") val playTime: Double = 0.0,
    @SerialName("total_time") val totalTime: Double = 0.0,
    @SerialName("save_time") val saveTime: Long = 0L,
    @SerialName("search_title") val searchTitle: String? = null,
    @SerialName("is_anime") val isAnime: Boolean = false,
    @SerialName("is_duanju") val isDuanju: Boolean = false,
) {
    val progress: Float
        get() = if (totalTime <= 0.0) 0f else (playTime / totalTime).toFloat().coerceIn(0f, 1f)

    val isFinished: Boolean get() = totalEpisodes > 0 && index >= totalEpisodes
}

/** 上报播放记录用的请求体，字段名必须与服务端一致。 */
@Serializable
data class PlayRecordBody(
    val title: String,
    @SerialName("source_name") val sourceName: String,
    val year: String = "",
    val cover: String = "",
    val index: Int = 1,
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
    @SerialName("play_time") val playTime: Double = 0.0,
    @SerialName("total_time") val totalTime: Double = 0.0,
    @SerialName("save_time") val saveTime: Long = 0L,
)

@Serializable
data class SavePlayRecordRequest(val key: String, val record: PlayRecordBody)

/** 播放记录 + 它的 map key（`"<source>+<id>"`）。 */
data class ContinueItem(val key: String, val record: PlayRecord) {
    val source: String get() = key.substringBefore('+')
    val videoId: String get() = key.substringAfter('+', "")
}
