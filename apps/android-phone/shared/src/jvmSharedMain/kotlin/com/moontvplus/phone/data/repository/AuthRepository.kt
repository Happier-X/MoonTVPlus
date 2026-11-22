package com.moontvplus.phone.data.repository

import com.moontvplus.phone.core.common.ApiResult
import com.moontvplus.phone.core.common.AppError
import com.moontvplus.phone.core.common.parseServerMessage
import com.moontvplus.phone.core.storage.SettingsStore
import com.moontvplus.phone.core.model.AuthInfo
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.model.LoginRequest
import com.moontvplus.phone.core.model.QrCreateResponse
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.network.SessionManager
import com.moontvplus.phone.core.platform.SiteCookies
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
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
            // 记住密码由登录页写进 Keystore 加密的 CredentialVault；
            // 这里不再把账号密码写进设置或 session.json。
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

    /**
     * 扫码登录第一步：向站点申请一次性二维码。
     * 二维码内容就是 `qrUrl`，手机浏览器打开后确认，本端轮询状态拿登录态。
     */
    suspend fun createQrCode(): ApiResult<QrCreateResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.qrCreate()
            val body = response.body()
            if (!response.isSuccessful || body == null || body.token.isBlank()) {
                return@withContext ApiResult.Err(
                    AppError.Http(response.code(), body?.let { "二维码生成失败" } ?: "二维码生成失败")
                )
            }
            ApiResult.Ok(body)
        } catch (e: IOException) {
            ApiResult.Err(AppError.Network)
        } catch (e: Exception) {
            ApiResult.Err(AppError.Unknown(e.message))
        }
    }

    /**
     * 扫码登录第二步：轮询状态。
     *
     * 服务端在 `confirmed` 那一次用 `Set-Cookie: auth=...` 把登录态写进响应头，
     * 值就是登录接口返回的 token 原文，所以直接拿它建会话。
     */
    suspend fun pollQrCode(token: String): ApiResult<QrPollResult> = withContext(Dispatchers.IO) {
        try {
            val response = api.qrStatus(token)
            val body = response.body() ?: return@withContext ApiResult.Err(
                AppError.Http(response.code(), "二维码状态获取失败")
            )
            if (body.status != "confirmed") {
                return@withContext ApiResult.Ok(QrPollResult(body.status, body.expiresAt))
            }

            val raw = response.headers().values("set-cookie")
                .firstOrNull { it.startsWith("auth=", ignoreCase = true) }
                ?.substringAfter("auth=")
                ?.substringBefore(";")
                ?: return@withContext ApiResult.Err(AppError.Http(401, "登录态缺失，请重试"))

            val authInfo = json.decodeFromString(
                AuthInfo.serializer(),
                URLDecoder.decode(raw, "UTF-8"),
            )
            val session = AuthSession(
                token = raw,
                auth = authInfo,
                storageType = settings.snapshot().storageType,
                savedAt = System.currentTimeMillis(),
            )
            sessions.save(session)
            serverProvider.current()?.origin?.let { cookies.writeAuth(it, raw) }
            ApiResult.Ok(QrPollResult(body.status, body.expiresAt, session))
        } catch (e: IOException) {
            ApiResult.Err(AppError.Network)
        } catch (e: Exception) {
            ApiResult.Err(AppError.Unknown(e.message))
        }
    }

    /** 注册：字段与校验跟网页版 /register 一致，错误信息由服务端返回。 */
    suspend fun register(
        username: String,
        password: String,
        inviteCode: String?,
        turnstileToken: String?,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.register(
                com.moontvplus.phone.core.model.RegisterRequest(
                    username = username.trim(),
                    password = password,
                    inviteCode = inviteCode?.trim()?.takeIf { it.isNotEmpty() },
                    turnstileToken = turnstileToken?.takeIf { it.isNotBlank() },
                ),
            )
            val payload = response.body()
            if (!response.isSuccessful || payload?.ok != true) {
                val message = payload?.error
                    ?: parseServerMessage(
                        json,
                        runCatching { response.errorBody()?.string() }.getOrNull(),
                    )
                    ?: "注册失败（HTTP " + response.code() + "）"
                return@withContext ApiResult.Err(AppError.Http(response.code(), message))
            }
            ApiResult.Ok(username.trim())
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
        settings.clearCredentials()
        serverProvider.current()?.origin?.let { cookies.clearAuth(it) }
    }

    /**
     * 冷启动时静态 token 已过期/被吊销：用保存的账号密码重新登录一次。
     * Turnstile 无法复用，因此这类站点仍需用户完成一次人机验证；
     * 只要 token 有效就不会走到这里。
     */
    suspend fun reauthenticate(): ApiResult<AuthSession> = withContext(Dispatchers.IO) {
        val session = sessions.session.value
        val username = session?.savedUsername
        val password = session?.savedPassword
        if (username.isNullOrBlank() || password.isNullOrEmpty()) {
            return@withContext ApiResult.Err(AppError.Http(401, "缺少自动登录凭据"))
        }
        login(username = username, password = password)
    }

    private fun defaultLoginMessage(code: Int): String = when (code) {
        401 -> "用户名或密码错误"
        403 -> "该账号已被封禁"
        429 -> "尝试过于频繁，请稍后再试"
        else -> "登录失败（HTTP $code）"
    }
}

/** 扫码轮询结果：[session] 只在 `confirmed` 时有值。 */
data class QrPollResult(
    val status: String,
    val expiresAt: Long = 0L,
    val session: AuthSession? = null,
)
