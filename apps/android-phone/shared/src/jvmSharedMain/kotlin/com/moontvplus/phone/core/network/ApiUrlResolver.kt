package com.moontvplus.phone.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 把代码里的相对路径（`/api/xxx`）拼到当前服务器地址上。
 *
 * Retrofit 的 baseUrl 是占位地址，真正的 host 由 [ServerUrlInterceptor] 在请求时替换，
 * 因此切换服务器地址无需重建 Retrofit。
 */
class ApiUrlResolver(
    private val serverProvider: ServerProvider,
) {

    fun base(): HttpUrl? = serverProvider.current()?.base

    fun isServerHost(url: HttpUrl): Boolean {
        val base = base() ?: return false
        return url.host == base.host && url.port == base.port && url.scheme == base.scheme
    }

    /** 把 `/api/xxx` 加上子路径前缀。 */
    fun prefixPath(path: String): String {
        val prefix = serverProvider.current()?.pathPrefix ?: return path
        return if (prefix.isEmpty()) path else prefix + path
    }

    /** 拼一个指向当前服务器的绝对地址，服务器地址尚未确定时返回 null。 */
    fun resolve(path: String, query: Map<String, String?> = emptyMap()): HttpUrl? {
        val base = base() ?: return null
        val builder = base.newBuilder().encodedPath(prefixPath(path))
        query.forEach { (key, value) ->
            if (value != null) builder.addQueryParameter(key, value)
        }
        return builder.build()
    }

    /** 站点内地址（`/api/...`）转绝对地址；已是绝对地址的原样返回。 */
    fun absolutize(raw: String): HttpUrl? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value.toHttpUrlOrNullSafe()
        }
        if (!value.startsWith("/")) return null
        return resolve(value)
    }

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? =
        runCatching { toHttpUrlOrNull() }.getOrNull()
}
