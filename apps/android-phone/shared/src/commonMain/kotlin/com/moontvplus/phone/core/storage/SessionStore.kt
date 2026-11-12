package com.moontvplus.phone.core.storage

import com.moontvplus.phone.core.model.AuthSession
import kotlinx.serialization.json.Json

/** 登录会话的本地持久化（单个 JSON 文件，含 token 与用户信息）。 */
class SessionStore(
    private val store: TextFileStore,
    private val json: Json,
) {

    suspend fun read(): AuthSession? {
        val raw = store.read(FILE) ?: return null
        return runCatching { json.decodeFromString(AuthSession.serializer(), raw) }.getOrNull()
    }

    suspend fun write(session: AuthSession) {
        runCatching {
            store.write(FILE, json.encodeToString(AuthSession.serializer(), session))
        }
    }

    suspend fun clear() {
        store.delete(FILE)
    }

    private companion object {
        const val FILE = "session.json"
    }
}
