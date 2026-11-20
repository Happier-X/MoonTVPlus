package com.moontvplus.phone.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import com.moontvplus.phone.playback.PlayerPlatform
import kotlin.math.roundToInt

/**
 * 播放页的系统级能力。
 *
 * 亮度和媒体音量都走「当前窗口」，退出播放页后系统设置恢复原样——
 * 这也是 B 站等播放器的行为，不会悄悄改掉用户的系统亮度。
 */
class AndroidPlayerPlatform(private val activity: Activity) : PlayerPlatform {

    private val audioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override val brightness: Float
        get() = activity.window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f

    override fun setBrightness(value: Float) {
        // 恢复默认要写 BRIGHTNESS_OVERRIDE_NONE；这里只处理 0.01 以上的有效值
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = value.coerceIn(0.01f, 1f)
        }
    }

    override val systemVolume: Float
        get() {
            val manager = audioManager ?: return 0.5f
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }

    override fun setSystemVolume(value: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (value.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    override val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun enterPictureInPicture(): Boolean {
        if (!pipSupported) return false
        return runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            activity.enterPictureInPictureMode(params)
        }.getOrDefault(false)
    }

    override fun setLandscape(landscape: Boolean?) {
        activity.requestedOrientation = when (landscape) {
            true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            false -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override val orientationSupported: Boolean = true

    override fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun vibrate(durationMs: Long) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = activity.getSystemService(android.os.VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            vibrator?.vibrate(durationMs)
        }
    }
}
