package com.moontvplus.phone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `auth` cookie / 登录响应里 `auth` 字段的内容。 */
@Serializable
data class AuthInfo(
    val role: String? = null,
    val username: String? = null,
    val timestamp: Long? = null,
    val tokenId: String? = null,
    val refreshToken: String? = null,
    val refreshExpires: Long? = null,
    val signature: String? = null,
    /** 仅 localStorage 模式存在 */
    val password: String? = null,
)

/**
 * 本地保存的会话。
 *
 * [token] 就是登录接口返回的 `token` 字段原样：
 * `encodeURIComponent(JSON.stringify(auth))`，直接放进 `Authorization: Bearer`。
 */
@Serializable
data class AuthSession(
    val token: String,
    val auth: AuthInfo,
    val storageType: String? = null,
    val savedAt: Long = 0L,
) {
    val isLocalStorageMode: Boolean
        get() = storageType == null || storageType.equals("localstorage", ignoreCase = true)

    val displayName: String
        get() = auth.username?.takeIf { it.isNotBlank() } ?: "站长"

    val roleLabel: String
        get() = when (auth.role) {
            "owner" -> "站长"
            "admin" -> "管理员"
            "user" -> "用户"
            else -> auth.role ?: "用户"
        }
}

@Serializable
data class LoginRequest(
    val username: String? = null,
    val password: String,
    val turnstileToken: String? = null,
)

@Serializable
data class LoginResponse(
    val ok: Boolean = false,
    val token: String? = null,
    val auth: AuthInfo? = null,
    val message: String? = null,
)

@Serializable
data class LogoutResponse(
    val ok: Boolean = false,
)

@Serializable
data class RefreshResponse(
    val ok: Boolean = false,
    val token: String? = null,
    val auth: AuthInfo? = null,
)

/** 站点公开配置，用于判断登录方式与展示站点信息。 */
@Serializable
data class ServerConfig(
    val SiteName: String? = null,
    val StorageType: String? = null,
    val Version: String? = null,
    val TVModeEnabled: Boolean? = null,
    val EnableRegistration: Boolean? = null,
    val RequireRegistrationInviteCode: Boolean? = null,
    val RegistrationRequireTurnstile: Boolean? = null,
    val LoginRequireTurnstile: Boolean? = null,
    val TurnstileSiteKey: String? = null,
    val EnableOfflineDownload: Boolean? = null,
    val DanmakuAutoLoadDefault: Boolean? = null,
    @SerialName("AIEnabled") val aiEnabled: Boolean? = null,
) {
    val isLocalStorageMode: Boolean
        get() = StorageType.equals("localstorage", ignoreCase = true)
}
