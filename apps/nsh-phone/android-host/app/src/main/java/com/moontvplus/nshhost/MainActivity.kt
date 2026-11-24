package com.moontvplus.nshhost

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.longbridge.nsh.NshView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var nsh: NshView
    private lateinit var updates: UpdateManager

    private val streams = ConcurrentHashMap<String, Call>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        nsh = NshView(this)
        nsh.setErrorListener { message -> Log.e("nsh-host", message) }
        nsh.setStorageDirectory(File(filesDir, "nsh/session"))
        updates = UpdateManager(this, nsh, http)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        nsh.setParams(mapOf(
            "versionName" to (packageInfo.versionName ?: ""),
            "versionCode" to PackageInfoCompat.getLongVersionCode(packageInfo)
        ))
        nsh.registerComponent("moontvplus.player") { context -> ExoPlayerComponent(context) }
        nsh.handle("http") { data, reply -> handleHttp(data, reply) }
        nsh.handle("http.stream") { data, reply -> handleStream(data, reply) }
        nsh.handle("http.cancel") { data, reply ->
            val id = (data as? Map<*, *>)?.get("id") as? String
            if (id != null) streams.remove(id)?.cancel()
            reply.resolve(true)
        }
        nsh.handle("auth.token") { data, reply ->
            val map = data as? Map<*, *>
            AuthStore.update(map?.get("server") as? String, map?.get("token") as? String)
            reply.resolve(true)
        }
        nsh.handle("auth.clear") { _, reply ->
            AuthStore.clear()
            reply.resolve(true)
        }
        nsh.handle("device.brightness") { data, reply -> handleBrightness(data, reply) }
        nsh.handle("device.volume") { data, reply -> handleVolume(data, reply) }
        nsh.handle("update.download") { data, reply -> updates.download(data, reply) }
        nsh.handle("update.cancel") { data, reply -> updates.cancel(data, reply) }
        nsh.handle("update.install") { data, reply -> updates.install(data, reply) }

        AuthStore.onTokenChanged = { token ->
            runOnUiThread { nsh.send("session.token", mapOf("token" to token)) }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        setPictureInPictureParams(pipParams(false))
        setContentView(nsh)
        if (!nsh.loadAssetBundle("nsh")) {
            Log.e("nsh-host", "could not load assets/nsh/bundle.nshc")
        }
    }

    private fun handleBrightness(data: Any?, reply: com.longbridge.nsh.NshHostReply) {
        val value = ((data as? Map<*, *>)?.get("value") as? Number)?.toFloat()
        if (value == null) {
            val current = window.attributes.screenBrightness
            val resolved = if (current >= 0f) current else
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            reply.resolve(mapOf("value" to resolved))
            return
        }
        val attrs = window.attributes
        attrs.screenBrightness = value.coerceIn(0.01f, 1f)
        window.attributes = attrs
        reply.resolve(mapOf("value" to value))
    }

    private fun handleVolume(data: Any?, reply: com.longbridge.nsh.NshHostReply) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val value = ((data as? Map<*, *>)?.get("value") as? Number)?.toFloat()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (value == null) {
            reply.resolve(mapOf("value" to audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max))
            return
        }
        val level = (value.coerceIn(0f, 1f) * max).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        reply.resolve(mapOf("value" to level.toFloat() / max))
    }

    private fun handleHttp(data: Any?, reply: com.longbridge.nsh.NshHostReply) {
        val map = data as? Map<*, *>
        val url = map?.get("url") as? String
        if (url.isNullOrBlank()) {
            reply.reject("missing url")
            return
        }
        val method = (map?.get("method") as? String ?: "GET").uppercase()
        val bodyText = map?.get("body") as? String
        val headers = map?.get("headers") as? Map<*, *>

        val builder = Request.Builder().url(url)
        headers?.forEach { (key, value) ->
            if (key != null && value != null) builder.header(key.toString(), value.toString())
        }
        val mediaType = (headers?.get("Content-Type") as? String ?: "application/json").toMediaTypeOrNull()
        val body = when {
            bodyText != null -> bodyText.toRequestBody(mediaType)
            method == "POST" || method == "PUT" || method == "PATCH" -> "".toRequestBody(mediaType)
            else -> null
        }
        builder.method(method, body)

        http.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                reply.reject(error.message ?: "network error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string() ?: ""
                    reply.resolve(
                        mapOf(
                            "status" to it.code,
                            "body" to text,
                            "contentType" to (it.header("Content-Type") ?: "")
                        )
                    )
                }
            }
        })
    }

    private fun handleStream(data: Any?, reply: com.longbridge.nsh.NshHostReply) {
        val map = data as? Map<*, *>
        val id = map?.get("id") as? String
        val url = map?.get("url") as? String
        if (id.isNullOrBlank() || url.isNullOrBlank()) {
            reply.reject("missing id or url")
            return
        }
        val headers = map?.get("headers") as? Map<*, *>
        val builder = Request.Builder().url(url).get()
        headers?.forEach { (key, value) ->
            if (key != null && value != null) builder.header(key.toString(), value.toString())
        }
        val call = http.newCall(builder.build())
        streams[id] = call
        Log.d("nsh-host", "stream start " + id + " " + url)
        reply.resolve(mapOf("id" to id))

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                streams.remove(id)
                nsh.send("http.stream", mapOf("id" to id, "done" to true, "error" to (error.message ?: "stream error")))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        Log.d("nsh-host", "stream response " + it.code + " " + (it.header("Content-Type") ?: ""))
                        val body = it.body
                        if (body == null) {
                            nsh.send("http.stream", mapOf("id" to id, "done" to true, "error" to "empty body"))
                            return
                        }
                        val source = body.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data:")) {
                                Log.d("nsh-host", "stream data " + line.length)
                                nsh.send(
                                    "http.stream",
                                    mapOf(
                                        "id" to id,
                                        "data" to line.removePrefix("data:").trim()
                                    )
                                )
                            }
                        }
                        Log.d("nsh-host", "stream done " + id)
                        nsh.send("http.stream", mapOf("id" to id, "done" to true))
                    }
                } catch (error: Exception) {
                    nsh.send("http.stream", mapOf("id" to id, "done" to true, "error" to (error.message ?: "stream error")))
                } finally {
                    streams.remove(id)
                }
            }
        })
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    fun setPipAutoEnter(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        setPictureInPictureParams(pipParams(enabled))
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val entered = enterPictureInPictureMode(pipParams(false))
        android.util.Log.d("nsh-host", "pip entered=" + entered)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        nsh.send("pip", mapOf("active" to isInPictureInPictureMode))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updates.onActivityResult(requestCode, resultCode)
    }

    override fun onDestroy() {
        PlaybackService.stop(this)
        nsh.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!nsh.handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

}
