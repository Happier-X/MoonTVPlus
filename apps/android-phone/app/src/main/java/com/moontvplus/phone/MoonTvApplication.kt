package com.moontvplus.phone

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MoonTvApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    /** Coil 的单例加载器：带鉴权与 UA（海报走站点的 /api/image-proxy）。 */
    override fun newImageLoader(): ImageLoader = imageLoader
}
