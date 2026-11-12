package com.moontvplus.phone.data.local

import android.content.Context
import android.webkit.CookieManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 站点 cookie（`auth=<token>`）。
 *
 * 原生页面用 Authorization 头，但「网页版」入口是 WebView，只能靠 cookie 带登录态，
 * 所以登录/退出时两边要一起维护。
 */
@Singleton
class SiteCookieStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun writeAuth(origin: String, token: String) = withContext(Dispatchers.IO) {
        runCatching {
            manager()?.apply {
                setCookie(origin, "$AUTH_COOKIE=$token; Path=/; Max-Age=$MAX_AGE")
                flush()
            }
        }
        Unit
    }

    suspend fun clearAuth(origin: String) = withContext(Dispatchers.IO) {
        runCatching {
            manager()?.apply {
                setCookie(origin, "$AUTH_COOKIE=; Path=/; Max-Age=0")
                flush()
            }
        }
        Unit
    }

    suspend fun readAuth(origin: String): String? = withContext(Dispatchers.IO) {
        val cookies = runCatching { manager()?.getCookie(origin) }.getOrNull()
            ?: return@withContext null
        cookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$AUTH_COOKIE=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    private fun manager(): CookieManager? = runCatching { CookieManager.getInstance() }.getOrNull()

    private companion object {
        const val AUTH_COOKIE = "auth"
        const val MAX_AGE = 60 * 60 * 24 * 60
    }
}
