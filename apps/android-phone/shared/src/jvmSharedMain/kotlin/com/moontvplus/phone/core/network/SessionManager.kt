package com.moontvplus.phone.core.network

import com.moontvplus.phone.core.storage.SessionStore
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.model.RefreshResponse
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 会话与 token 刷新。
 *
 * 关键约束（来自服务端实现）：
 * - `token` 本身就是 URL 编码过的 JSON，直接放进 `Authorization: Bearer`，不要二次编码。
 * - access token 4 小时过期，401 时用 `POST /api/auth/refresh` 换新。
 * - `StorageType=localstorage` 的站点不校验时间戳，也不支持 header 刷新，直接跳过。
 */
class SessionManager(
    private val store: SessionStore,
    private val urls: ApiUrlResolver,
    private val json: Json,
    private val refreshClient: OkHttpClient,
) {

    private val _session = MutableStateFlow<AuthSession?>(null)
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    private val refreshMutex = Mutex()

    suspend fun load() {
        _session.value = store.read()
    }

    fun currentToken(): String? = _session.value?.token

    suspend fun save(session: AuthSession) {
        _session.value = session
        store.write(session)
    }

    suspend fun clear() {
        _session.value = null
        store.clear()
    }

    /**
     * 刷新 token。多个请求同时 401 时只会真正发一次刷新请求：
     * 拿到锁后发现 token 已被别的线程换掉，直接返回新 token。
     */
    suspend fun refreshIfNeeded(staleToken: String?): String? = refreshMutex.withLock {
        val current = _session.value ?: return@withLock null
        if (current.isLocalStorageMode) return@withLock current.token
        if (!staleToken.isNullOrBlank() && current.token != staleToken) {
            return@withLock current.token
        }

        when (val result = doRefresh(current)) {
            is RefreshResult.Success -> {
                save(result.session)
                result.session.token
            }
            RefreshResult.Invalid -> {
                clear()
                null
            }
            // 网络抖动不清登录态，交给调用方按失败处理
            RefreshResult.Transient -> null
        }
    }

    /**
     * 启动时检查：快过期就主动刷新，避免首页并发请求一起 401。
     * 返回 false 表示会话已失效，需要重新登录。
     */
    suspend fun ensureFresh(): Boolean {
        val current = _session.value ?: return false
        if (current.isLocalStorageMode) return true

        val refreshExpires = current.auth.refreshExpires
        if (refreshExpires != null && refreshExpires <= System.currentTimeMillis()) {
            clear()
            return false
        }

        val issuedAt = current.auth.timestamp ?: 0L
        val age = System.currentTimeMillis() - issuedAt
        if (issuedAt > 0L && age < ACCESS_TOKEN_TTL_MS - REFRESH_AHEAD_MS) {
            return true
        }

        return when (val result = doRefresh(current)) {
            is RefreshResult.Success -> {
                save(result.session)
                true
            }
            RefreshResult.Invalid -> {
                clear()
                false
            }
            // 网络不可用时先放行，真的 401 再走 Authenticator
            RefreshResult.Transient -> true
        }
    }

    private suspend fun doRefresh(session: AuthSession): RefreshResult {
        val url = urls.resolve("/api/auth/refresh") ?: return RefreshResult.Transient
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer ${session.token}")
            .build()

        // 同步 execute() 必须离开主线程，否则触发 NetworkOnMainThreadException
        return withContext(Dispatchers.IO) {
            try {
                refreshClient.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        return@use RefreshResult.Invalid
                    }
                    if (!response.isSuccessful) {
                        return@use RefreshResult.Transient
                    }
                    val body = response.body?.string().orEmpty()
                    val parsed = runCatching {
                        json.decodeFromString(RefreshResponse.serializer(), body)
                    }.getOrNull()

                    val token = parsed?.token
                    val auth = parsed?.auth
                    if (token.isNullOrBlank() || auth == null) {
                        RefreshResult.Transient
                    } else {
                        RefreshResult.Success(
                            session.copy(
                                token = token,
                                auth = auth,
                                savedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            } catch (e: IOException) {
                RefreshResult.Transient
            }
        }
    }

    private sealed interface RefreshResult {
        data class Success(val session: AuthSession) : RefreshResult
        data object Invalid : RefreshResult
        data object Transient : RefreshResult
    }

    private companion object {
        const val ACCESS_TOKEN_TTL_MS = 4L * 60 * 60 * 1000
        const val REFRESH_AHEAD_MS = 10L * 60 * 1000
    }
}
