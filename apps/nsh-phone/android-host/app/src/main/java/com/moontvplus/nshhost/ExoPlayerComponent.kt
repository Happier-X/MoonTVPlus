package com.moontvplus.nshhost

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.longbridge.nsh.NshComponent
import com.longbridge.nsh.NshComponentContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.IOException

class ExoPlayerComponent(
    private val nsh: NshComponentContext
) : NshComponent {
    private val context = nsh.context
    private val root = FrameLayout(context).apply {
        setBackgroundColor(0xFF000000.toInt())
    }
    private val playerView = PlayerView(context).apply {
        useController = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    private val danmakuView = DanmakuView(context)
    private val handler = Handler(Looper.getMainLooper())

    private var url: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var pendingSeekMs: Long = 0L
    private var paused = false
    private var liveMode = false
    private var rate = 1.0
    private var subtitleUrl: String? = null
    private var subtitleSize = 18f
    private var subtitleOutline = true
    private var lastState: String? = null
    private var retries = 0
    private var subtitleVisible = true
    private var externalCues: List<SubtitleCue> = emptyList()
    private var tickCount = 0
    private val subtitleClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val subtitleOverlay = TextView(context).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(16, 4, 16, 4)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { bottomMargin = 36 }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().also { exo ->
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        retries = 0
                        if (pendingSeekMs > 0) {
                            exo.seekTo(pendingSeekMs)
                            pendingSeekMs = 0
                        }
                        nsh.emit("ready", mapOf("duration" to exo.duration))
                        emitState()
                        emitTracks()
                    }
                    Player.STATE_BUFFERING -> nsh.emit("state", mapOf("state" to "buffering"))
                    Player.STATE_ENDED -> nsh.emit("state", mapOf("state" to "ended"))
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                emitState()
                val activity = context as? Activity
                if (isPlaying) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onTracksChanged(tracks: Tracks) {
                emitTracks()
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (code == 401 || error.message?.contains("AUTH_REFRESH_FAILED") == true ||
                    cause?.message?.contains("AUTH_REFRESH_FAILED") == true
                ) {
                    nsh.emit("authExpired", mapOf("message" to "登录已过期，请重新登录"))
                } else {
                    nsh.emit("error", mapOf("message" to (error.message ?: "player error")))
                    if (retries < 3) {
                        retries++
                        handler.postDelayed({ retryAtCurrentPosition() }, 1500L)
                    }
                }
            }
        })
    }

    private val tick = object : Runnable {
        override fun run() {
            tickCount++
            updateExternalSubtitle()
            if (tickCount % 4 == 0) PlaybackService.refresh()
            if (player.mediaItemCount > 0 && tickCount % 2 == 0) {
                nsh.emit(
                    "progress",
                    mapOf(
                        "position" to player.currentPosition,
                        "duration" to player.duration,
                        "rate" to rate
                    )
                )
                emitState()
            }
            handler.postDelayed(this, 250L)
        }
    }

    init {
        PlayerBridge.attach(player)
        root.addView(playerView)
        root.addView(danmakuView)
        root.addView(subtitleOverlay)
        applySubtitleStyle()
        handler.postDelayed(tick, 250L)
    }

    override fun view(): View = root

    override fun setProp(name: String, value: Any?) {
        root.post {
            when (name) {
                "url" -> {
                    url = value as? String
                    load()
                }
                "headers" -> {
                    headers = stringMap(value)
                    if (url != null) load()
                }
                "live" -> {
                    liveMode = value as? Boolean ?: false
                }
                "paused" -> {
                    paused = value as? Boolean ?: false
                    player.playWhenReady = !paused
                }
                "rate" -> {
                    rate = (value as? Number)?.toDouble() ?: 1.0
                    player.setPlaybackSpeed(rate.toFloat())
                }
                "startPosition" -> {
                    pendingSeekMs = ((value as? Number)?.toDouble() ?: 0.0).toLong()
                    if (player.playbackState == Player.STATE_READY && pendingSeekMs > 0) {
                        player.seekTo(pendingSeekMs)
                        pendingSeekMs = 0
                    }
                }
            }
        }
    }

    private fun dataSourceFactory(): DataSource.Factory {
        val allHeaders = LinkedHashMap<String, String>()
        allHeaders["User-Agent"] = AuthStore.UA
        allHeaders.putAll(headers)
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(allHeaders)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val raw = DefaultDataSource.Factory(context, http)
        val resolving = ResolvingDataSource.Factory(raw) { spec ->
            if (!AuthStore.isSiteUrl(spec.uri.toString())) return@Factory spec
            AuthStore.ensureFresh()
            val next = LinkedHashMap(spec.httpRequestHeaders)
            AuthStore.authorization()?.let { next["Authorization"] = it }
            spec.withRequestHeaders(next)
        }
        return AuthRetryFactory(resolving)
    }

    private fun load() {
        val target = url ?: return
        val item = MediaItem.Builder().setUri(target).build()
        val dataSource = dataSourceFactory()
        val source = if (isHls(target)) {
            HlsMediaSource.Factory(dataSource)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(dataSource).createMediaSource(item)
        }
        val position = if (liveMode) 0L else player.currentPosition.takeIf { player.mediaItemCount > 0 } ?: 0L
        player.setMediaSource(source)
        player.prepare()
        if (pendingSeekMs > 0) {
            player.seekTo(pendingSeekMs)
            pendingSeekMs = 0
        } else if (liveMode) {
            player.seekToDefaultPosition()
        } else if (position > 0) {
            player.seekTo(position)
        }
        player.playWhenReady = !paused
        player.setPlaybackSpeed(rate.toFloat())
        applySubtitleStyle()
        Log.d("nsh-host", "playback service start")
        PlaybackService.start(context)
        PlaybackService.refresh()
    }

    override fun command(name: String, args: Any?) {
        root.post {
            when (name) {
                "load" -> {
                    val map = args as? Map<*, *> ?: return@post
                    val nextUrl = map["url"] as? String ?: return@post
                    url = nextUrl
                    headers = stringMap(map["headers"])
                    liveMode = map["live"] as? Boolean ?: liveMode
                    load()
                }
                "play" -> player.play()
                "pause" -> player.pause()
                "stop" -> player.stop()
                "seek" -> {
                    val seconds = (args as? Number)?.toDouble() ?: return@post
                    player.seekTo((seconds * 1000).toLong())
                }
                "setRate" -> {
                    val next = (args as? Number)?.toDouble() ?: 1.0
                    rate = next
                    player.setPlaybackSpeed(next.toFloat())
                }
                "refreshTracks" -> emitTracks()
                "setDanmaku" -> {
                    val map = args as? Map<*, *> ?: return@post
                    val raw = map["items"] as? List<*> ?: emptyList<Any>()
                    val items = raw.mapNotNull { entry ->
                        val item = entry as? Map<*, *> ?: return@mapNotNull null
                        DanmakuItem(
                            (item["time"] as? Number)?.toLong() ?: return@mapNotNull null,
                            item["text"] as? String ?: return@mapNotNull null,
                            (item["mode"] as? Number)?.toInt() ?: 0,
                            colorOf(item["color"])
                        )
                    }
                    danmakuView.update(
                        items,
                        map["enabled"] as? Boolean ?: true,
                        (map["opacity"] as? Number)?.toFloat() ?: 0.85f,
                        (map["fontSize"] as? Number)?.toFloat() ?: 18f,
                        map["mode"] as? String ?: "all"
                    )
                }
                "setMediaInfo" -> {
                    val map = args as? Map<*, *>
                    PlayerBridge.title = map?.get("title") as? String ?: "MoonTVPlus"
                    PlayerBridge.subtitle = map?.get("subtitle") as? String ?: ""
                    PlaybackService.refresh()
                }
                "retry" -> {
                    retries = 0
                    retryAtCurrentPosition()
                }
                "selectVideo" -> {
                    val map = args as? Map<*, *> ?: return@post
                    val group = (map["group"] as? Number)?.toInt() ?: return@post
                    val index = (map["index"] as? Number)?.toInt() ?: 0
                    selectTrack(C.TRACK_TYPE_VIDEO, group, index)
                }
                "autoVideo" -> {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build()
                    emitTracks()
                }
                "selectAudio" -> {
                    val map = args as? Map<*, *> ?: return@post
                    val group = (map["group"] as? Number)?.toInt() ?: return@post
                    val index = (map["index"] as? Number)?.toInt() ?: 0
                    selectTrack(C.TRACK_TYPE_AUDIO, group, index)
                }
                "selectSubtitle" -> {
                    val map = args as? Map<*, *> ?: return@post
                    val group = (map["group"] as? Number)?.toInt() ?: return@post
                    val index = (map["index"] as? Number)?.toInt() ?: 0
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(overrideFor(group, index))
                        .build()
                    emitTracks()
                }
                "setSubtitleEnabled" -> {
                    subtitleVisible = (args as? Map<*, *>)?.get("enabled") as? Boolean ?: false
                    if (subtitleUrl == null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitleVisible)
                            .build()
                    }
                    updateExternalSubtitle()
                    emitTracks()
                }
                "loadSubtitle" -> {
                    val map = args as? Map<*, *>
                    val next = (map?.get("url") as? String)?.takeIf { it.isNotBlank() }
                    subtitleUrl = next
                    subtitleVisible = true
                    externalCues = emptyList()
                    subtitleOverlay.text = ""
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    next?.let { fetchExternalSubtitle(it) }
                    emitTracks()
                }
                "clearSubtitle" -> {
                    subtitleUrl = null
                    externalCues = emptyList()
                    subtitleOverlay.text = ""
                    subtitleOverlay.visibility = View.GONE
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                    emitTracks()
                }
                "setSubtitleStyle" -> {
                    val map = args as? Map<*, *>
                    (map?.get("size") as? Number)?.let { subtitleSize = it.toFloat() }
                    (map?.get("outline") as? Boolean)?.let { subtitleOutline = it }
                    applySubtitleStyle()
                }
                "setFullscreen" -> {
                    val enabled = args as? Boolean ?: false
                    val activity = context as? Activity ?: return@post
                    WindowCompat.setDecorFitsSystemWindows(activity.window, !enabled)
                    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    if (enabled) {
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
                "setOrientation" -> {
                    val activity = context as? Activity ?: return@post
                    activity.requestedOrientation = when (args as? String) {
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                "enterPip" -> {
                    Log.d("nsh-host", "enter pip")
                    (context as? MainActivity)?.enterPip()
                }
            }
        }
    }

    private fun retryAtCurrentPosition() {
        if (player.mediaItemCount == 0) return
        val position = player.currentPosition
        player.prepare()
        player.seekTo(position)
        player.playWhenReady = !paused
        player.setPlaybackSpeed(rate.toFloat())
    }

    private fun selectTrack(type: Int, group: Int, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .clearOverridesOfType(type)
            .addOverride(overrideFor(group, index))
            .build()
        emitTracks()
    }

    private fun overrideFor(group: Int, index: Int): TrackSelectionOverride {
        val tracks = player.currentTracks
        val mediaGroup = tracks.groups[group].mediaTrackGroup
        return TrackSelectionOverride(mediaGroup, index)
    }

    private fun emitTracks() {
        val groups = player.currentTracks.groups
        val video = mutableListOf<Map<String, Any?>>()
        val audio = mutableListOf<Map<String, Any?>>()
        val text = mutableListOf<Map<String, Any?>>()
        groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val selected = group.isTrackSelected(trackIndex)
                val item = mutableMapOf<String, Any?>(
                    "group" to groupIndex,
                    "index" to trackIndex,
                    "selected" to selected
                )
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        item["label"] = if (format.height > 0) format.height.toString() + "p"
                        else (format.label ?: ("视频 " + (video.size + 1)))
                        item["height"] = format.height
                        item["width"] = format.width
                        item["bitrate"] = format.bitrate
                        video.add(item)
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        item["label"] = format.label ?: format.language ?: ("音轨 " + (audio.size + 1))
                        item["language"] = format.language
                        audio.add(item)
                    }
                    C.TRACK_TYPE_TEXT -> {
                        item["label"] = format.label ?: format.language ?: ("字幕 " + (text.size + 1))
                        item["language"] = format.language
                        text.add(item)
                    }
                }
            }
        }
        val subtitleEnabled = if (subtitleUrl != null) subtitleVisible
        else !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        nsh.emit(
            "tracks",
            mapOf(
                "video" to video,
                "audio" to audio,
                "subtitle" to text,
                "subtitleEnabled" to subtitleEnabled,
                "externalSubtitle" to (subtitleUrl != null)
            )
        )
    }

    private fun applySubtitleStyle() {
        val view = playerView.subtitleView
        if (view != null) {
            view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
            view.setStyle(
                CaptionStyleCompat(
                    Color.WHITE,
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    if (subtitleOutline) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
                    Color.BLACK,
                    null
                )
            )
        }
        subtitleOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSize)
        if (subtitleOutline) subtitleOverlay.setShadowLayer(3f, 0f, 0f, Color.BLACK)
        else subtitleOverlay.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    private fun fetchExternalSubtitle(target: String) {
        val builder = Request.Builder().url(target).header("User-Agent", AuthStore.UA)
        AuthStore.authorization()?.let { builder.header("Authorization", it) }
        Thread {
            val text = subtitleClient.newCall(builder.build()).execute().use { it.body?.string() ?: "" }
            val cues = parseSubtitles(text)
            handler.post {
                if (subtitleUrl == target) {
                    externalCues = cues
                    updateExternalSubtitle()
                }
            }
        }.start()
    }

    private fun updateExternalSubtitle() {
        if (subtitleUrl == null || !subtitleVisible) {
            subtitleOverlay.visibility = View.GONE
            return
        }
        val position = player.currentPosition
        val cue = externalCues.firstOrNull { position >= it.start && position < it.end }
        subtitleOverlay.text = cue?.text ?: ""
        subtitleOverlay.visibility = if (cue != null) View.VISIBLE else View.GONE
    }

    private fun parseSubtitles(text: String): List<SubtitleCue> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.contains("[Script Info]") || normalized.contains("Dialogue:")) {
            return normalized.lines().filter { it.startsWith("Dialogue:") }.mapNotNull { line ->
                val parts = line.removePrefix("Dialogue:").split(",", limit = 10)
                if (parts.size < 10) return@mapNotNull null
                val start = parseTimestamp(parts[1].trim())
                val end = parseTimestamp(parts[2].trim())
                val content = parts[9]
                    .replace(Regex("\\{[^}]*\\}"), "")
                    .replace("\\N", "\n")
                    .trim()
                if (start < 0 || end <= start || content.isBlank()) null
                else SubtitleCue(start, end, content)
            }
        }
        val cues = mutableListOf<SubtitleCue>()
        for (block in normalized.split(Regex("\n\\s*\n"))) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timeIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeIndex < 0) continue
            val times = lines[timeIndex].split("-->")
            val start = parseTimestamp(times[0].trim())
            val end = parseTimestamp(times.getOrNull(1)?.trim()?.substringBefore(" ") ?: "")
            val content = lines.drop(timeIndex + 1).joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (start < 0 || end <= start || content.isBlank()) continue
            cues.add(SubtitleCue(start, end, content))
        }
        return cues
    }

    private fun parseTimestamp(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        if (parts.size != 3) return -1
        val seconds = parts[2].toDoubleOrNull() ?: return -1
        return ((parts[0].toLongOrNull() ?: 0) * 3600 + (parts[1].toLongOrNull() ?: 0) * 60) * 1000 +
            (seconds * 1000).toLong()
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player.release()
        (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun emitState() {
        val state = when {
            player.playbackState == Player.STATE_ENDED -> "ended"
            player.playbackState == Player.STATE_BUFFERING -> "buffering"
            player.isPlaying -> "playing"
            else -> "paused"
        }
        if (state != lastState) {
            lastState = state
            nsh.emit("state", mapOf("state" to state))
        }
    }

    private fun isHls(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/api/proxy-m3u8") ||
            lower.contains("/api/proxy/vod/m3u8")
    }

    private fun colorOf(value: Any?): Int {
        val raw = value as? String ?: return Color.WHITE
        return try {
            Color.parseColor(raw)
        } catch (error: Exception) {
            Color.WHITE
        }
    }

    private fun stringMap(value: Any?): Map<String, String> {
        val map = value as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        map.forEach { (key, item) ->
            if (key != null && item != null) out[key.toString()] = item.toString()
        }
        return out
    }
}

data class SubtitleCue(val start: Long, val end: Long, val text: String)

private class AuthRetryFactory(
    private val upstream: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = AuthRetryDataSource(upstream.createDataSource())
}

private class AuthRetryDataSource(
    private val upstream: DataSource
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        try {
            return upstream.open(dataSpec)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode != 401) throw error
            val refreshed = AuthStore.refreshNow() ?: throw IOException("AUTH_REFRESH_FAILED", error)
            val nextHeaders = LinkedHashMap(dataSpec.httpRequestHeaders)
            nextHeaders["Authorization"] = "Bearer " + refreshed
            return upstream.open(dataSpec.withRequestHeaders(nextHeaders))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

