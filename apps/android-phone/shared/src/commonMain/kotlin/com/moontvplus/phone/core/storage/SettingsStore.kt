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
    val turnstileSiteKey: String? = null,
    /** 旧壳 cookie 迁移只做一次，避免退出登录后被旧 cookie 顶回来 */
    val legacyCookieMigrationDone: Boolean = false,
    /** system / light / dark */
    val themeMode: String = "system",
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
    ) = update { current ->
        current.copy(
            serverAddress = address,
            siteName = siteName ?: current.siteName,
            serverVersion = serverVersion ?: current.serverVersion,
            storageType = storageType ?: current.storageType,
            loginRequireTurnstile = loginRequireTurnstile,
            turnstileSiteKey = turnstileSiteKey,
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

    suspend fun setThemeMode(mode: String) = update { it.copy(themeMode = mode) }

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
