package com.moontvplus.phone.core.platform

import android.content.Intent
import android.net.Uri
import com.moontvplus.phone.playback.AndroidPlaybackContext

actual fun openExternalPlayer(url: String): Boolean {
    val context = AndroidPlaybackContext.contextOrNull() ?: return false
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

actual fun readLocalTextSource(uri: String): String? {
    val context = AndroidPlaybackContext.contextOrNull() ?: return null
    return runCatching {
        val parsed = if (uri.startsWith("content://") || uri.startsWith("file://")) {
            Uri.parse(uri)
        } else {
            Uri.fromFile(java.io.File(uri))
        }
        context.contentResolver.openInputStream(parsed)?.use { input ->
            // 字幕文件很小，限制 4MB，避免误选视频文件把内存打满
            val bytes = input.readNBytes(4 * 1024 * 1024)
            decodeText(bytes)
        }
    }.getOrNull()
}

private fun decodeText(bytes: ByteArray): String {
    // 无 BOM 时按 UTF-8 兜底：SRT/ASS 绝大多数是 UTF-8 或 GBK，GBK 会在替换字符处显现
    return when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }
}
