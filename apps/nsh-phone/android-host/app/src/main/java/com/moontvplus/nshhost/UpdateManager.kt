package com.moontvplus.nshhost

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.longbridge.nsh.NshHostReply
import com.longbridge.nsh.NshView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class UpdateManager(
    private val activity: Activity,
    private val nsh: NshView,
    private val http: OkHttpClient
) {
    private val calls = ConcurrentHashMap<String, Call>()
    private val partials = ConcurrentHashMap<String, File>()
    private var pendingInstall: String? = null

    fun download(data: Any?, reply: NshHostReply) {
        val map = data as? Map<*, *>
        val url = map?.get("url") as? String
        val versionName = map?.get("versionName") as? String ?: ""
        val versionCode = (map?.get("versionCode") as? Number)?.toLong() ?: 0L
        val expectedSize = (map?.get("size") as? Number)?.toLong() ?: 0L
        val expectedSha = (map?.get("sha256") as? String)?.trim()?.lowercase() ?: ""
        if (url.isNullOrBlank() || versionCode <= 0L) {
            reply.reject("missing url or versionCode")
            return
        }

        val id = (map?.get("id") as? String)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val dir = File(activity.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "moontvplus-" + versionCode + ".apk")
        val partial = File(dir, "moontvplus-" + versionName.ifBlank { versionCode.toString() } + ".part")
        partial.delete()
        partials[id] = partial

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AuthStore.UA)
            .build()
        val call = http.newCall(request)
        calls[id] = call
        reply.resolve(mapOf("id" to id))

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                calls.remove(id)
                partial.delete()
                partials.remove(id)
                nsh.send("update.progress", mapOf("id" to id, "done" to true, "error" to (error.message ?: "下载失败")))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            throw IOException("HTTP " + res.code)
                        }
                        val body = res.body ?: throw IOException("响应为空")
                        val total = if (expectedSize > 0) expectedSize else body.contentLength()
                        val digest = MessageDigest.getInstance("SHA-256")
                        var received = 0L
                        var lastReport = 0L
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    received += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport > 300) {
                                        lastReport = now
                                        nsh.send("update.progress", mapOf(
                                            "id" to id,
                                            "received" to received,
                                            "total" to total
                                        ))
                                    }
                                }
                            }
                        }
                        nsh.send("update.progress", mapOf("id" to id, "received" to received, "total" to total))

                        if (expectedSize > 0 && received != expectedSize) {
                            throw IOException("大小不匹配：" + received + " / " + expectedSize)
                        }
                        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                        if (expectedSha.isNotBlank() && actualSha != expectedSha) {
                            throw IOException("sha256 不匹配")
                        }

                        @Suppress("DEPRECATION")
                        val archive = activity.packageManager.getPackageArchiveInfo(partial.absolutePath, 0)
                            ?: throw IOException("无法读取 APK 信息")
                        if (archive.packageName != activity.packageName) {
                            throw IOException("包名不匹配：" + archive.packageName)
                        }
                        val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
                        val currentCode = PackageInfoCompat.getLongVersionCode(
                            activity.packageManager.getPackageInfo(activity.packageName, 0)
                        )
                        if (archiveCode <= currentCode) {
                            throw IOException("APK versionCode 未递增：" + archiveCode + " / " + currentCode)
                        }
                        val debuggable = (archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0
                        if (debuggable) {
                            throw IOException("拒绝安装 debug 包")
                        }

                        if (target.exists()) target.delete()
                        if (!partial.renameTo(target)) {
                            throw IOException("保存安装包失败")
                        }
                        calls.remove(id)
                        partials.remove(id)
                        nsh.send("update.progress", mapOf(
                            "id" to id,
                            "done" to true,
                            "path" to target.absolutePath,
                            "received" to received,
                            "total" to total,
                            "sha256" to actualSha,
                            "versionCode" to archiveCode
                        ))
                    }
                } catch (error: Exception) {
                    Log.w("nsh-host", "update download failed", error)
                    calls.remove(id)
                    partial.delete()
                    partials.remove(id)
                    nsh.send("update.progress", mapOf("id" to id, "done" to true, "error" to (error.message ?: "下载失败")))
                }
            }
        })
    }

    fun cancel(data: Any?, reply: NshHostReply) {
        val id = (data as? Map<*, *>)?.get("id") as? String
        if (!id.isNullOrBlank()) {
            calls.remove(id)?.cancel()
            partials.remove(id)?.delete()
        }
        reply.resolve(true)
    }

    fun install(data: Any?, reply: NshHostReply) {
        val path = (data as? Map<*, *>)?.get("path") as? String
        if (path.isNullOrBlank()) {
            reply.reject("missing path")
            return
        }
        val file = File(path)
        if (!file.isFile) {
            reply.reject("安装包不存在")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            pendingInstall = path
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
                activity.startActivityForResult(intent, REQUEST_INSTALL_PERMISSION)
                reply.resolve(mapOf("needPermission" to true))
            } catch (error: Exception) {
                reply.reject(error.message ?: "无法打开安装权限设置")
            }
            return
        }
        try {
            launchInstaller(file)
            reply.resolve(mapOf("started" to true))
        } catch (error: Exception) {
            reply.reject(error.message ?: "无法拉起安装器")
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST_INSTALL_PERMISSION) return
        val path = pendingInstall ?: return
        pendingInstall = null
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            nsh.send("update.installResult", mapOf("error" to "未授权安装未知应用"))
            return
        }
        try {
            launchInstaller(File(path))
            nsh.send("update.installResult", mapOf("started" to true))
        } catch (error: Exception) {
            nsh.send("update.installResult", mapOf("error" to (error.message ?: "无法拉起安装器")))
        }
    }

    private fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 61
    }
}


