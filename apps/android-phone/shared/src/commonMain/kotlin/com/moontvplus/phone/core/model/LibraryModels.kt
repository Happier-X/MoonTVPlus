package com.moontvplus.phone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 收藏条目，字段与 `src/lib/types.ts` 里的 `Favorite` 对齐。
 * `GET /api/favorites` 返回的是 `Record<"<source>+<id>", Favorite>`。
 */
@Serializable
data class Favorite(
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
    val title: String = "",
    @Serializable(with = FlexStringSerializer::class) val year: String = "",
    val cover: String = "",
    @SerialName("save_time") val saveTime: Long = 0L,
    @SerialName("search_title") val searchTitle: String = "",
    val origin: String? = null,
    @SerialName("is_completed") val isCompleted: Boolean? = null,
    @SerialName("vod_remarks") val vodRemarks: String? = null,
)

/** 收藏 + 它的 map key（`"<source>+<id>"`）。 */
data class FavoriteItem(val key: String, val favorite: Favorite) {
    val source: String get() = key.substringBefore('+')
    val videoId: String get() = key.substringAfter('+', "")
}

@Serializable
data class SaveFavoriteRequest(val key: String, val favorite: Favorite)
