package com.moontvplus.phone.cast

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DLNA / UPnP 投屏客户端。
 *
 * 只做「发现 + AVTransport 控制」这条最短路径：
 * 1. SSDP M-SEARCH 多播发现 MediaRenderer；
 * 2. 拉取设备描述 XML，取 AVTransport 的 controlURL；
 * 3. 用 SOAP 发 SetAVTransportURI / Play / Pause / Stop / Seek / 查询状态。
 *
 * 不依赖 Cling 之类的重型库：UDP 一个 socket、HTTP 走共享 OkHttp 就够了，
 * 报文拼装在 `DlnaProtocol` 里且可单测。
 */
class DlnaClient(
    private val client: OkHttpClient,
) {

    /** 发现局域网内的渲染器；[timeoutMs] 是总等待时间，不是每条设备的超时。 */
    suspend fun discover(timeoutMs: Long = 3_000L): Result<List<CastDevice>> = withContext(Dispatchers.IO) {
        runCatching {
            val locations = linkedSetOf<String>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val message = DlnaProtocol.buildMSearch()
                val address = InetAddress.getByName(DlnaProtocol.SSDP_HOST)
                val deadline = System.currentTimeMillis() + timeoutMs
                // 多播会丢包：隔 400ms 重发一次，最多 3 次
                repeat(3) {
                    runCatching {
                        socket.send(DatagramPacket(message, message.size, address, DlnaProtocol.SSDP_PORT))
                    }
                    val roundDeadline = minOf(deadline, System.currentTimeMillis() + 500)
                    val buffer = ByteArray(4_096)
                    while (System.currentTimeMillis() < roundDeadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                        if (!received) continue
                        val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        DlnaProtocol.parseSearchResponse(payload)?.let { locations += it.location }
                    }
                    if (System.currentTimeMillis() >= deadline) return@repeat
                }
            }
            locations.mapNotNull { location -> fetchDevice(location).getOrNull() }
                .distinctBy { it.id }
                .sortedBy { it.name.lowercase() }
        }
    }

    suspend fun fetchDevice(location: String): Result<CastDevice> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(location).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw CastException("设备描述读取失败（HTTP ${response.code}）")
                val xml = response.body?.string().orEmpty()
                DlnaProtocol.parseDeviceDescription(xml, location)
                    ?: throw CastException("设备不支持 AVTransport")
            }
        }
    }

    suspend fun setAvTransportUri(device: CastDevice, url: String, title: String): Result<Unit> =
        soap(device, DlnaProtocol.buildSetAvTransportUri(url, DlnaProtocol.buildDidl(title, url, DlnaProtocol.mimeTypeOf(url))))

    suspend fun setAvTransportUriRaw(device: CastDevice, url: String): Result<Unit> =
        soap(device, DlnaProtocol.buildSetAvTransportUri(url, ""))

    suspend fun play(device: CastDevice): Result<Unit> = soap(device, DlnaProtocol.buildPlay())

    suspend fun pause(device: CastDevice): Result<Unit> = soap(device, DlnaProtocol.buildPause())

    suspend fun stop(device: CastDevice): Result<Unit> = soap(device, DlnaProtocol.buildStop())

    suspend fun seek(device: CastDevice, positionMs: Long): Result<Unit> =
        soap(device, DlnaProtocol.buildSeek(positionMs))

    suspend fun transportState(device: CastDevice): Result<CastTransportState> = withContext(Dispatchers.IO) {
        runCatching {
            val body = post(device, "GetTransportInfo", DlnaProtocol.buildGetTransportInfo())
            DlnaProtocol.parseTransportState(body)
        }
    }

    suspend fun positionInfo(device: CastDevice): Result<DlnaProtocol.PositionSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val body = post(device, "GetPositionInfo", DlnaProtocol.buildGetPositionInfo())
            DlnaProtocol.parsePositionInfo(body)
        }
    }

    private suspend fun soap(device: CastDevice, envelope: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val action = Regex("""<u:(\w+)""").find(envelope)?.groupValues?.get(1) ?: "Play"
            if (device.controlUrl.isBlank()) throw CastException("设备缺少 AVTransport 控制地址")
            post(device, action, envelope)
            Unit
        }
    }

    private fun post(device: CastDevice, action: String, envelope: String): String {
        val request = Request.Builder()
            .url(device.controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPACTION", DlnaProtocol.soapActionHeader(DlnaProtocol.AV_TRANSPORT, action))
            .header("User-Agent", USER_AGENT)
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // UPnP 的错误详情在 SOAP Fault 里，能取到就给用户看
                val detail = XmlScan.text(body, "errorCode")
                throw CastException(
                    if (detail != null) "投屏失败（错误码 $detail）" else "投屏失败（HTTP ${response.code}）",
                )
            }
            return body
        }
    }

    class CastException(message: String) : Exception(message)

    private companion object {
        const val USER_AGENT = "MoonTVPlus/1.0 UPnP/1.1 DLNADOC/1.50"
    }
}
