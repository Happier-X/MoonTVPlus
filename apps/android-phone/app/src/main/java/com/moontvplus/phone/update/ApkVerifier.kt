package com.moontvplus.phone.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ApkVerifier(private val context: Context) {
    @Suppress("DEPRECATION")
    fun verify(file: File, update: ClientUpdate, savedHash: String? = null) {
        if (!file.isFile || file.length() != update.apk.size) throw IOException("安装包不存在或不完整，请重新下载。")
        if (savedHash != null && sha256(file) != savedHash) throw IOException("安装包已发生变化，请重新下载。")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("文件不是有效的 Android 安装包。")
        val installed = manager.getPackageInfo(context.packageName, flags)
        if (Build.VERSION.SDK_INT >= 24 && archive.applicationInfo?.minSdkVersion?.let { it > Build.VERSION.SDK_INT } == true) {
            throw IOException("新版需要更高版本的 Android，当前设备无法安装。")
        }
        if (archive.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            throw IOException("不能通过应用更新安装调试包。")
        }
        UpdatePolicy.validateIdentity(
            archive.packageName, context.packageName, versionCode(archive), versionCode(installed),
            archive.versionName, update.version, signerMatches(installed, archive),
        )
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerMatches(installed: PackageInfo, archive: PackageInfo): Boolean {
        fun hashes(signatures: Array<android.content.pm.Signature>?): Set<String> =
            signatures.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        if (Build.VERSION.SDK_INT < 28) {
            val current = hashes(installed.signatures)
            return current.isNotEmpty() && current == hashes(archive.signatures)
        }
        val old = installed.signingInfo ?: return false
        val candidate = archive.signingInfo ?: return false
        val current = hashes(old.apkContentsSigners)
        val next = hashes(candidate.apkContentsSigners)
        if (current.isEmpty() || next.isEmpty()) return false
        if (old.hasMultipleSigners() || candidate.hasMultipleSigners()) return current == next
        // A platform-verified signing lineage permits forward key rotation, never rollback to an old key.
        return current == next || hashes(candidate.signingCertificateHistory).containsAll(current)
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
