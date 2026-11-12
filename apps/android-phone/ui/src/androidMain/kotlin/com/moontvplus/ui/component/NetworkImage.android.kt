package com.moontvplus.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Android 走 Coil。`ImageLoaderFactory` 在 `MoonTvApplication` 里把 ImageLoader
 * 换成了带鉴权、带磁盘缓存的实例，所以这里直接用默认的 `LocalImageLoader`。
 */
@Composable
actual fun NetworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
