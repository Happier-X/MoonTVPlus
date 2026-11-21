package com.moontvplus.phone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 动漫磁力搜索的单条资源（`/api/acg/{acgrip|mikan|dmhy|nyaa}`）。
 *
 * `guid` 在部分源里是对象，这里保留原始 JSON，取键时自行兼容。
 */
@Serializable
data class AcgItem(
    val title: String = "",
    val link: String = "",
    val guid: JsonElement? = null,
    val pubDate: String = "",
    val torrentUrl: String = "",
    val description: String = "",
    val images: List<String> = emptyList(),
) {
    /** 磁链优先，退回到详情页链接。 */
    val downloadUrl: String get() = torrentUrl.ifBlank { link }

    val key: String
        get() = downloadUrl.ifBlank { title }
}

@Serializable
data class AcgSearchResponse(
    val keyword: String = "",
    val page: Int = 1,
    val total: Int = 0,
    val items: List<AcgItem> = emptyList(),
    val error: String? = null,
)

@Serializable
data class AcgSearchRequest(
    val keyword: String,
    val page: Int = 1,
)

/** 四个磁力源，与网页版 AcgSearch 的胶囊顺序一致。 */
enum class AcgSource(val value: String, val label: String) {
    ACGRIP("acgrip", "ACG.RIP"),
    MIKAN("mikan", "蜜柑"),
    DMHY("dmhy", "动漫花园"),
    NYAA("nyaa", "Nyaa"),
    ;

    /** 只有 ACG.RIP 支持翻页，其余是 RSS 单页。 */
    val supportsPaging: Boolean get() = this == ACGRIP

    companion object {
        fun fromValue(value: String?): AcgSource =
            entries.firstOrNull { it.value == value } ?: ACGRIP
    }
}
