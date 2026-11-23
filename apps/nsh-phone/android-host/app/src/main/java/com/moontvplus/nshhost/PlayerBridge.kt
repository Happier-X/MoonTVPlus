package com.moontvplus.nshhost

import androidx.media3.exoplayer.ExoPlayer

object PlayerBridge {
    @Volatile var player: ExoPlayer? = null
    @Volatile var title: String = "MoonTVPlus"
    @Volatile var subtitle: String = ""

    fun attach(next: ExoPlayer) {
        player = next
    }

    fun detach(next: ExoPlayer) {
        if (player === next) player = null
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun position(): Long = player?.currentPosition ?: 0L
    fun duration(): Long = player?.duration ?: 0L
}
