package com.moontvplus.phone.subtitles

import com.moontvplus.phone.core.storage.TextFileStore
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 外挂字幕加载：HTTP 下载 + 本地缓存 + 解析。
 *
 * 网络字幕经常是 GBK（老字幕组），直接用 UTF-8 解码会得到一堆替换字符；
 * 这里先严格解 UTF-8，失败再退回 GBK/GB18030。
 */
class SubtitleRepository(
    private val client: OkHttpClient,
    private val store: TextFileStore,
    private val userAgent: String = "MoonTVPlus Player",
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun load(url: String, forceRefresh: Boolean = false): Result<SubtitleDocument> = withContext(ioDispatcher) {
        val cacheKey = "subtitles/${url.hashCode().toUInt().toString(16)}.txt"
        if (!forceRefresh) {
            val cached = store.read(cacheKey)
            if (!cached.isNullOrBlank()) {
                val parsed = SubtitleParser.parse(cached, url)
                if (!parsed.empty) return@withContext Result.success(parsed)
            }
        }
        val text = runCatching { download(url) }.getOrElse { return@withContext Result.failure(it) }
        val document = SubtitleParser.parse(text, url)
        if (document.empty) {
            return@withContext Result.failure(SubtitleException("字幕内容为空或格式不支持"))
        }
        runCatching { store.write(cacheKey, text) }
        Result.success(document)
    }

    private fun download(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SubtitleException("字幕下载失败（HTTP ${response.code}）")
            val bytes = response.body?.bytes() ?: throw SubtitleException("字幕内容为空")
            if (bytes.size > MAX_BYTES) throw SubtitleException("字幕文件过大")
            return decode(bytes)
        }
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching { String(bytes, Charset.forName("GB18030")) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
    }

    class SubtitleException(message: String) : Exception(message)

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
