package com.moontvplus.phone.data.repository

import com.moontvplus.phone.core.common.ApiResult
import com.moontvplus.phone.core.common.AppError
import com.moontvplus.phone.core.common.parseServerMessage
import com.moontvplus.phone.core.storage.SettingsStore
import com.moontvplus.phone.core.model.AuthInfo
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.model.LoginRequest
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.network.SessionManager
import com.moontvplus.phone.core.platform.SiteCookies
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AuthRepository(
    private val api: MoonApi,
    private val sessions: SessionManager,
    private val settings: SettingsStore,
    private val serverProvider: ServerProvider,
    private val cookies: SiteCookies,
    private val json: Json,
) {

    suspend fun login(
        username: String?,
        password: String,
        turnstileToken: String? = null,
    ): ApiResult<AuthSession> = withContext(Dispatchers.IO) {
        val storageType = settings.snapshot().storageType
        val isLocalStorage = storageType.equals("localstorage", ignoreCase = true)

        val body = if (isLocalStorage) {
            LoginRequest(password = password)
        } else {
            LoginRequest(
                username = username?.trim()?.takeIf { it.isNotEmpty() },
                password = password,
                turnstileToken = turnstileToken?.takeIf { it.isNotBlank() },
            )
        }

        try {
            val response = api.login(body)
            if (!response.isSuccessful) {
                val message = parseServerMessage(
                    json,
                    runCatching { response.errorBody()?.string() }.getOrNull(),
                ) ?: defaultLoginMessage(response.code())
                return@withContext ApiResult.Err(AppError.Http(response.code(), message))
            }

            val payload = response.body()
            if (payload?.ok != true) {
                return@withContext ApiResult.Err(
                    AppError.Http(response.code(), payload?.message ?: "登录失败，请重试")
                )
            }

            // 未配置 PASSWORD 的 localStorage 站点会直接放行且不发 token，
            // 此时不带 Authorization 也能访问，用空 token 的会话表示“已进入站点”。
            val token = payload.token.orEmpty()
            val auth = payload.auth ?: AuthInfo(role = "owner")

            val session = AuthSession(
                token = token,
                auth = auth,
                storageType = storageType,
                savedAt = System.currentTimeMillis(),
            )
            sessions.save(session)
            // 网页版入口要靠 cookie 带登录态，登录成功后同步写一份
            if (token.isNotBlank()) {
                serverProvider.current()?.origin?.let { cookies.writeAuth(it, token) }
            }
            ApiResult.Ok(session)
        } catch (e: IOException) {
            ApiResult.Err(AppError.Network)
        } catch (e: Exception) {
            ApiResult.Err(AppError.Unknown(e.message))
        }
    }

    /** 服务端登出失败也照样清本地会话与站点 cookie。 */
    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { api.logout() }
        sessions.clear()
        serverProvider.current()?.origin?.let { cookies.clearAuth(it) }
    }

    private fun defaultLoginMessage(code: Int): String = when (code) {
        401 -> "用户名或密码错误"
        403 -> "该账号已被封禁"
        429 -> "尝试过于频繁，请稍后再试"
        else -> "登录失败（HTTP $code）"
    }
}
