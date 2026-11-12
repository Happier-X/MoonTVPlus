package com.moontvplus.phone.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 401 时自动刷新 token 并重放一次请求。
 *
 * 只重试一次（`priorResponse != null` 即放弃），避免死循环；
 * 刷新本身走独立的 OkHttpClient，避免和业务请求抢同一个 Dispatcher 队列。
 */
class TokenAuthenticator(
    private val sessions: SessionManager,
    private val urls: ApiUrlResolver,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (!urls.isServerHost(request.url)) return null

        val path = request.url.encodedPath
        if (path.endsWith("/api/login") || path.endsWith("/api/auth/refresh")) {
            return null
        }

        // 已经重放过一次，说明刷新没能解决问题
        if (response.priorResponse != null) return null

        val stale = request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val fresh = runCatching {
            runBlocking { sessions.refreshIfNeeded(stale) }
        }.getOrNull() ?: return null

        return request.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .build()
    }
}
