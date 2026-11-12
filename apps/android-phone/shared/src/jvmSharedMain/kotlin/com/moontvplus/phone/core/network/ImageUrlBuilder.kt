package com.moontvplus.phone.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 图片地址统一走站点的 `/api/image-proxy`。
 *
 * 原因：豆瓣 / Bangumi / TMDB 的图床都有防盗链，服务端代理已经带了正确的 Referer，
 * 而且这条路走的是共享 OkHttp，会自动带上 Authorization。
 */
class ImageUrlBuilder(
    private val urls: ApiUrlResolver,
) {

    fun proxied(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        // 站内相对路径（CMS 返回的 /xxx.jpg）直接拼到站点地址上
        if (value.startsWith("/")) return urls.absolutize(value)?.toString()

        if (!value.startsWith("http://") && !value.startsWith("https://")) return null

        val parsed = value.toHttpUrlOrNull() ?: return null
        // 已经在站点上的图片不用再套一层代理
        if (urls.isServerHost(parsed)) return value

        return urls.resolve("/api/image-proxy", mapOf("url" to value))?.toString()
    }
}
