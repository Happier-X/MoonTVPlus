package com.moontvplus.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * 海报/封面加载。
 *
 * Android 走 Coil（会被 `ImageLoaderFactory` 换成带鉴权的 ImageLoader），
 * 桌面端走共享 OkHttp + skiko 解码。调用方只需要给 URL。
 */
@Composable
expect fun NetworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
)
