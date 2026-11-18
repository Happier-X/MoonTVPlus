package com.moontvplus.phone.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moontvplus.phone.BuildConfig
import java.util.Locale

@Composable
fun UpdateDialog(state: UpdateUiState, updater: AppUpdater, onInstall: () -> Unit) {
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = updater::dismiss,
        title = { Text("应用更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodyMedium)
                state.update?.let {
                    Text("新版本 ${it.version}", style = MaterialTheme.typography.titleMedium)
                    Text(it.notes.ifBlank { "发布者未提供更新说明。" }, style = MaterialTheme.typography.bodySmall)
                    Text("安装包 ${megabytes(it.apk.size)} MB · GitHub 正式发布", style = MaterialTheme.typography.bodySmall)
                }
                when (state.stage) {
                    UpdateStage.RESTORING -> Text("正在恢复更新状态…")
                    UpdateStage.CHECKING -> Text("正在检查安卓稳定版…")
                    UpdateStage.DOWNLOADING -> {
                        LinearProgressIndicator(
                            progress = { if (state.total > 0) (state.received.toFloat() / state.total).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("已下载 ${megabytes(state.received)} / ${megabytes(state.total)} MB（${if (state.total > 0) state.received * 100 / state.total else 0}%）")
                    }
                    UpdateStage.CANCELLING -> Text("正在取消下载…")
                    UpdateStage.VERIFYING -> Text("正在校验安装包、版本与签名…")
                    UpdateStage.IDLE -> Text("从 GitHub 检查安卓客户端稳定版，下载后由系统确认安装。")
                    else -> Unit
                }
                state.message?.let { Text(it, color = if (state.stage == UpdateStage.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.update != null && !state.busy && state.stage != UpdateStage.READY) {
                    TextButton(onClick = updater::ignore) { Text("忽略此版本") }
                }
            }
        },
        confirmButton = {
            when {
                state.stage == UpdateStage.READY -> TextButton(onClick = onInstall) { Text("安装更新") }
                state.stage == UpdateStage.DOWNLOADING -> TextButton(onClick = updater::cancelDownload) { Text("取消下载") }
                state.busy -> Unit
                state.update != null -> TextButton(onClick = updater::download) { Text(if (state.stage == UpdateStage.ERROR) "重新下载" else "下载更新") }
                else -> TextButton(onClick = { updater.check(manual = true) }) { Text(if (state.stage == UpdateStage.ERROR) "重试检查" else "检查更新") }
            }
        },
        dismissButton = {
            TextButton(onClick = updater::dismiss) { Text(if (state.busy) "收起" else "关闭") }
        },
    )
}

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)
