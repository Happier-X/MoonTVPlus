package com.moontvplus.phone.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DlnaProtocolTest {

    @Test
    fun mSearchRequestIsAsciiAndWellFormed() {
        val payload = DlnaProtocol.buildMSearch().toString(Charsets.US_ASCII)
        assertTrue(payload.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(payload.contains("MAN: \"ssdp:discover\""))
        assertTrue(payload.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(payload.endsWith("\r\n\r\n"))
    }

    @Test
    fun parsesSsdpResponseCaseInsensitively() {
        val response = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.1.10:49152/description.xml
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
            USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1
            SERVER: Linux/4.4 UPnP/1.0
        """.trimIndent()
        val parsed = DlnaProtocol.parseSearchResponse(response)
        assertNotNull(parsed)
        assertEquals("http://192.168.1.10:49152/description.xml", parsed.location)
        assertTrue(parsed.usn.startsWith("uuid:abc"))
    }

    @Test
    fun ignoresNonSuccessAndMissingLocation() {
        assertEquals(null, DlnaProtocol.parseSearchResponse("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"))
        assertEquals(null, DlnaProtocol.parseSearchResponse("HTTP/1.1 200 OK\r\nST: x\r\n"))
    }

    @Test
    fun resolvesRelativeControlUrl() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>客厅电视</friendlyName>
                <manufacturer>Sony</manufacturer>
                <modelName>BRAVIA</modelName>
                <UDN>uuid:sony-1</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/upnp/control/RenderingControl1</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/upnp/control/AVTransport1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val device = DlnaProtocol.parseDeviceDescription(xml, "http://192.168.1.10:49152/description.xml")
        assertNotNull(device)
        assertEquals("客厅电视", device.name)
        assertEquals("uuid:sony-1", device.id)
        assertEquals("http://192.168.1.10:49152/upnp/control/AVTransport1", device.controlUrl)
    }

    @Test
    fun relativeControlUrlWithoutLeadingSlashIsResolvedAgainstDirectory() {
        val xml = """
            <service>
              <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
              <controlURL>control/avt</controlURL>
            </service>
        """.trimIndent()
        val device = DlnaProtocol.parseDeviceDescription(
            "<root><device><friendlyName>盒子</friendlyName><UDN>uuid:x</UDN>$xml</device></root>",
            "http://10.0.0.2:8080/desc/device.xml",
        )
        assertEquals("http://10.0.0.2:8080/desc/control/avt", device?.controlUrl)
    }

    @Test
    fun soapEnvelopeEscapesUriAndMetadata() {
        val metadata = DlnaProtocol.buildDidl("A & B", "http://x/v.mp4?a=1&b=2", "video/mp4")
        val envelope = DlnaProtocol.buildSetAvTransportUri("http://x/v.mp4?a=1&b=2", metadata)
        assertTrue(envelope.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(envelope.contains("<CurrentURI>http://x/v.mp4?a=1&amp;b=2</CurrentURI>"))
        assertTrue(metadata.contains("A &amp; B"))
        assertTrue(metadata.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
    }

    @Test
    fun transportStateIsParsed() {
        val response = """
            <s:Envelope><s:Body><u:GetTransportInfoResponse>
              <CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>
            </u:GetTransportInfoResponse></s:Body></s:Envelope>
        """.trimIndent()
        assertEquals(CastTransportState.PAUSED, DlnaProtocol.parseTransportState(response))
        assertEquals(CastTransportState.UNKNOWN, DlnaProtocol.parseTransportState("<x/>"))
    }

    @Test
    fun positionInfoIsParsed() {
        val response = """
            <s:Envelope><s:Body><u:GetPositionInfoResponse>
              <TrackDuration>01:02:03</TrackDuration>
              <RelTime>00:10:05.500</RelTime>
            </u:GetPositionInfoResponse></s:Body></s:Envelope>
        """.trimIndent()
        val snapshot = DlnaProtocol.parsePositionInfo(response)
        assertEquals(3_723_000L, snapshot.durationMs)
        assertEquals(605_500L, snapshot.positionMs)
    }

    @Test
    fun durationFormattingRoundTrips() {
        assertEquals("01:02:03", DlnaProtocol.formatDuration(3_723_000L))
        assertEquals(3_723_000L, DlnaProtocol.parseDuration("01:02:03"))
        assertEquals(0L, DlnaProtocol.parseDuration("NOT_IMPLEMENTED"))
    }

    @Test
    fun mimeTypeGuessingCoversHls() {
        assertEquals("application/vnd.apple.mpegurl", DlnaProtocol.mimeTypeOf("http://a/b.m3u8?x=1"))
        assertEquals("video/mp4", DlnaProtocol.mimeTypeOf("http://a/b.mp4"))
    }
}
