package com.moontvplus.phone.cast

/**
 * 极简 XML 扫描器。
 *
 * DLNA 的设备描述、SOAP 响应都是小型、结构固定的 XML，引入完整 XML 解析器
 * 反而会带进 Android 上的 SAX/权限问题（以及不必要的依赖）。这里只做三件事：
 * 取标签文本、取同名块、取属性。所有函数都对命名空间前缀宽容。
 */
object XmlScan {

    fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** 第一个 `<tag>text</tag>` 的文本（自动去 CDATA / 命名空间前缀）。 */
    fun text(xml: String, tag: String): String? {
        val regex = Regex(
            """<(?:[\w.-]+:)?${Regex.escape(tag)}\b[^>]*>(.*?)</(?:[\w.-]+:)?${Regex.escape(tag)}>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val raw = regex.find(xml)?.groupValues?.get(1) ?: return null
        return unescape(stripCdata(raw)).trim().ifEmpty { null }
    }

    /** 所有 `<tag>...</tag>` 块（含标签本身）。 */
    fun blocks(xml: String, tag: String): List<String> {
        val regex = Regex(
            """<(?:[\w.-]+:)?${Regex.escape(tag)}\b[^>]*>.*?</(?:[\w.-]+:)?${Regex.escape(tag)}>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        return regex.findAll(xml).map { it.value }.toList()
    }

    /** 取 `<tag ... attr="v">` 的属性。 */
    fun attribute(xml: String, tag: String, attr: String): String? {
        val regex = Regex(
            """<(?:[\w.-]+:)?${Regex.escape(tag)}\b([^>]*)>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val attrs = regex.find(xml)?.groupValues?.get(1) ?: return null
        val attrRegex = Regex("""(?:^|\s)${Regex.escape(attr)}\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return attrRegex.find(attrs)?.groupValues?.get(1)?.let(::unescape)
    }

    private fun stripCdata(raw: String): String = raw
        .replace(Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)) { it.groupValues[1] }
}

/** 一台可投屏的 DLNA 渲染器。 */
data class CastDevice(
    /** UDN，形如 `uuid:xxxx-...` */
    val id: String,
    val name: String,
    /** 设备描述 URL（SSDP 的 LOCATION） */
    val location: String,
    val controlUrl: String = "",
    val modelName: String = "",
    val manufacturer: String = "",
)

enum class CastTransportState {
    UNKNOWN,
    STOPPED,
    PLAYING,
    PAUSED,
    TRANSITIONING,
    NO_MEDIA;

    val isPlaying: Boolean get() = this == PLAYING

    companion object {
        fun of(raw: String?): CastTransportState = when (raw?.trim()?.uppercase()) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING", "PAUSED" -> PAUSED
            "STOPPED" -> STOPPED
            "TRANSITIONING" -> TRANSITIONING
            "NO_MEDIA_PRESENT" -> NO_MEDIA
            else -> UNKNOWN
        }
    }
}

/** 投屏状态。UI 用它显示「正在投屏到 xxx」。 */
data class CastState(
    val searching: Boolean = false,
    val devices: List<CastDevice> = emptyList(),
    val connected: CastDevice? = null,
    val transport: CastTransportState = CastTransportState.UNKNOWN,
    val castingTitle: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val message: String? = null,
) {
    val active: Boolean get() = connected != null
}

/**
 * DLNA / UPnP AVTransport 协议文本。
 *
 * 只做 SSDP 发现 + SOAP 控制的必要子集；播放、暂停、停止、seek、查询状态
 * 覆盖了遥控器上的全部操作。所有报文都是纯字符串，可以在 commonTest 里断言。
 */
object DlnaProtocol {

    const val SSDP_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val SERVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** 构造 SSDP M-SEARCH。多播报文必须是 ASCII，不能带 BOM。 */
    fun buildMSearch(
        serviceType: String = SERVICE_TYPE,
        mxSeconds: Int = 3,
        userAgent: String = "Android/1.0 UPnP/1.1 MoonTVPlus/1.0",
    ): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: ${mxSeconds.coerceIn(1, 5)}\r\n")
        append("ST: $serviceType\r\n")
        append("USER-AGENT: $userAgent\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    /** 解析 SSDP 响应头。返回 location + usn/st。 */
    data class SearchResponse(val location: String, val usn: String, val serviceType: String, val server: String)

    fun parseSearchResponse(payload: String): SearchResponse? {
        if (!payload.startsWith("HTTP/1.1 200", ignoreCase = true) && !payload.startsWith("HTTP/1.0 200")) {
            return null
        }
        val headers = payload.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
            }
            .toMap()
        val location = headers["location"] ?: return null
        if (!location.startsWith("http", ignoreCase = true)) return null
        return SearchResponse(
            location = location,
            usn = headers["usn"].orEmpty(),
            serviceType = headers["st"].orEmpty(),
            server = headers["server"].orEmpty(),
        )
    }

    /**
     * 从设备描述 XML 里取出名字与 AVTransport 控制地址。
     *
     * [descriptionUrl] 用于把相对的 `<controlURL>` 补成绝对地址。
     */
    fun parseDeviceDescription(xml: String, descriptionUrl: String): CastDevice? {
        val name = XmlScan.text(xml, "friendlyName") ?: return null
        val udn = XmlScan.text(xml, "UDN") ?: XmlScan.text(xml, "udn") ?: descriptionUrl
        val modelName = XmlScan.text(xml, "modelName").orEmpty()
        val manufacturer = XmlScan.text(xml, "manufacturer").orEmpty()
        val service = XmlScan.blocks(xml, "service").firstOrNull { block ->
            (XmlScan.text(block, "serviceType") ?: "").contains("AVTransport", ignoreCase = true)
        }
        val control = service?.let { XmlScan.text(it, "controlURL") }
            ?.let { resolveUrl(descriptionUrl, it) }
            .orEmpty()
        return CastDevice(
            id = udn,
            name = name,
            location = descriptionUrl,
            controlUrl = control,
            modelName = modelName,
            manufacturer = manufacturer,
        )
    }

    /** 把 controlURL 补成绝对地址（设备经常返回 `/upnp/control/AVTransport1`）。 */
    fun resolveUrl(base: String, relative: String): String {
        val trimmed = relative.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        val uri = UrlParts.parse(base) ?: return trimmed
        if (trimmed.startsWith("/")) return "${uri.scheme}://${uri.authority}$trimmed"
        val directory = uri.path.substringBeforeLast('/', "")
        return "${uri.scheme}://${uri.authority}$directory/$trimmed"
    }

    fun soapActionHeader(serviceType: String, action: String) = "\"$serviceType#$action\""

    /** 生成 SOAP 信封。[arguments] 按顺序拼进 action 元素。 */
    fun buildEnvelope(serviceType: String, action: String, arguments: List<Pair<String, String>>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
            append("<s:Envelope s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" ")
            append("xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">")
            append("<s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
            arguments.forEach { (key, value) ->
                append('<').append(key).append('>')
                append(XmlScan.escape(value))
                append("</").append(key).append('>')
            }
            append("</u:").append(action).append('>')
            append("</s:Body></s:Envelope>")
        }

    fun buildSetAvTransportUri(uri: String, metadata: String): String = buildEnvelope(
        AV_TRANSPORT, "SetAVTransportURI",
        listOf("InstanceID" to "0", "CurrentURI" to uri, "CurrentURIMetaData" to metadata),
    )

    fun buildPlay(): String = buildEnvelope(AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
    fun buildPause(): String = buildEnvelope(AV_TRANSPORT, "Pause", listOf("InstanceID" to "0"))
    fun buildStop(): String = buildEnvelope(AV_TRANSPORT, "Stop", listOf("InstanceID" to "0"))

    /** 单位 "REL_TIME"，格式 `HH:MM:SS`。 */
    fun buildSeek(positionMs: Long): String = buildEnvelope(
        AV_TRANSPORT, "Seek",
        listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to formatDuration(positionMs)),
    )

    fun buildGetTransportInfo(): String = buildEnvelope(AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"))
    fun buildGetPositionInfo(): String = buildEnvelope(AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"))

    /** DIDL-Lite 元数据。部分电视不带元数据也能播，但带上的兼容性明显更好。 */
    fun buildDidl(title: String, url: String, mimeType: String): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>").append(XmlScan.escape(title)).append("</dc:title>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("<res protocolInfo=\"http-get:*:").append(XmlScan.escape(mimeType)).append(":*\">")
        append(XmlScan.escape(url)).append("</res>")
        append("</item></DIDL-Lite>")
    }

    /** 猜投屏用的 MIME：HLS 必须写明，否则不少电视会当普通文件下载失败。 */
    fun mimeTypeOf(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            clean.endsWith(".mpd") -> "application/dash+xml"
            clean.endsWith(".webm") -> "video/webm"
            clean.endsWith(".mkv") -> "video/x-matroska"
            clean.endsWith(".mov") -> "video/quicktime"
            clean.endsWith(".flv") -> "video/x-flv"
            else -> "video/mp4"
        }
    }

    fun parseTransportState(soapResponse: String): CastTransportState =
        CastTransportState.of(XmlScan.text(soapResponse, "CurrentTransportState"))

    /** `HH:MM:SS` / `HH:MM:SS.mmm` / `NOT_IMPLEMENTED` */
    fun parseDuration(raw: String?): Long {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || !value.contains(':')) return 0L
        val parts = value.split(':')
        if (parts.size != 3) return 0L
        val hours = parts[0].toLongOrNull() ?: return 0L
        val minutes = parts[1].toLongOrNull() ?: return 0L
        val seconds = parts[2].toDoubleOrNull() ?: return 0L
        return ((hours * 3600 + minutes * 60) * 1000L + (seconds * 1000).toLong()).coerceAtLeast(0L)
    }

    fun formatDuration(positionMs: Long): String {
        val total = (positionMs.coerceAtLeast(0L)) / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    data class PositionSnapshot(val positionMs: Long, val durationMs: Long)

    fun parsePositionInfo(soapResponse: String): PositionSnapshot = PositionSnapshot(
        positionMs = parseDuration(XmlScan.text(soapResponse, "RelTime")),
        durationMs = parseDuration(
            XmlScan.text(soapResponse, "TrackDuration")
                ?: XmlScan.text(soapResponse, "CurrentTrackDuration"),
        ),
    )

    internal data class UrlParts(val scheme: String, val authority: String, val path: String) {
        companion object {
            fun parse(url: String): UrlParts? {
                val schemeEnd = url.indexOf("://")
                if (schemeEnd <= 0) return null
                val scheme = url.substring(0, schemeEnd)
                val rest = url.substring(schemeEnd + 3)
                val pathStart = rest.indexOf('/')
                val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
                val path = if (pathStart < 0) "/" else rest.substring(pathStart)
                return UrlParts(scheme, authority, path)
            }
        }
    }
}
