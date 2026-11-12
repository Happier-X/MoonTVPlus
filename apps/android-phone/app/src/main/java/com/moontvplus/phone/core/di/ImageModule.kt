package com.moontvplus.phone.core.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.moontvplus.phone.core.network.ServerProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    /**
     * 海报加载器：复用带鉴权与 UA 的共享 client（图片代理需要登录态）。
     *
     * 磁盘缓存按站点地址分目录，换服务器不会串图。
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @MediaClient client: OkHttpClient,
        serverProvider: ServerProvider,
    ): ImageLoader {
        val cacheDirName = serverProvider.current()?.base?.host ?: "default"
        return ImageLoader.Builder(context)
            .okHttpClient { client }
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "image_cache/$cacheDirName"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
