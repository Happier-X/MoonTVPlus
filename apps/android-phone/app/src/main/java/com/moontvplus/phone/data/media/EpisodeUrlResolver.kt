package com.moontvplus.phone.data.media

import java.net.URLEncoder

/**
 * 一集的播放计划。
 *
 * [url] 是最终给播放器的地址；[isHls] 决定用哪种 MediaSource；
 * [unsupported] 表示容器格式 Media3 解不了（avi/rmvb 之类），要提示用户换方式。
 */
data class EpisodePlan(
    val url: String,
    val isHls: Boolean,
    val unsupported: Boolean = false,
    /** 走的是站内代理（需要登录态），第三方直链则要单独加 Referer */
    val viaServer: Boolean = false,
)

/**
 * 决定某一集用哪个地址播。
 *
 * 优先级（与服务端行为对齐）：
 * 1. 服务端已经把地址改写成 `/api/proxy-m3u8` → 原样用（这就是去广告代理）
 * 2. 源开了 `proxyMode` → 走 `/api/proxy/vod/m3u8?url=&source=`
 * 3. 其它 http(s) 地址 → 直连，第三方 HLS 由播放器补 Referer
 */
object EpisodeUrlResolver {

    private val HLS_SUFFIXES = listOf(".m3u8", ".m3u")
    private val DIRECT_SUFFIXES = listOf(".mp4", ".webm", ".mkv", ".flv", ".mov", ".ts")
    private val UNSUPPORTED = listOf(".avi", ".rmvb", ".rm", ".wmv", ".3gp", ".m2ts")

    private const val SERVER_PROXY_M3U8 = "/api/proxy-m3u8"
    private const val SERVER_PROXY_VOD_M3U8 = "/api/proxy/vod/m3u8"

    fun resolve(
        origin: String,
        episodeUrl: String?,
        source: String,
        proxyMode: Boolean,
    ): EpisodePlan? {
        val raw = episodeUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val absolute = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> origin.trimEnd('/') + raw
            else -> return null
        }

        // 1. 服务端已经套好代理
        if (absolute.contains(SERVER_PROXY_M3U8) || absolute.contains(SERVER_PROXY_VOD_M3U8)) {
            return EpisodePlan(
                url = absolute,
                isHls = true,
                viaServer = true,
            )
        }

        val path = absolute.substringBefore('?').lowercase()

        if (UNSUPPORTED.any { path.endsWith(it) }) {
            return EpisodePlan(url = absolute, isHls = false, unsupported = true)
        }

        val isHls = HLS_SUFFIXES.any { path.endsWith(it) }

        // 2. 源要求走服务端代理
        if (proxyMode && isHls) {
            val encoded = encode(absolute)
            val proxied = "${origin.trimEnd('/')}$SERVER_PROXY_VOD_M3U8" +
                "?url=$encoded&source=${encode(source)}"
            return EpisodePlan(url = proxied, isHls = true, viaServer = true)
        }

        // 3. 直连
        return EpisodePlan(
            url = absolute,
            isHls = isHls,
            unsupported = !isHls && DIRECT_SUFFIXES.none { path.endsWith(it) },
        )
    }

    /** 从 `/api/proxy-m3u8?url=...` 里取回原始地址，播放失败时用来降级直连。 */
    fun unwrapProxyUrl(url: String): String? = runCatching {
        val query = url.substringAfter('?', "")
        query.split('&')
            .firstOrNull { it.startsWith("url=") }
            ?.substringAfter("url=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.startsWith("http") }
    }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
