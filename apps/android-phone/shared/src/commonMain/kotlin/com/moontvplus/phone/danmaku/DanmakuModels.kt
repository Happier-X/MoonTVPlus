package com.moontvplus.phone.danmaku

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 弹幕展示模式，数值与弹弹play / B 站协议一致。 */
enum class DanmakuMode(val protocolValue: Int) {
    SCROLL(1),
    BOTTOM(4),
    TOP(5),
    REVERSE(6),
    /** 高级/定位弹幕，当前按滚动弹幕降级显示 */
    ADVANCED(7),
    SCRIPT(8);

    companion object {
        fun of(value: Int): DanmakuMode = entries.firstOrNull { it.protocolValue == value } ?: SCROLL
    }
}

/** 一条弹幕。[timeMs] 是相对正片开头的毫秒。 */
data class DanmakuComment(
    val id: String,
    val timeMs: Long,
    val text: String,
    val mode: DanmakuMode = DanmakuMode.SCROLL,
    val colorArgb: Long = 0xFFFFFFFFL,
    val sizePercent: Int = 100,
)

/** 匹配到的剧集信息（来自弹弹play 的 search/episodes）。 */
data class DanmakuEpisode(
    val animeId: Long = 0L,
    val animeTitle: String = "",
    val episodeId: Long = 0L,
    val episodeTitle: String = "",
)

/** 弹幕加载状态。未配置凭据时 [configured] 为 false，界面显示「弹幕未配置」而不是假装加载。 */
data class DanmakuState(
    val enabled: Boolean = true,
    val configured: Boolean = false,
    val loading: Boolean = false,
    val comments: List<DanmakuComment> = emptyList(),
    val matched: DanmakuEpisode? = null,
    val message: String? = null,
    val loadedForEpisode: Int? = null,
) {
    val count: Int get() = comments.size
    val active: Boolean get() = enabled && comments.isNotEmpty()
}

/** 弹弹play 搜索接口的响应。字段名与官方文档一致，未知字段忽略。 */
@Serializable
data class DandanplaySearchResponse(
    val success: Boolean = false,
    val errorCode: Int = 0,
    val errorMessage: String? = null,
    val animes: List<DandanplayAnime> = emptyList(),
)

@Serializable
data class DandanplayAnime(
    val animeId: Long = 0L,
    val animeTitle: String = "",
    val type: String = "",
    val typeDescription: String? = null,
    val episodes: List<DandanplayEpisode> = emptyList(),
)

@Serializable
data class DandanplayEpisode(
    val episodeId: Long = 0L,
    val episodeTitle: String = "",
)

@Serializable
data class DandanplayCommentsResponse(
    val count: Int = 0,
    val comments: List<DandanplayComment> = emptyList(),
)

@Serializable
data class DandanplayComment(
    @SerialName("cid") val cid: Long = 0L,
    /** "时间,模式,颜色,用户ID" */
    val p: String = "",
    val m: String = "",
)

/**
 * 弹幕解析。
 *
 * 两个入口：
 * - [parseDandanplay]：弹弹play 的 `p` 字段协议；
 * - [parseXml]：B 站兼容的 `<d p="...">text</d>`（本地导入/缓存兜底）。
 *
 * 解析失败的单条弹幕直接丢弃：一条坏数据不应该让整集没有弹幕。
 */
object DanmakuParser {

    fun parseDandanplay(comments: List<DandanplayComment>): List<DanmakuComment> =
        comments.mapNotNull { comment -> parseItem(comment.cid.toString(), comment.p, comment.m) }
            .sortedBy { it.timeMs }

    fun parseItem(id: String, p: String, text: String): DanmakuComment? {
        if (text.isBlank()) return null
        val parts = p.split(',')
        if (parts.size < 3) return null
        val seconds = parts[0].toFloatOrNull() ?: return null
        val mode = DanmakuMode.of(parts[1].toIntOrNull() ?: 1)
        val color = parts[2].toIntOrNull() ?: 0xFFFFFF
        return DanmakuComment(
            id = id,
            timeMs = (seconds * 1000f).toLong().coerceAtLeast(0L),
            text = text.replace("\r", "").replace("\n", " ").trim(),
            mode = mode,
            colorArgb = 0xFF000000L or (color.toLong() and 0xFFFFFFL),
        )
    }

    /** B 站 XML：`<d p="时间,模式,字号,颜色,时间戳,弹幕池,用户,行内ID">内容</d>` */
    fun parseXml(xml: String): List<DanmakuComment> {
        val regex = Regex("""<d\s+p="([^"]*)"[^>]*>(.*?)</d>""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(xml).mapNotNull { match ->
            val parts = match.groupValues[1].split(',')
            val seconds = parts.getOrNull(0)?.toFloatOrNull() ?: return@mapNotNull null
            val mode = DanmakuMode.of(parts.getOrNull(1)?.toIntOrNull() ?: 1)
            val size = parts.getOrNull(2)?.toIntOrNull() ?: 25
            val color = parts.getOrNull(3)?.toIntOrNull() ?: 0xFFFFFF
            val text = match.groupValues[2]
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .trim()
            if (text.isEmpty()) return@mapNotNull null
            DanmakuComment(
                id = parts.getOrNull(7) ?: seconds.toString(),
                timeMs = (seconds * 1000f).toLong().coerceAtLeast(0L),
                text = text,
                mode = mode,
                colorArgb = 0xFF000000L or (color.toLong() and 0xFFFFFFL),
                sizePercent = ((size / 25f) * 100f).toInt().coerceIn(60, 200),
            )
        }.sortedBy { it.timeMs }.toList()
    }

    /** 屏蔽词过滤：忽略大小写、去首尾空格；空词条忽略。 */
    fun filter(comments: List<DanmakuComment>, blockedWords: List<String>): List<DanmakuComment> {
        val words = blockedWords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return comments
        return comments.filter { comment ->
            val lower = comment.text.lowercase()
            words.none { lower.contains(it) }
        }
    }

    /** 缓存编码：一行一条 `time|mode|color|text`，比 JSON 小一个数量级，解析也更快。 */
    fun encodeCache(comments: List<DanmakuComment>): String = buildString {
        comments.forEach { comment ->
            append(comment.timeMs).append('|')
                .append(comment.mode.protocolValue).append('|')
                .append(comment.colorArgb).append('|')
                .append(comment.text.replace('|', '｜').replace('\n', ' '))
                .append('\n')
        }
    }

    fun decodeCache(raw: String): List<DanmakuComment> = raw.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val parts = line.split('|', limit = 4)
        if (parts.size < 4) return@mapNotNull null
        val time = parts[0].toLongOrNull() ?: return@mapNotNull null
        DanmakuComment(
            id = "$time-${parts[3].hashCode()}",
            timeMs = time,
            text = parts[3],
            mode = DanmakuMode.of(parts[1].toIntOrNull() ?: 1),
            colorArgb = parts[2].toLongOrNull() ?: 0xFFFFFFFFL,
        )
    }.toList()
}
