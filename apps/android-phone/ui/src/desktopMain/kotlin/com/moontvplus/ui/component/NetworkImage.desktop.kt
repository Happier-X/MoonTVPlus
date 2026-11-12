package com.moontvplus.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 桌面端的图片加载器。
 *
 * 桌面没有 Coil，海报直接走共享 OkHttp（会带上 Authorization），
 * 用 skiko 解码成 Compose 位图；解码结果按 URL 缓存在进程里。
 */
object DesktopImageLoader {
    /** 由桌面入口注入 `graph.mediaClient`。 */
    var client: OkHttpClient? = null

    private val cache = LinkedHashMap<String, ImageBitmap>(256, 0.75f, true)

    fun get(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    fun put(url: String, bitmap: ImageBitmap) = synchronized(cache) {
        cache[url] = bitmap
        if (cache.size > 400) {
            val oldest = cache.keys.firstOrNull()
            if (oldest != null) cache.remove(oldest)
        }
    }
}

@Composable
actual fun NetworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    var bitmap by remember(url) { mutableStateOf(url?.let { DesktopImageLoader.get(it) }) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank() || bitmap != null) return@LaunchedEffect
        val client = DesktopImageLoader.client ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }
            }.getOrNull()?.also { DesktopImageLoader.put(url, it) }
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
