package com.moontvplus.phone.di

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.moontvplus.phone.BuildConfig
import com.moontvplus.phone.core.platform.Platform
import com.moontvplus.phone.core.platform.PlatformInfo
import com.moontvplus.phone.core.platform.PlatformServices
import com.moontvplus.phone.data.local.AndroidLegacySessionBridge
import com.moontvplus.phone.data.local.AndroidSiteCookies
import com.moontvplus.phone.di.AppGraph
import java.io.File

/** Android 侧注入给共享逻辑层的运行时信息。 */
class AndroidPlatformInfo(context: Context) : PlatformInfo {

    private val appContext = context.applicationContext

    override val deviceLabel: String =
        "Android ${sanitize(Build.VERSION.RELEASE)}; ${sanitize(Build.MODEL)}"

    override val userAgent: String = "${BuildConfig.APP_UA} ($deviceLabel)"

    override val appDataDir: String = appContext.filesDir.absolutePath

    override val debug: Boolean = BuildConfig.DEBUG

    override val platformName: String = "Android"

    private fun sanitize(value: String?): String = value.orEmpty()
        .filter { it.code in 0x20..0x7e }
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifEmpty { "unknown" }
}

/**
 * Android 应用的依赖容器：包住跨平台 [AppGraph]，再补上 Android 专属的东西
 * （WebView cookie、旧壳迁移、Coil 图片加载器）。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val graph: AppGraph = AppGraph(
        platformServices = PlatformServices(
            cookies = AndroidSiteCookies(),
            legacy = AndroidLegacySessionBridge(appContext),
        ),
    )

    /** 海报加载器：复用带鉴权与 UA 的 client（图片代理需要登录态）。 */
    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .okHttpClient { graph.mediaClient }
        .crossfade(true)
        .memoryCache {
            MemoryCache.Builder(appContext)
                .maxSizePercent(0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(appContext.cacheDir, "image_cache"))
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .build()

    /** 启动时把设置读进内存；界面出现之前调用。 */
    suspend fun initialize() {
        Platform.init(AndroidPlatformInfo(appContext))
        graph.initialize()
    }
}
