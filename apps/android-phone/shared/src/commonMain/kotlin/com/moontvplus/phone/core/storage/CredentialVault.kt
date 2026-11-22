package com.moontvplus.phone.core.storage

/** 记住密码用的凭据。Android 实现必须放进 Keystore 加密存储，不能落到明文设置文件。 */
data class SavedCredentials(
    val username: String?,
    val password: String,
)

/**
 * 登录页「记住密码」的存储边界。
 *
 * 共享层只依赖这个接口：Android 用 Keystore + 私有 SharedPreferences，
 * 桌面端用内存实现（不落盘）。
 */
interface CredentialVault {
    suspend fun load(): SavedCredentials?

    suspend fun save(username: String?, password: String)

    suspend fun clear()
}

/** 桌面端/测试用：只在当前进程内保留，重启即失效。 */
class InMemoryCredentialVault : CredentialVault {
    private var saved: SavedCredentials? = null

    override suspend fun load(): SavedCredentials? = saved

    override suspend fun save(username: String?, password: String) {
        if (password.isBlank()) {
            saved = null
            return
        }
        saved = SavedCredentials(username?.trim()?.takeIf { it.isNotEmpty() }, password)
    }

    override suspend fun clear() {
        saved = null
    }
}
