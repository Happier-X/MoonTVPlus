package com.moontvplus.phone

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.moontvplus.phone.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MoonTvApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /** 应用级协程作用域：初始化这类不该被界面销毁打断的活儿放这儿。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Coil 的单例加载器：带鉴权与 UA（海报走站点的 /api/image-proxy）。 */
    override fun newImageLoader(): ImageLoader = container.imageLoader
}
