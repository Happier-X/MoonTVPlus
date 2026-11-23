package com.moontvplus.nshhost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

class PlaybackService : Service() {
    companion object {
        private const val CHANNEL = "moontvplus_playback"
        private const val NOTIFICATION_ID = 41

        @Volatile private var instance: PlaybackService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }

        fun refresh() {
            instance?.update()
        }
    }

    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        session = MediaSessionCompat(this, "MoonTVPlus").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    PlayerBridge.play()
                    update()
                }

                override fun onPause() {
                    PlayerBridge.pause()
                    update()
                }

                override fun onSeekTo(pos: Long) {
                    PlayerBridge.seekTo(pos)
                    update()
                }
            })
            isActive = true
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        update()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        update()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "播放控制", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playing = PlayerBridge.isPlaying()
        val action = if (playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "暂停",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "播放",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(PlayerBridge.title)
            .setContentText(PlayerBridge.subtitle)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    private fun update() {
        val playing = PlayerBridge.isPlaying()
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, PlayerBridge.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, PlayerBridge.subtitle)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, PlayerBridge.duration())
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND
                )
                .setState(state, PlayerBridge.position(), if (playing) 1f else 0f)
                .build()
        )
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
    }
}
