package com.moontvplus.phone.playback

/**
 * 播放页需要的系统级操作。
 *
 * 这些能力都和「当前 Activity / 窗口」绑定，没法在 commonMain 里 expect/actual，
 * 由宿主平台实现后注入 `PlayerSession`；没有注入时（例如单测、桌面占位播放器），
 * 对应手势与入口整体隐藏，不做假交互。
 */
interface PlayerPlatform {
    /** 0f..1f，当前窗口亮度（不是系统设置里的亮度） */
    val brightness: Float
    fun setBrightness(value: Float)

    /** 0f..1f，媒体音量 */
    val systemVolume: Float
    fun setSystemVolume(value: Float)

    val pipSupported: Boolean
    fun enterPictureInPicture(): Boolean

    /** true = 强制横屏，false = 强制竖屏，null = 跟随传感器 */
    fun setLandscape(landscape: Boolean?)
    val orientationSupported: Boolean

    fun keepScreenOn(enabled: Boolean) = Unit

    fun vibrate(durationMs: Long) = Unit
}
