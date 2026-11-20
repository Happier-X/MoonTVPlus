package com.moontvplus.phone.danmaku

import com.moontvplus.phone.core.storage.DanmakuPreferences
import com.moontvplus.phone.core.storage.TextFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8

/**
 * 弹弹play 公开弹幕接口。
 *
 * 流程：搜索剧集（标题 + 集数）→ 取 `episodeId` → 拉弹幕 → 本地缓存。
 * 缓存按 episodeId 存成紧凑文本，一集 30 万条也只有几百 KB。
 *
 * 凭据（AppId / AppSecret）由用户在播放器设置里填；未配置时 [search] 直接失败并
 * 给出可读原因，界面上显示「弹幕未配置」，播放本身不受影响。
 */
class DanmakuRepository(
    private val client: OkHttpClient,
    private val store: TextFileStore,
    private val json: Json,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** 允许单测换成可控调度器；生产环境走 IO */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun search(
        title: String,
        episode: Int,
        preferences: DanmakuPreferences,
    ): Result<List<DanmakuEpisode>> = withContext(ioDispatcher) {
        if (!preferences.configured) return@withContext Result.failure(DanmakuException("弹幕未配置"))
        val cleanTitle = cleanTitle(title)
        if (cleanTitle.isBlank()) return@withContext Result.failure(DanmakuException("没有可用于匹配的剧名"))
        val first = runCatching {
            requestEpisodes(cleanTitle, episode.coerceAtLeast(1), preferences)
        }.getOrElse { return@withContext Result.failure(it) }
        if (first.isNotEmpty()) return@withContext Result.success(first)
        // 有些条目在剧名里带「第X季/年份」，用去掉修饰的标题再查一次
        val simplified = cleanTitle.replace(Regex("""[\s（(【\[].*?[）)】\]]"""), "").trim()
        if (simplified.isNotBlank() && simplified != cleanTitle) {
            return@withContext Result.success(
                runCatching { requestEpisodes(simplified, 1, preferences) }.getOrDefault(emptyList()),
            )
        }
        Result.success(emptyList())
    }

    /** 在候选里挑最贴近当前集的一条。 */
    fun bestMatch(candidates: List<DanmakuEpisode>, title: String, episode: Int): DanmakuEpisode? {
        if (candidates.isEmpty()) return null
        val target = cleanTitle(title)
        val sameTitle = candidates.filter { similarity(it.animeTitle, target) > 0.55f || it.animeTitle.contains(target) }
        val pool = sameTitle.ifEmpty { candidates }
        return pool.minByOrNull { candidate ->
            val number = extractEpisodeNumber(candidate.episodeTitle)
            when {
                number == null -> 100
                number == episode -> 0
                else -> kotlin.math.abs(number - episode) * 10
            }
        }
    }

    suspend fun comments(
        episodeId: Long,
        preferences: DanmakuPreferences,
        forceRefresh: Boolean = false,
    ): Result<List<DanmakuComment>> = withContext(ioDispatcher) {
        if (!preferences.configured) return@withContext Result.failure(DanmakuException("弹幕未配置"))
        val cacheFile = "danmaku/$episodeId.txt"
        if (!forceRefresh) {
            val cached = store.read(cacheFile)
            if (!cached.isNullOrBlank()) {
                val parsed = DanmakuParser.decodeCache(cached)
                if (parsed.isNotEmpty()) return@withContext Result.success(parsed)
            }
        }
        val body = runCatching { fetch("/api/v2/comment/$episodeId?withRelated=true&chConvert=0", preferences) }
            .getOrElse { return@withContext Result.failure(it) }
        val parsed = runCatching {
            json.decodeFromString(DandanplayCommentsResponse.serializer(), body)
        }.getOrElse { return@withContext Result.failure(it) }
        val comments = DanmakuParser.parseDandanplay(parsed.comments)
        if (comments.isNotEmpty()) {
            // 缓存写入失败不影响本次播放
            runCatching { store.write(cacheFile, DanmakuParser.encodeCache(comments)) }
        }
        Result.success(comments)
    }

    suspend fun clearCache() {
        // 缓存文件按 episodeId 命名，逐个删太碎；交给系统清理缓存目录即可
    }

    private fun requestEpisodes(title: String, episode: Int, preferences: DanmakuPreferences): List<DanmakuEpisode> {
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val body = fetch(
            "/api/v2/search/episodes?anime=$encodedTitle&episode=$episode",
            preferences,
        )
        val response = json.decodeFromString(DandanplaySearchResponse.serializer(), body)
        if (!response.success && response.animes.isEmpty()) {
            throw DanmakuException(response.errorMessage ?: "弹幕搜索失败")
        }
        return response.animes.flatMap { anime ->
            anime.episodes.map { episode ->
                DanmakuEpisode(
                    animeId = anime.animeId,
                    animeTitle = anime.animeTitle,
                    episodeId = episode.episodeId,
                    episodeTitle = episode.episodeTitle,
                )
            }
        }
    }

    private fun fetch(pathWithQuery: String, preferences: DanmakuPreferences): String {
        val url: HttpUrl = (baseUrl.trimEnd('/') + pathWithQuery).toHttpUrl()
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val path = url.encodedPath
        val signature = (preferences.appId + timestamp + path + preferences.appSecret)
            .encodeUtf8().sha256().base64()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-AppId", preferences.appId)
            .header("X-Timestamp", timestamp)
            .header("X-Signature", signature)
            .header("User-Agent", "MoonTVPlus Player")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.decodeFromString(DandanplaySearchResponse.serializer(), body).errorMessage
                }.getOrNull()
                throw DanmakuException(
                    when (response.code) {
                        401, 403 -> "弹幕凭据无效，请检查 AppId / AppSecret"
                        404 -> "未找到该集的弹幕"
                        else -> message ?: "弹幕接口返回 HTTP ${response.code}"
                    },
                )
            }
            return body
        }
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""[\[【(（].*?[\]】)）]"""), " ")
        .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
        .replace(Regex("""(?i)\b(4k|1080p|720p|hdr|web-?dl|bluray|bd)\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun similarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        val aSet = a.filter { !it.isWhitespace() }.toSet()
        val bSet = b.filter { !it.isWhitespace() }.toSet()
        if (aSet.isEmpty() || bSet.isEmpty()) return 0f
        return aSet.intersect(bSet).size.toFloat() / aSet.union(bSet).size
    }

    private fun extractEpisodeNumber(title: String): Int? {
        // "第 12 集/话/期"、"EP12"、"12" 都能认
        Regex("""第\s*(\d+)\s*[集话話期]""").find(title)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""(?i)ep?\.?\s*(\d+)""").find(title)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""(\d+)""").find(title)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    class DanmakuException(message: String) : Exception(message)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.dandanplay.net"
    }
}
