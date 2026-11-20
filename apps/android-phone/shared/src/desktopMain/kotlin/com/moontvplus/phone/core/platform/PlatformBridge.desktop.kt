package com.moontvplus.phone.core.platform

import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLDecoder

actual fun openExternalPlayer(url: String): Boolean {
    if (url.isBlank()) return false
    return runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            true
        }
    }.getOrDefault(false)
}

actual fun readLocalTextSource(uri: String): String? {
    return runCatching {
        val file = when {
            uri.startsWith("file://") -> File(URI(uri))
            uri.startsWith("file:") -> File(URLDecoder.decode(uri.removePrefix("file:"), "UTF-8"))
            else -> File(uri)
        }
        if (!file.isFile) return null
        file.readText()
    }.getOrNull()
}
