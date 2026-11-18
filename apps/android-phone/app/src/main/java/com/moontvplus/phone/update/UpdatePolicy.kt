package com.moontvplus.phone.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI

/** Client versions are independent of the website's 302.x versions. */
data class ClientVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<ClientVersion> {
    override fun compareTo(other: ClientVersion): Int =
        compareValuesBy(this, other, ClientVersion::major, ClientVersion::minor, ClientVersion::patch)
    companion object {
        fun parse(value: String): ClientVersion? {
            val parts = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
                .matchEntire(value)?.groupValues?.drop(1) ?: return null
            val numbers = parts.map { it.toLongOrNull() ?: return null }
            return ClientVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val digest: String? = null,
    val state: String = "uploaded",
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

@Serializable
data class ClientUpdate(val version: String, val notes: String, val apk: ReleaseAsset, val sums: ReleaseAsset? = null)

object UpdatePolicy {
    const val REPOSITORY = "Happier-X/MoonTVPlus"
    private const val PREFIX = "https://github.com/$REPOSITORY/releases/download/"
    const val MAX_APK_BYTES = 512L * 1024 * 1024

    fun releaseUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.rawUserInfo == null && url.startsWith(PREFIX) && uri.fragment == null &&
            uri.rawPath.split('/').none { it == ".." || it == "." }
    }.getOrDefault(false)

    fun redirectUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.port == -1 && uri.rawUserInfo == null &&
            uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)

    fun select(releases: List<GithubRelease>, current: String): ClientUpdate? {
        val installed = ClientVersion.parse(current) ?: throw IOException("当前客户端版本无法识别，请前往项目发布页检查。")
        return releases.mapNotNull { release ->
            if (release.draft || release.prerelease || !release.tag.startsWith("client-v")) return@mapNotNull null
            val version = release.tag.removePrefix("client-v")
            val parsed = ClientVersion.parse(version) ?: return@mapNotNull null
            if (parsed <= installed) return@mapNotNull null
            val expectedName = "moontvplus-phone-$version-release-signed.apk"
            val apk = release.assets.singleOrNull {
                it.name == expectedName && it.state == "uploaded" && it.size in 1..MAX_APK_BYTES &&
                    releaseUrl(it.url) && URI(it.url).rawPath.substringBeforeLast('/').endsWith("/${release.tag}")
            } ?: return@mapNotNull null
            val sums = release.assets.singleOrNull {
                it.name == "SHA256SUMS.txt" && it.state == "uploaded" && it.size in 1..262144 &&
                    releaseUrl(it.url) && URI(it.url).rawPath.substringBeforeLast('/') == URI(apk.url).rawPath.substringBeforeLast('/')
            }
            parsed to ClientUpdate(version, release.body.orEmpty(), apk, sums)
        }.maxByOrNull { it.first }?.second
    }

    fun checksum(update: ClientUpdate, sumsText: String?): String? {
        val digest = update.apk.digest?.let {
            if (!it.matches(Regex("sha256:[a-fA-F0-9]{64}"))) throw IOException("发布附件的 SHA-256 格式不正确。")
            it.substringAfter(':').lowercase()
        }
        val sum = sumsText?.lineSequence()?.mapNotNull { line ->
            Regex("^([a-fA-F0-9]{64})[ \\t]+\\*?(.+)$").matchEntire(line.trim())?.let {
                if (it.groupValues[2] == update.apk.name) it.groupValues[1].lowercase() else null
            }
        }?.toList()?.singleOrNull()
        if (sumsText != null && sum == null) throw IOException("校验清单未包含唯一的对应安装包，已停止下载。")
        if (digest != null && sum != null && digest != sum) throw IOException("发布附件的校验信息不一致。")
        return digest ?: sum
    }

    fun validateIdentity(packageName: String?, expectedPackage: String, code: Long, currentCode: Long,
                         version: String?, expectedVersion: String, sameSigner: Boolean) {
        if (packageName != expectedPackage) throw IOException("安装包不是 MoonTVPlus 安卓客户端。")
        if (code <= currentCode) throw IOException("安装包版本码未高于当前版本，不能覆盖升级。")
        if (version != expectedVersion) throw IOException("安装包版本与发布版本不一致。")
        if (!sameSigner) throw IOException("安装包签名与当前应用不匹配，已拒绝安装。请确认当前应用是否为官方正式签名版本。")
    }
}
