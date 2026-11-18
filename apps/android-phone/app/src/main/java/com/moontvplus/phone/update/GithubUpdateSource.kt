package com.moontvplus.phone.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Own unauthenticated client: never inherits site cookies, GitHub tokens or auth interceptors. */
class GithubUpdateSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersion: String): ClientUpdate? {
        val releases = mutableListOf<GithubRelease>()
        // Exhaust the public release list; /latest can point at a website-only release.
        for (page in 1..20) {
            val url = "https://api.github.com/repos/${UpdatePolicy.REPOSITORY}/releases?per_page=100&page=$page"
            val hasNext = response(url, api = true) { result ->
                releases += json.decodeFromString<List<GithubRelease>>(boundedText(result, 8 * 1024 * 1024))
                result.header("Link").orEmpty().contains("rel=\"next\"")
            }
            if (!hasNext) return UpdatePolicy.select(releases, currentVersion)
        }
        throw IOException("发布记录过多，暂时无法完整检查，请稍后重试或查看 GitHub 发布页。")
    }

    suspend fun download(update: ClientUpdate, destination: File, progress: (Long, Long) -> Unit): String {
        val sums = update.sums?.let { asset ->
            response(asset.url) { boundedText(it, 262144) }
        }
        val expectedHash = UpdatePolicy.checksum(update, sums)
        return response(update.apk.url) { result ->
            val body = result.body ?: throw IOException("下载内容为空。")
            val declaredSize = body.contentLength()
            if (declaredSize >= 0 && declaredSize != update.apk.size) throw IOException("下载文件大小与发布信息不一致。")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastProgress = 0L
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > update.apk.size) throw IOException("下载文件超出预期大小。")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val now = System.nanoTime()
                        if (now - lastProgress > 150_000_000L) {
                            progress(received, update.apk.size)
                            lastProgress = now
                        }
                    }
                }
            }
            if (received != update.apk.size) throw IOException("下载未完成，请重试。")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedHash != null && actual != expectedHash) throw IOException("安装包 SHA-256 校验失败，请重新下载。")
            progress(received, update.apk.size)
            actual
        }
    }

    private fun boundedText(response: Response, limit: Int): String {
        val body = response.body ?: throw IOException("服务器没有返回内容。")
        return body.byteStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw IOException("服务器返回内容过大。")
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }

    private sealed interface Exchange<out T> {
        data class Redirect(val url: String) : Exchange<Nothing>
        data class Done<T>(val value: T) : Exchange<T>
    }

    private suspend fun <T> response(url: String, api: Boolean = false, block: suspend (Response) -> T): T =
        withContext(Dispatchers.IO) {
            var current = url
            repeat(6) {
                if (api) {
                    if (!current.startsWith("https://api.github.com/repos/${UpdatePolicy.REPOSITORY}/releases?")) {
                        throw IOException("更新接口地址不可信。")
                    }
                } else if (!UpdatePolicy.redirectUrl(current)) throw IOException("下载地址不是可信的 GitHub HTTPS 附件。")
                val request = Request.Builder().url(current)
                    .header("User-Agent", "MoonTVPlus-Android-Updater")
                    .header("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                    .build()
                val call = client.newCall(request)
                // Cancel a blocking execute/read immediately when the owning operation is cancelled.
                val redirect = coroutineScope {
                    val watcher = launch(Dispatchers.Default) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        call.execute().use { result ->
                            if (result.code in listOf(301, 302, 303, 307, 308)) {
                                if (api) throw IOException("GitHub 更新接口异常，请稍后重试。")
                                val location = result.header("Location") ?: throw IOException("下载重定向缺少地址。")
                                return@coroutineScope Exchange.Redirect(result.request.url.resolve(location)?.toString()
                                    ?: throw IOException("下载重定向地址无效。"))
                            }
                            if (result.code == 403 || result.code == 429) {
                                throw IOException("GitHub 暂时限制了访问频率，请稍后再检查更新。")
                            }
                            if (!result.isSuccessful) throw IOException("GitHub 请求失败（HTTP ${result.code}），请稍后重试。")
                            Exchange.Done(block(result))
                        }
                    } finally {
                        watcher.cancelAndJoin()
                    }
                }
                when (redirect) {
                    is Exchange.Redirect -> current = redirect.url
                    is Exchange.Done -> return@withContext redirect.value
                }
            }
            throw IOException("下载重定向次数过多。")
        }
}
