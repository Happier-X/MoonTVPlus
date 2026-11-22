package com.moontvplus.phone.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the same APK from all known mirrors and keeps the one that probes fastest.
 * A failed full download automatically falls back to the next mirror, so a slow or stale
 * mirror can never block an update when the other line is healthy.
 */
class MirrorDownloader(
    private val downloader: UpdateSource = GithubUpdateSource(),
    private val probe: suspend (String) -> Long = { url -> probeEndpoint(url) },
) {
    suspend fun download(update: ClientUpdate, destination: File, progress: (Long, Long) -> Unit): String {
        val candidates = candidates(update)
        if (candidates.isEmpty()) throw IOException("没有可用的更新下载线路。")

        val measured = coroutineScope {
            candidates.map { asset ->
                async(Dispatchers.IO) {
                    val score = try {
                        probe(asset.url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        0L
                    }
                    asset to score
                }
            }.awaitAll()
        }
        val ordered = measured.sortedByDescending { it.second }.map { it.first }
        val attempts = if (measured.any { it.second > 0L }) ordered else candidates

        var lastError: Exception? = null
        for (asset in attempts) {
            val candidate = update.copy(
                apk = asset,
                sums = if (asset.url == update.apk.url) update.sums else null,
            )
            try {
                withContext(Dispatchers.IO) { destination.delete() }
                progress(0, asset.size)
                return downloader.download(candidate, destination, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("更新下载失败，请稍后重试。")
    }

    private fun candidates(update: ClientUpdate): List<ReleaseAsset> =
        (listOf(update.apk) + update.mirrors)
            .filter { UpdatePolicy.downloadUrl(it.url) }
            .distinctBy { it.url }
}

private val probeClient = OkHttpClient.Builder()
    .followRedirects(true).followSslRedirects(true)
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .callTimeout(3, TimeUnit.SECONDS)
    .build()

private suspend fun probeEndpoint(url: String): Long = withContext(Dispatchers.IO) {
    if (!UpdatePolicy.downloadUrl(url)) return@withContext 0L
    val request = Request.Builder().url(url)
        .header("Range", "bytes=0-65535")
        .header("Cache-Control", "no-cache")
        .header("User-Agent", "MoonTVPlus-Android-Updater")
        .build()
    val started = System.nanoTime()
    try {
        probeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext 0L
            val finalUrl = response.request.url.toString()
            if (!UpdatePolicy.siteUrl(finalUrl) && !UpdatePolicy.redirectUrl(finalUrl)) {
                return@withContext 0L
            }
            val body = response.body ?: return@withContext 0L
            var received = 0
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (received < 65536) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 65536 - received))
                    if (count < 0) break
                    received += count
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            if (received <= 0) 0L else received.toLong() * 1000L / elapsedMs.coerceAtLeast(1L)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        0L
    }
}