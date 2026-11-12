package com.moontvplus.phone.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

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

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val dataStore = context.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            serverAddress = prefs[KEY_SERVER_ADDRESS],
            siteName = prefs[KEY_SITE_NAME],
            serverVersion = prefs[KEY_SERVER_VERSION],
            storageType = prefs[KEY_STORAGE_TYPE],
            loginRequireTurnstile = prefs[KEY_LOGIN_TURNSTILE] ?: false,
            turnstileSiteKey = prefs[KEY_TURNSTILE_SITE_KEY]?.takeIf { it.isNotBlank() },
            legacyCookieMigrationDone = prefs[KEY_LEGACY_COOKIE_MIGRATED] ?: false,
            themeMode = prefs[KEY_THEME_MODE] ?: "system",
            fluidSearch = prefs[KEY_FLUID_SEARCH] ?: true,
            exactSearch = prefs[KEY_EXACT_SEARCH] ?: true,
            autoNext = prefs[KEY_AUTO_NEXT] ?: true,
            autoPictureInPicture = prefs[KEY_AUTO_PIP] ?: false,
            playbackSpeed = prefs[KEY_PLAYBACK_SPEED] ?: 1f,
        )
    }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun saveServer(
        address: String,
        siteName: String?,
        serverVersion: String?,
        storageType: String?,
        loginRequireTurnstile: Boolean = false,
        turnstileSiteKey: String? = null,
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_SERVER_ADDRESS] = address
            siteName?.let { prefs[KEY_SITE_NAME] = it }
            serverVersion?.let { prefs[KEY_SERVER_VERSION] = it }
            storageType?.let { prefs[KEY_STORAGE_TYPE] = it }
            prefs[KEY_LOGIN_TURNSTILE] = loginRequireTurnstile
            prefs[KEY_TURNSTILE_SITE_KEY] = turnstileSiteKey.orEmpty()
        }
    }

    suspend fun clearServerInfo() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SERVER_ADDRESS)
            prefs.remove(KEY_SITE_NAME)
            prefs.remove(KEY_SERVER_VERSION)
            prefs.remove(KEY_STORAGE_TYPE)
            prefs.remove(KEY_LOGIN_TURNSTILE)
            prefs.remove(KEY_TURNSTILE_SITE_KEY)
        }
    }

    suspend fun markLegacyCookieMigrationDone() {
        dataStore.edit { it[KEY_LEGACY_COOKIE_MIGRATED] = true }
    }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setFluidSearch(enabled: Boolean) {
        dataStore.edit { it[KEY_FLUID_SEARCH] = enabled }
    }

    suspend fun setExactSearch(enabled: Boolean) {
        dataStore.edit { it[KEY_EXACT_SEARCH] = enabled }
    }

    suspend fun setAutoNext(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_NEXT] = enabled }
    }

    suspend fun setAutoPictureInPicture(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_PIP] = enabled }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { it[KEY_PLAYBACK_SPEED] = speed }
    }

    private companion object {
        val KEY_SERVER_ADDRESS = stringPreferencesKey("server_address")
        val KEY_SITE_NAME = stringPreferencesKey("site_name")
        val KEY_SERVER_VERSION = stringPreferencesKey("server_version")
        val KEY_STORAGE_TYPE = stringPreferencesKey("storage_type")
        val KEY_LOGIN_TURNSTILE = booleanPreferencesKey("login_require_turnstile")
        val KEY_TURNSTILE_SITE_KEY = stringPreferencesKey("turnstile_site_key")
        val KEY_LEGACY_COOKIE_MIGRATED = booleanPreferencesKey("legacy_cookie_migrated")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_FLUID_SEARCH = booleanPreferencesKey("fluid_search")
        val KEY_EXACT_SEARCH = booleanPreferencesKey("exact_search")
        val KEY_AUTO_NEXT = booleanPreferencesKey("auto_next")
        val KEY_AUTO_PIP = booleanPreferencesKey("auto_pip")
        val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
    }
}
