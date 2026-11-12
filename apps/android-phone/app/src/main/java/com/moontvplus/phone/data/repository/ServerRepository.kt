package com.moontvplus.phone.data.repository

import com.moontvplus.phone.BuildConfig
import com.moontvplus.phone.core.datastore.SettingsStore
import com.moontvplus.phone.core.model.ServerConfig
import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.phone.core.network.ServerProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ServerProbeResult {
    data class Success(val address: ServerAddress, val config: ServerConfig) : ServerProbeResult
    data class Failed(val message: String) : ServerProbeResult
}

@Singleton
class ServerRepository @Inject constructor(
    private val settings: SettingsStore,
    private val serverProvider: ServerProvider,
    private val json: Json,
) {

    /** 探测专用：不使用地址重写拦截器，直接请求候选地址。 */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun probe(raw: String): ServerProbeResult = withContext(Dispatchers.IO) {
        val candidates = ServerAddress.candidates(raw)
        if (candidates.isEmpty()) {
            return@withContext ServerProbeResult.Failed("地址格式不正确，示例：https://tv.example.com")
        }

        var lastError = "无法连接到服务器"
        for (candidate in candidates) {
            val url = candidate.base.newBuilder()
                .addPathSegments("api/server-config")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BuildConfig.APP_UA)
                .header("Accept", "application/json")
                .build()

            try {
                probeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "服务器返回 HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    val config = runCatching {
                        json.decodeFromString(ServerConfig.serializer(), body)
                    }.getOrNull()
                    // 有站点名/版本/存储类型中的任意一个，才认为是 MoonTVPlus 站点
                    if (config == null ||
                        (config.SiteName.isNullOrBlank() &&
                            config.Version.isNullOrBlank() &&
                            config.StorageType.isNullOrBlank())
                    ) {
                        lastError = "该地址不是 MoonTVPlus 站点"
                        return@use
                    }
                    return@withContext ServerProbeResult.Success(candidate, config)
                }
            } catch (e: IOException) {
                lastError = "无法连接到服务器，请检查地址与网络"
            } catch (e: Exception) {
                lastError = e.message ?: "探测失败"
            }
        }

        ServerProbeResult.Failed(lastError)
    }

    suspend fun save(address: ServerAddress, config: ServerConfig?) {
        settings.saveServer(
            address = address.toStorage(),
            siteName = config?.SiteName,
            serverVersion = config?.Version,
            storageType = config?.StorageType,
            loginRequireTurnstile = config?.LoginRequireTurnstile == true,
            turnstileSiteKey = config?.TurnstileSiteKey?.takeIf { it.isNotBlank() },
        )
        serverProvider.set(address)
    }

    /** 地址变更后重新同步内存里的当前地址。 */
    suspend fun syncFromSettings() {
        val stored = settings.snapshot().serverAddress
        serverProvider.set(ServerAddress.parseStored(stored))
    }

    suspend fun clear() {
        settings.clearServerInfo()
        serverProvider.set(null)
    }
}
