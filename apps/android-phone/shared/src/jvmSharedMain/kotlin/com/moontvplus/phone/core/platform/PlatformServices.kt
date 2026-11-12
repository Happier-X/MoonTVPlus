package com.moontvplus.phone.core.platform

import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.phone.core.storage.SettingsStore

/**
 * 站点 cookie（`auth=<token>`）的维护。
 *
 * 原生页面用 Authorization 头，但「网页版」入口是 WebView，只能靠 cookie 带登录态，
 * 所以登录/退出时两边要一起维护。桌面端没有 WebView，用空实现即可。
 */
interface SiteCookies {
    suspend fun writeAuth(origin: String, token: String)
    suspend fun clearAuth(origin: String)
    suspend fun readAuth(origin: String): String?
}

object NoopSiteCookies : SiteCookies {
    override suspend fun writeAuth(origin: String, token: String) = Unit
    override suspend fun clearAuth(origin: String) = Unit
    override suspend fun readAuth(origin: String): String? = null
}

/**
 * 从旧的 WebView 壳（同包名、直接加载网页）升级上来时的兼容处理。
 *
 * Android 上会读 SharedPreferences 的 server_url 与站点 cookie；
 * 桌面端没有这段历史，用 [NoopLegacySessionBridge]。
 */
interface LegacySessionBridge {
    /** 旧壳里配过的服务器地址；当前没有地址时才写入，返回是否发生了迁移。 */
    suspend fun migrateServerAddress(settings: SettingsStore): Boolean

    /** 升级后第一次启动从站点 cookie 还原登录态。 */
    suspend fun restoreSessionOnce(address: ServerAddress, settings: SettingsStore): AuthSession?
}

object NoopLegacySessionBridge : LegacySessionBridge {
    override suspend fun migrateServerAddress(settings: SettingsStore): Boolean = false
    override suspend fun restoreSessionOnce(
        address: ServerAddress,
        settings: SettingsStore,
    ): AuthSession? = null
}

/** 各平台需要注入的服务集合。桌面端全部用空实现。 */
data class PlatformServices(
    val cookies: SiteCookies = NoopSiteCookies,
    val legacy: LegacySessionBridge = NoopLegacySessionBridge,
)
