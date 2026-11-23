package com.moontvplus.nshhost

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object AuthStore {
    const val UA = "MoonTVPlus App/1.9.16 (Android) moontvplus app"
    private const val ACCESS_TOKEN_AGE = 4 * 60 * 60 * 1000L
    private const val RENEWAL_THRESHOLD = 10 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile var server: String = ""
    @Volatile var token: String = ""
    @Volatile var onTokenChanged: ((String) -> Unit)? = null

    fun update(serverValue: String?, tokenValue: String?) {
        server = serverValue?.trim()?.trimEnd('/') ?: ""
        token = tokenValue ?: ""
    }

    fun clear() {
        token = ""
    }

    fun isSiteUrl(url: String): Boolean {
        return server.isNotEmpty() && url.startsWith(server)
    }

    fun authorization(): String? {
        return if (token.isNotEmpty()) "Bearer $token" else null
    }

    fun ensureFresh() {
        if (token.isBlank()) return
        val timestamp = tokenTimestamp(token) ?: return
        val remaining = ACCESS_TOKEN_AGE - (System.currentTimeMillis() - timestamp)
        if (remaining < RENEWAL_THRESHOLD) refreshNow()
    }

    @Synchronized
    fun refreshNow(): String? {
        if (server.isBlank() || token.isBlank()) return null
        val request = Request.Builder()
            .url(server + "/api/auth/refresh")
            .post("".toRequestBody("application/json".toMediaTypeOrNull()))
            .header("Authorization", "Bearer " + token)
            .header("User-Agent", UA)
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) return null
            val body = JSONObject(it.body?.string() ?: "")
            val next = body.optString("token")
            if (next.isBlank()) return null
            token = next
            onTokenChanged?.invoke(next)
            return next
        }
    }

    private fun tokenTimestamp(value: String): Long? {
        return try {
            val decoded = if (value.startsWith("{")) value else URLDecoder.decode(value, "UTF-8")
            JSONObject(decoded).optLong("timestamp").takeIf { it > 0 }
        } catch (error: Exception) {
            null
        }
    }
}
