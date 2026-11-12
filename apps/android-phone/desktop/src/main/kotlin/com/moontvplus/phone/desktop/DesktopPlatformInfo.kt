package com.moontvplus.phone.desktop

import com.moontvplus.phone.core.platform.PlatformInfo
import java.io.File

/** 桌面端注入给共享逻辑层的运行时信息。 */
class DesktopPlatformInfo(appVersion: String) : PlatformInfo {

    override val deviceLabel: String =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

    override val userAgent: String = "MoonTVPlus App/$appVersion ($deviceLabel) moontvplus app"

    override val appDataDir: String = File(System.getProperty("user.home"), ".moontvplus")
        .apply { mkdirs() }
        .absolutePath

    override val debug: Boolean = false

    override val platformName: String = "Desktop"
}
