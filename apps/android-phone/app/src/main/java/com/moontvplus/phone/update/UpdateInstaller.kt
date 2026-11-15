package com.moontvplus.phone.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider

/** The system always owns permission and installation UI; no storage or notification permission is needed. */
class UpdateInstaller(
    private val activity: ComponentActivity,
    private val updater: AppUpdater,
    private val contentUri: (java.io.File) -> Uri = { file ->
        FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
    },
) {
    fun install() {
        updater.prepareInstall { file ->
            if (activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@prepareInstall
            try {
                if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                    updater.awaitingInstallPermission = true
                    updater.installMessage("请允许 MoonTVPlus 安装应用；返回后将继续打开系统安装器。")
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                } else {
                    updater.awaitingInstallPermission = false
                    val uri = contentUri(file)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        clipData = ClipData.newRawUri("MoonTVPlus 更新", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    updater.installMessage("已打开系统安装器。若取消安装，可再次点击安装更新。")
                }
            } catch (_: Exception) {
                updater.awaitingInstallPermission = false
                updater.installMessage("无法打开安装权限页面或系统安装器，请检查设备设置后重试。")
            }
        }
    }

    fun resume() {
        if (!updater.awaitingInstallPermission) return
        if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
            install()
        } else {
            updater.awaitingInstallPermission = false
            updater.installMessage("尚未允许安装应用。安装包已保留，可点击安装更新后重试授权。")
        }
    }
}
