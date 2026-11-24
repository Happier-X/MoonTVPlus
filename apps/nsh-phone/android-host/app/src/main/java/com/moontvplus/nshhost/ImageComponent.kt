package com.moontvplus.nshhost

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.longbridge.nsh.NshComponent
import com.longbridge.nsh.NshComponentContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class ImageComponent(
    private val nsh: NshComponentContext
) : NshComponent {
    private val context = nsh.context
    private val root = FrameLayout(context)
    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private var url: String? = null
    private var radius = 0f
    private var aspectRatio = 0f
    private var placeholder = Color.parseColor("#1C1C22")
    private var fallback = Color.parseColor("#2A2A33")
    private var lastRequest: ImageRequest? = null

    private val loader: ImageLoader by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                if (AuthStore.isSiteUrl(original.url.toString())) {
                    AuthStore.ensureFresh()
                    builder.header("User-Agent", AuthStore.UA)
                    AuthStore.authorization()?.let { builder.header("Authorization", it) }
                } else {
                    builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                }
                chain.proceed(builder.build())
            }
            .build()
        ImageLoader.Builder(context)
            .okHttpClient(client)
            .memoryCache {
                MemoryCache.Builder(context).maxSizePercent(0.2).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    init {
        root.addView(imageView)
        root.setBackgroundColor(placeholder)
        imageView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val node = view as ImageView
                if (radius <= 0f) {
                    outline.setRect(0, 0, node.width, node.height)
                } else {
                    outline.setRoundRect(0, 0, node.width, node.height, radius)
                }
            }
        }
        imageView.clipToOutline = true
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyAspect()
        }
    }

    override fun view(): View = root

    override fun setProp(name: String, value: Any?) {
        root.post {
            when (name) {
                "url" -> {
                    url = value as? String
                    load()
                }
                "radius" -> {
                    radius = (value as? Number)?.toFloat() ?: 0f
                    imageView.invalidateOutline()
                }
                "aspectRatio" -> {
                    aspectRatio = (value as? Number)?.toFloat() ?: 0f
                    applyAspect()
                }
                "placeholderColor" -> {
                    placeholder = parseColor(value as? String, placeholder)
                    if (imageView.drawable == null) root.setBackgroundColor(placeholder)
                }
                "errorColor" -> {
                    fallback = parseColor(value as? String, fallback)
                }
            }
        }
    }

    private fun applyAspect() {
        if (aspectRatio <= 0f || root.width <= 0) return
        val height = (root.width / aspectRatio).toInt()
        if (imageView.layoutParams.height != height) {
            imageView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
        }
    }

    private fun parseColor(value: String?, default: Int): Int {
        if (value.isNullOrBlank()) return default
        return try {
            Color.parseColor(value.trim())
        } catch (error: IllegalArgumentException) {
            default
        }
    }

    private fun load() {
        val target = url
        root.setBackgroundColor(placeholder)
        imageView.setImageDrawable(null)
        if (target.isNullOrBlank()) {
            root.setBackgroundColor(fallback)
            return
        }
        val request = ImageRequest.Builder(context)
            .data(target)
            .target(object : coil.target.Target {
                override fun onStart(placeholderDrawable: android.graphics.drawable.Drawable?) {
                    imageView.setImageDrawable(placeholderDrawable)
                }

                override fun onError(error: android.graphics.drawable.Drawable?) {
                    imageView.setImageDrawable(null)
                    root.setBackgroundColor(fallback)
                }

                override fun onSuccess(result: android.graphics.drawable.Drawable) {
                    imageView.setImageDrawable(result)
                }
            })
            .build()
        lastRequest = request
        loader.enqueue(request)
    }
}
