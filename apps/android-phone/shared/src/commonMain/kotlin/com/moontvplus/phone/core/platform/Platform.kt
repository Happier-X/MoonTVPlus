package com.moontvplus.phone.core.platform

/**
 * 各平台在启动时注入的运行时信息。
 *
 * 逻辑层（网络、缓存、仓库）通过它拿 UA 和可写目录，避免直接依赖 Android API，
 * 这样同一份代码在 Windows 桌面上也能跑。
 */
interface PlatformInfo {
    /** 完整 UA，必须包含 `moontvplus app`（服务端据此套去广告代理） */
    val userAgent: String
    /** 可写的应用私有目录，绝对路径 */
    val appDataDir: String
    val debug: Boolean
    /** 仅用于日志/诊断，例如 "Android 14; Pixel 7" */
    val deviceLabel: String
    /** 平台名，展示与分支用 */
    val platformName: String
}

private object FallbackPlatformInfo : PlatformInfo {
    override val userAgent: String = DEFAULT_UA
    override val appDataDir: String = "."
    override val debug: Boolean = false
    override val deviceLabel: String = "Unknown"
    override val platformName: String = "Unknown"
}

object Platform {
    private var info: PlatformInfo = FallbackPlatformInfo

    fun init(info: PlatformInfo) {
        this.info = info
    }

    val userAgent: String get() = info.userAgent
    val appDataDir: String get() = info.appDataDir
    val debug: Boolean get() = info.debug
    val deviceLabel: String get() = info.deviceLabel
    val platformName: String get() = info.platformName
}

const val DEFAULT_UA = "MoonTVPlus App/1.0.0 (Android) moontvplus app"
