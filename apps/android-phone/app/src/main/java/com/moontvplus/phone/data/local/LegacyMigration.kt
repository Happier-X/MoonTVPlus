package com.moontvplus.phone.data.local

import android.content.Context
import com.moontvplus.phone.core.datastore.SettingsStore
import com.moontvplus.phone.core.model.AuthInfo
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.network.ServerAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 从旧的 WebView 壳（包名相同、直接加载网页）升级上来时的兼容处理：
 *
 * - 旧壳把地址存在 `SharedPreferences("moontv_phone").server_url`
 * - 旧壳的登录态是站点 cookie（`auth=<encodeURIComponent(JSON)>`），
 *   取值与 `/api/login` 返回的 token 同构，可以直接当 Bearer 用
 *
 * 目的：覆盖安装后不用重新填地址、重新登录。cookie 只尝试一次，
 * 否则「退出登录」之后又会被旧 cookie 顶回来。
 */
@Singleton
class LegacyMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val cookies: SiteCookieStore,
    private val json: Json,
) {

    /** 旧壳里配过的服务器地址；当前没有地址时才写入。 */
    suspend fun migrateServerAddress(): Boolean = withContext(Dispatchers.IO) {
        if (!settings.snapshot().serverAddress.isNullOrBlank()) return@withContext false
        val legacy = legacyServerUrl() ?: return@withContext false
        val address = ServerAddress.parseStored(legacy) ?: return@withContext false
        settings.saveServer(
            address = address.toStorage(),
            siteName = null,
            serverVersion = null,
            storageType = null,
        )
        true
    }

    /** 从站点 cookie 还原登录态；只在升级后第一次启动尝试。 */
    suspend fun restoreSessionOnce(address: ServerAddress): AuthSession? =
        withContext(Dispatchers.IO) {
            if (settings.snapshot().legacyCookieMigrationDone) return@withContext null
            settings.markLegacyCookieMigrationDone()

            val rawValue = cookies.readAuth(address.origin) ?: return@withContext null
            val auth = decodeAuth(rawValue) ?: return@withContext null
            if (auth.role.isNullOrBlank()) return@withContext null

            AuthSession(
                token = rawValue,
                auth = auth,
                storageType = settings.snapshot().storageType,
                savedAt = System.currentTimeMillis(),
            )
        }

    private fun legacyServerUrl(): String? = runCatching {
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun decodeAuth(rawValue: String): AuthInfo? {
        val decoded = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrNull()
        for (candidate in listOfNotNull(decoded, rawValue)) {
            val parsed = runCatching {
                json.decodeFromString(AuthInfo.serializer(), candidate)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private companion object {
        const val LEGACY_PREFS = "moontv_phone"
        const val KEY_SERVER_URL = "server_url"
    }
}
