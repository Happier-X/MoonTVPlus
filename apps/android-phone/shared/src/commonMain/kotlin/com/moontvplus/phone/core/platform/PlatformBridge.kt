package com.moontvplus.phone.core.platform

/**
 * 用系统播放器/浏览器打开一集。
 *
 * 桌面端还没有内嵌播放器时，详情页与播放页的「外部打开」入口走这里；
 * Android 用 ACTION_VIEW 交给系统，桌面用默认浏览器。
 */
expect fun openExternalPlayer(url: String): Boolean

/**
 * 读取本地字幕文件。
 *
 * [uri] 可以是 `content://`（Android 文件选择器）、`file://` 或普通绝对路径。
 * 解析失败返回 null，界面据此提示「无法读取该字幕文件」，而不是静默失败。
 */
expect fun readLocalTextSource(uri: String): String?
