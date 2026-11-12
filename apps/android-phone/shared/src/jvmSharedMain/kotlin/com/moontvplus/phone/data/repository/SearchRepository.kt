package com.moontvplus.phone.data.repository

import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.ApiUrlResolver
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.SearchHistoryRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response

/** 流式搜索事件，对应服务端 `data: {...}` 里的 `type`。 */
sealed interface SearchEvent {
    data class Started(val totalSources: Int) : SearchEvent
    data class SourceDone(
        val source: String,
        val sourceName: String,
        val results: List<SearchResult>,
    ) : SearchEvent
    data class SourceFailed(
        val source: String,
        val sourceName: String,
        val message: String,
    ) : SearchEvent
    data class Completed(val totalResults: Int, val completedSources: Int) : SearchEvent
    data class Failed(val message: String) : SearchEvent
}

class SearchRepository(
    private val api: MoonApi,
    private val json: Json,
    private val urls: ApiUrlResolver,
    private val sseClient: OkHttpClient,
) {

    /**
     * SSE 流式搜索。
     *
     * 用裸 OkHttp 而不是 Retrofit：需要按行读、随时中断，且不能有读超时。
     * 每帧只有 `data: {...}`，以空行结束 — 与网页端解析方式一致。
     */
    fun stream(keyword: String): Flow<SearchEvent> = callbackFlow {
        val url = urls.resolve("/api/search/ws", mapOf("q" to keyword))
        if (url == null) {
            trySend(SearchEvent.Failed("服务器地址无效"))
            close()
            return@callbackFlow
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity")
            .build()
        val call = sseClient.newCall(request)

        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        trySend(SearchEvent.Failed("搜索失败（HTTP ${response.code}）"))
                        return@use
                    }
                    val source = response.body?.source() ?: return@use
                    val buffer = StringBuilder()
                    while (isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            if (buffer.isNotEmpty()) {
                                parseFrame(buffer.toString())?.let { trySend(it) }
                                buffer.setLength(0)
                            }
                        } else if (line.startsWith("data:")) {
                            buffer.append(line.removePrefix("data:").trim())
                        }
                    }
                    if (buffer.isNotEmpty()) {
                        parseFrame(buffer.toString())?.let { trySend(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trySend(SearchEvent.Failed(e.message ?: "搜索连接中断"))
            } finally {
                close()
            }
        }

        awaitClose {
            call.cancel()
            job.cancel()
        }
    }

    private fun parseFrame(frame: String): SearchEvent? = runCatching {
        val obj = json.parseToJsonElement(frame).jsonObject
        when (val type = obj["type"]?.jsonPrimitive?.content) {
            "start" -> SearchEvent.Started(
                totalSources = obj["totalSources"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
            "source_result" -> SearchEvent.SourceDone(
                source = obj["source"]?.jsonPrimitive?.content.orEmpty(),
                sourceName = obj["sourceName"]?.jsonPrimitive?.content.orEmpty(),
                results = obj["results"]?.jsonArray
                    ?.mapNotNull { element ->
                        runCatching {
                            json.decodeFromJsonElement(SearchResult.serializer(), element)
                        }.getOrNull()
                    }
                    .orEmpty(),
            )
            "source_error" -> SearchEvent.SourceFailed(
                source = obj["source"]?.jsonPrimitive?.content.orEmpty(),
                sourceName = obj["sourceName"]?.jsonPrimitive?.content.orEmpty(),
                message = obj["error"]?.jsonPrimitive?.content ?: "搜索失败",
            )
            "complete" -> SearchEvent.Completed(
                totalResults = obj["totalResults"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                completedSources = obj["completedSources"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
            else -> {
                if (type == null) null else SearchEvent.Failed("未知事件：$type")
            }
        }
    }.getOrNull()

    /** 普通搜索：SSE 不可用时的回退，也可能在断流时补一次。 */
    suspend fun searchOnce(keyword: String): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val response: Response<com.moontvplus.phone.core.network.SearchResponse> =
                api.search(keyword)
            if (!response.isSuccessful) throw IllegalStateException("搜索失败（HTTP ${response.code()}）")
            response.body()?.results.orEmpty()
        }
    }

    suspend fun history(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.searchHistory()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code()}")
            response.body().orEmpty()
        }
    }

    suspend fun addHistory(keyword: String) {
        withContext(Dispatchers.IO) {
            runCatching { api.addSearchHistory(SearchHistoryRequest(keyword)) }
        }
    }

    suspend fun removeHistory(keyword: String) {
        withContext(Dispatchers.IO) {
            runCatching { api.removeSearchHistory(keyword) }
        }
    }
}
