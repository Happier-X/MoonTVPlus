package com.moontvplus.phone.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val serverAddress: String? = null,
    val siteName: String? = null,
    val serverVersion: String? = null,
    val storageType: String? = null,
    val loginRequireTurnstile: Boolean = false,
    /** 站点是否开启注册（登录页「立即注册」入口）。 */
    val enableRegistration: Boolean = false,
    val requireRegistrationInviteCode: Boolean = false,
    val registrationRequireTurnstile: Boolean = false,
    /** 站点是否开启电视直播 / 网络直播（只用于底栏入口，公开配置）。 */
    val liveEnabled: Boolean = false,
    val webLiveEnabled: Boolean = false,
    val turnstileSiteKey: String? = null,
    /** 旧壳 cookie 迁移只做一次，避免退出登录后被旧 cookie 顶回来 */
    val legacyCookieMigrationDone: Boolean = false,
    /**
     * Selene 同款自动登录凭据。保存在应用私有目录，不写入外部存储；
     * Turnstile 挑战无法复用，但账号密码可以，避免每次打开都重新输入。
     */
    val savedUsername: String? = null,
    val savedPassword: String? = null,
    /** system / light / dark */
    val themeMode: String = "system",
    /** 搜索页显示方式：card / list（网页版「高级」里的卡片/列表）。 */
    val searchDisplayMode: String = "card",
    /** 搜索页是否聚合同一部片子的多个源（网页版 viewMode=agg/all）。 */
    val searchAggregate: Boolean = true,
    val fluidSearch: Boolean = true,
    val exactSearch: Boolean = true,
    val autoNext: Boolean = true,
    val autoPictureInPicture: Boolean = false,
    val playbackSpeed: Float = 1f,
)

/**
 * 应用设置。
 *
 * 用「一个 JSON 文件 + StateFlow」代替 DataStore：DataStore 只在 Android 上有，
 * 桌面端直接用同一个实现即可。
 */
class SettingsStore(
    private val store: TextFileStore,
    private val json: Json,
) {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() {
        val raw = store.read(FILE) ?: return
        runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrNull()
            ?.let { _settings.value = it }
    }

    suspend fun snapshot(): AppSettings = _settings.value

    private suspend fun update(block: (AppSettings) -> AppSettings) {
        _settings.update(block)
        persist()
    }

    private suspend fun persist() {
        runCatching {
            store.write(FILE, json.encodeToString(AppSettings.serializer(), _settings.value))
        }
    }

    suspend fun saveServer(
        address: String,
        siteName: String?,
        serverVersion: String?,
        storageType: String?,
        loginRequireTurnstile: Boolean = false,
        turnstileSiteKey: String? = null,
        enableRegistration: Boolean = false,
        requireRegistrationInviteCode: Boolean = false,
        registrationRequireTurnstile: Boolean = false,
        liveEnabled: Boolean = false,
        webLiveEnabled: Boolean = false,
    ) = update { current ->
        current.copy(
            serverAddress = address,
            siteName = siteName ?: current.siteName,
            serverVersion = serverVersion ?: current.serverVersion,
            storageType = storageType ?: current.storageType,
            loginRequireTurnstile = loginRequireTurnstile,
            turnstileSiteKey = turnstileSiteKey,
            enableRegistration = enableRegistration,
            requireRegistrationInviteCode = requireRegistrationInviteCode,
            registrationRequireTurnstile = registrationRequireTurnstile,
            liveEnabled = liveEnabled,
            webLiveEnabled = webLiveEnabled,
        )
    }

    suspend fun clearServerInfo() = update { current ->
        current.copy(
            serverAddress = null,
            siteName = null,
            serverVersion = null,
            storageType = null,
            loginRequireTurnstile = false,
            turnstileSiteKey = null,
        )
    }

    suspend fun markLegacyCookieMigrationDone() =
        update { it.copy(legacyCookieMigrationDone = true) }

    suspend fun saveCredentials(username: String?, password: String?) = update { current ->
        current.copy(
            savedUsername = username?.trim()?.takeIf { it.isNotEmpty() },
            savedPassword = password?.takeIf { it.isNotEmpty() },
        )
    }

    suspend fun clearCredentials() = update { current ->
        current.copy(savedUsername = null, savedPassword = null)
    }

    suspend fun setThemeMode(mode: String) = update { it.copy(themeMode = mode) }

    suspend fun setSearchDisplayMode(mode: String) = update { it.copy(searchDisplayMode = mode) }

    suspend fun setSearchAggregate(enabled: Boolean) = update { it.copy(searchAggregate = enabled) }

    suspend fun setFluidSearch(enabled: Boolean) = update { it.copy(fluidSearch = enabled) }

    suspend fun setExactSearch(enabled: Boolean) = update { it.copy(exactSearch = enabled) }

    suspend fun setAutoNext(enabled: Boolean) = update { it.copy(autoNext = enabled) }

    suspend fun setAutoPictureInPicture(enabled: Boolean) =
        update { it.copy(autoPictureInPicture = enabled) }

    suspend fun setPlaybackSpeed(speed: Float) = update { it.copy(playbackSpeed = speed) }

    private companion object {
        const val FILE = "settings.json"
    }
}
