package com.moontvplus.phone.core.network

import android.os.Build
import com.moontvplus.phone.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

/** 把请求里的占位 host 换成当前服务器地址，并补上子路径前缀。 */
@Singleton
class ServerUrlInterceptor @Inject constructor(
    private val urls: ApiUrlResolver,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val base = urls.base() ?: return chain.proceed(chain.request())
        val request = chain.request()
        val original = request.url
        val prefix = urls.prefixPath("")

        val rewritten = original.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(prefix + original.encodedPath)
            .build()

        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}

/** 给本站请求补 `Authorization: Bearer <token>`；登录与刷新接口由调用方自己带。 */
@Singleton
class AuthInterceptor @Inject constructor(
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
 */
@Singleton
class UserAgentInterceptor @Inject constructor() : Interceptor {

    private val userAgent: String by lazy {
        val model = sanitize(Build.MODEL)
        val release = sanitize(Build.VERSION.RELEASE)
        "${BuildConfig.APP_UA} (Android $release; $model)"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val existing = request.header("User-Agent")
        // 播放器会针对第三方 CDN 单独设置浏览器 UA，这里不覆盖
        if (existing != null) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("User-Agent", userAgent).build()
        )
    }

    /** Header 只允许可见 ASCII，机型名可能带中文。 */
    private fun sanitize(value: String?): String {
        val cleaned = value.orEmpty()
            .filter { it.code in 0x20..0x7e }
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifEmpty { "unknown" }
    }
}
