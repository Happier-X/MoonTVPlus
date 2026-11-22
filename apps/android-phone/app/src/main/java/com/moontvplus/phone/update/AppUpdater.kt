package com.moontvplus.phone.update

import android.content.Context
import android.os.StatFs
import com.moontvplus.phone.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

enum class UpdateStage { RESTORING, IDLE, CHECKING, AVAILABLE, DOWNLOADING, CANCELLING, VERIFYING, READY, LATEST, ERROR }

data class UpdateUiState(
    val stage: UpdateStage = UpdateStage.RESTORING,
    val visible: Boolean = false,
    val update: ClientUpdate? = null,
    val received: Long = 0,
    val total: Long = 0,
    val message: String? = null,
) {
    val busy get() = stage in setOf(UpdateStage.RESTORING, UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.CANCELLING, UpdateStage.VERIFYING)
}

@Serializable
private data class ReadyDownload(val update: ClientUpdate, val hash: String)

/** Application-owned work survives activity recreation; partial downloads are discarded after process death. */
class AppUpdater(
    private val context: Context,
    private val source: UpdateSource = CompositeUpdateSource(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) {
    private val preferences = context.getSharedPreferences("client_updates", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "updates")
    private val apk = File(directory, "client-update.apk")
    private val partial = File(directory, "client-update.apk.part")
    private val verifier = ApkVerifier(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(UpdateUiState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var ready: ReadyDownload? = null
    var awaitingInstallPermission: Boolean
        get() = preferences.getBoolean("install_permission", false)
        set(value) { preferences.edit().putBoolean("install_permission", value).apply() }

    init {
        work = scope.launch {
            val restored = withContext(Dispatchers.IO) {
                directory.mkdirs()
                partial.delete()
                runCatching {
                    preferences.getString("ready", null)?.let { text ->
                        json.decodeFromString<ReadyDownload>(text).also { verifier.verify(apk, it.update, it.hash) }
                    }
                }.getOrNull()
            }
            ready = restored
            if (restored != null) mutable.update { it.copy(stage = UpdateStage.READY, update = restored.update) }
            else {
                clearReady()
                withContext(Dispatchers.IO) { apk.delete() }
                mutable.update { it.copy(stage = UpdateStage.IDLE) }
            }
        }
    }

    suspend fun awaitIdle() { work?.join() }

    fun show() { mutable.update { it.copy(visible = true) } }
    fun dismiss() { mutable.update { it.copy(visible = false) } }
    fun ignore() {
        state.value.update?.let { preferences.edit().putString("ignored", it.version).apply() }
        dismiss()
    }

    fun check(manual: Boolean) {
        if (manual) show()
        if (state.value.busy || work?.isActive == true || ready != null) return
        val now = System.currentTimeMillis()
        val last = preferences.getLong("last_check", 0)
        if (!manual && now >= last && now - last < 12 * 60 * 60 * 1000L) return
        preferences.edit().putLong("last_check", now).apply()
        mutable.update { it.copy(stage = UpdateStage.CHECKING, message = null, update = null) }
        work = scope.launch {
            try {
                val update = source.check(currentVersion)
                val prompt = update != null && (manual || preferences.getString("ignored", null) != update.version)
                mutable.update { it.copy(
                    stage = if (update == null) UpdateStage.LATEST else UpdateStage.AVAILABLE,
                    update = update, visible = it.visible || prompt,
                    message = if (update == null) "没有发现适用于此设备的更高版本正式安装包。" else null,
                ) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e, visible = manual || state.value.visible) }
        }
    }

    fun download() {
        val update = state.value.update ?: return
        if (state.value.busy || work?.isActive == true) return
        clearReady()
        mutable.update { it.copy(stage = UpdateStage.DOWNLOADING, received = 0, total = update.apk.size, message = null) }
        work = scope.launch {
            try {
                val hash = withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    apk.delete()
                    partial.delete()
                    if (StatFs(directory.absolutePath).availableBytes < update.apk.size + 16 * 1024 * 1024) {
                        throw IOException("设备可用空间不足，请清理后重试。")
                    }
                    val hash = source.download(update, partial) { received, total ->
                        mutable.update { it.copy(received = received, total = total) }
                    }
                    mutable.update { it.copy(stage = UpdateStage.VERIFYING) }
                    verifier.verify(partial, update, hash)
                    if (!partial.renameTo(apk)) throw IOException("无法保存安装包，请重试。")
                    hash
                }
                ready = ReadyDownload(update, hash)
                preferences.edit().putString("ready", json.encodeToString(ReadyDownload.serializer(), ready!!)).apply()
                mutable.update { it.copy(stage = UpdateStage.READY, message = "下载与校验完成，可安装更新。") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.IO) { partial.delete(); apk.delete() }
                    mutable.update { it.copy(stage = UpdateStage.AVAILABLE, message = "下载已取消，可重新下载。") }
                }
                throw e
            } catch (e: Exception) {
                // Cancelling OkHttp can surface as IOException instead of CancellationException.
                val cancelled = e is CancellationException || !kotlinx.coroutines.currentCoroutineContext()[Job]!!.isActive
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.IO) { partial.delete(); apk.delete() }
                    if (cancelled) {
                        mutable.update { it.copy(stage = UpdateStage.AVAILABLE, message = "下载已取消，可重新下载。") }
                    } else failure(e)
                }
                if (cancelled) throw CancellationException("Download cancelled", e)
            }
        }
    }

    fun cancelDownload() {
        if (state.value.stage != UpdateStage.DOWNLOADING) return
        val download = work ?: return
        mutable.update { it.copy(stage = UpdateStage.CANCELLING) }
        // Cancellation must also be recorded if the download was still suspended before its try/finally.
        scope.launch {
            download.cancel()
            download.join()
            withContext(Dispatchers.IO) { partial.delete(); apk.delete() }
            mutable.update { it.copy(stage = UpdateStage.AVAILABLE, message = "下载已取消，可重新下载。") }
        }
    }

    /** Re-check the private file, identity and signature immediately before granting the installer access. */
    fun prepareInstall(onReady: (File) -> Unit) {
        val download = ready ?: run {
            mutable.update { it.copy(stage = UpdateStage.ERROR, message = "安装包已丢失，请重新下载。") }
            return
        }
        if (state.value.busy || work?.isActive == true) return
        mutable.update { it.copy(stage = UpdateStage.VERIFYING, message = null) }
        work = scope.launch {
            try {
                withContext(Dispatchers.IO) { verifier.verify(apk, download.update, download.hash) }
                mutable.update { it.copy(stage = UpdateStage.READY) }
                onReady(apk)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                clearReady()
                withContext(Dispatchers.IO) { apk.delete() }
                failure(e)
            }
        }
    }

    fun installMessage(message: String) { mutable.update { it.copy(message = message, visible = true) } }

    private fun clearReady() {
        ready = null
        awaitingInstallPermission = false
        preferences.edit().remove("ready").apply()
    }

    private fun failure(error: Exception, visible: Boolean = state.value.visible) {
        val message = when {
            error is java.net.UnknownHostException || error is java.net.ConnectException -> "无法连接更新服务器，请检查网络后重试。"
            error is java.net.SocketTimeoutException -> "连接或下载超时，请检查网络后重试。"
            error is javax.net.ssl.SSLException -> "更新服务器安全连接失败，请检查网络与设备时间后重试。"
            error is IOException -> error.message?.takeIf { it.any { char -> char in '一'..'鿿' } }
                ?: "网络连接或文件读写失败，请检查网络与可用空间后重试。"
            else -> "更新检查或文件处理失败，请稍后重试。"
        }
        mutable.update { it.copy(stage = UpdateStage.ERROR, message = message, visible = visible) }
    }
}
