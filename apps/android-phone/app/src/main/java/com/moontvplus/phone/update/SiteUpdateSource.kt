package com.moontvplus.phone.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reads the small manifest served by the MoonTVPlus site. This is the primary update path for
 * users in mainland China who cannot reach api.github.com; the APK itself is served by the same
 * Cloudflare-hosted site.
 */
class SiteUpdateSource(
    private val baseUrl: String = UpdatePolicy.SITE_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build(),
    private val downloader: UpdateSource = GithubUpdateSource(client),
) : UpdateSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(currentVersion: String): ClientUpdate? = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/downloads/latest.json"
        if (!UpdatePolicy.siteUrl(url)) throw IOException("更新清单地址不可信。")
        val request = Request.Builder().url(url)
            .header("User-Agent", "MoonTVPlus-Android-Updater")
            .header("Accept", "application/json")
            .build()
        val manifest = client.newCall(request).execute().use { response ->
            if (response.code == 404) throw IOException("更新清单暂未发布。")
            if (!response.isSuccessful) throw IOException("更新清单请求失败（HTTP ${response.code}），请稍后重试。")
            val body = response.body ?: throw IOException("更新清单为空。")
            val text = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 262144) throw IOException("更新清单过大。")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            json.decodeFromString<SiteUpdateManifest>(text)
        }
        val installed = ClientVersion.parse(currentVersion)
            ?: throw IOException("当前客户端版本无法识别。")
        val candidate = ClientVersion.parse(manifest.version)
            ?: throw IOException("更新清单版本格式不正确。")
        if (candidate <= installed) return@withContext null
        if (manifest.apk.size !in 1..UpdatePolicy.MAX_APK_BYTES) throw IOException("更新包大小异常。")
        if (!UpdatePolicy.siteUrl(manifest.apk.url)) throw IOException("更新包地址不可信。")
        val hash = manifest.apk.sha256?.lowercase()
        if (hash != null && !hash.matches(Regex("[a-f0-9]{64}"))) throw IOException("更新包校验值格式不正确。")
        ClientUpdate(
            version = manifest.version,
            notes = manifest.notes,
            apk = ReleaseAsset(
                name = manifest.apk.name,
                url = manifest.apk.url,
                size = manifest.apk.size,
                digest = hash?.let { "sha256:$it" },
            ),
        )
    }

    override suspend fun download(update: ClientUpdate, destination: File, progress: (Long, Long) -> Unit): String =
        downloader.download(update, destination, progress)
}