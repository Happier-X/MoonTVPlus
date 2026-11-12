package com.moontvplus.phone.core.network

import com.moontvplus.phone.core.platform.Platform
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把 Retrofit 占位 host（`127.0.0.1`）的请求改写成当前服务器地址，并补上子路径前缀。
 *
 * 只处理占位 host：图片代理、播放分片这类已经是绝对地址的请求原样放行，
 * 否则第三方 CDN 会被错误地换成站点域名。
 */
class ServerUrlInterceptor(
    private val urls: ApiUrlResolver,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = request.url
        if (original.host != PLACEHOLDER_HOST) return chain.proceed(request)

        val base = urls.base() ?: return chain.proceed(request)
        val prefix = urls.prefixPath("")
        val rewritten = original.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(prefix + original.encodedPath)
            .build()

        return chain.proceed(request.newBuilder().url(rewritten).build())
    }

    private companion object {
        const val PLACEHOLDER_HOST = "127.0.0.1"
    }
}

/** 给本站请求补 `Authorization: Bearer <token>`；登录与刷新接口由调用方自己带。 */
class AuthInterceptor(
    private val sessions: SessionManager,
    private val urls: ApiUrlResolver,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!urls.isServerHost(request.url)) return chain.proceed(request)

        val path = request.url.encodedPath
        if (path.endsWith("/api/login") || path.endsWith("/api/auth/refresh")) {
            return chain.proceed(request)
        }

        val token = sessions.currentToken()
        val builder = request.newBuilder()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return chain.proceed(builder.build())
    }
}

/**
 * 固定 UA。服务端按 UA 里是否含 `moontvplus app` 决定是否给配置过的源套去广告代理。
 *
 * UA 由各平台在启动时通过 [Platform] 注入（Android 带机型、桌面带系统版本）。
 */
class UserAgentInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 播放器会针对第三方 CDN 单独设置浏览器 UA，这里不覆盖
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("User-Agent", Platform.userAgent).build()
        )
    }
}
