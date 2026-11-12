package com.moontvplus.phone.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 站点地址：`scheme://host[:port][/子路径]/`。
 *
 * 支持子路径部署（如 `https://example.com/moontv`），[base] 保证以 `/` 结尾，
 * 作为所有相对 API 路径的拼接前缀。
 */
data class ServerAddress(val base: HttpUrl) {

    val origin: String
        get() = "${base.scheme}://${base.host}${if (base.port == HttpUrl.defaultPort(base.scheme)) "" else ":${base.port}"}"

    /** 相对路径前缀，根路径部署时为空串，子路径部署时为 `/moontv`。 */
    val pathPrefix: String
        get() = base.encodedPath.trimEnd('/')

    /** 用于持久化的字符串形式。 */
    fun toStorage(): String = base.toString()

    fun display(): String = toStorage().trimEnd('/')

    companion object {
        private val KNOWN_ROUTE_SEGMENTS = setOf(
            "play", "search", "detail", "login", "register", "admin",
            "live", "manga", "tv", "watch-room", "douban", "source-search",
        )

        /**
         * 解析用户输入。未带 scheme 时使用 [scheme]。
         *
         * 容忍这些常见写法：
         * - `tv.example.com`
         * - `http://192.168.1.10:3000/`
         * - `https://example.com/moontv`
         * - 误贴的 `https://example.com/tv`
         */
        fun parse(raw: String, scheme: String = "https"): ServerAddress? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "$scheme://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "http" && parsed.scheme != "https") return null

            var segments = parsed.pathSegments.filter { it.isNotEmpty() }
            // 误把页面路径当站点地址：去掉末尾的应用路由段
            while (segments.isNotEmpty() && segments.last().lowercase() in KNOWN_ROUTE_SEGMENTS) {
                segments = segments.dropLast(1)
            }
            val prefix = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")

            val base = parsed.newBuilder()
                .encodedPath(if (prefix.isEmpty()) "/" else "$prefix/")
                .query(null)
                .fragment(null)
                .build()
            return ServerAddress(base)
        }

        /** 从持久化字符串恢复。 */
        fun parseStored(stored: String?): ServerAddress? {
            val value = stored?.trim().orEmpty()
            if (value.isEmpty()) return null
            return parse(value)
        }

        /**
         * 用户没写 scheme 时，按 https → http 的顺序尝试。
         */
        fun candidates(raw: String): List<ServerAddress> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.contains("://")) {
                return listOfNotNull(parse(trimmed))
            }
            return listOfNotNull(parse(trimmed, "https"), parse(trimmed, "http"))
                .distinctBy { it.toStorage() }
        }
    }
}
