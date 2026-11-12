package com.moontvplus.phone.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `裸域名默认补 https`() {
        val address = ServerAddress.parse("tv.example.com")
        assertEquals("https://tv.example.com/", address?.toStorage())
        assertEquals("https://tv.example.com", address?.origin)
        assertEquals("", address?.pathPrefix)
    }

    @Test
    fun `保留显式 http 与端口`() {
        val address = ServerAddress.parse("http://192.168.1.10:3000/")
        assertEquals("http://192.168.1.10:3000/", address?.toStorage())
        assertEquals("http://192.168.1.10:3000", address?.origin)
    }

    @Test
    fun `默认端口不写进 origin`() {
        val address = ServerAddress.parse("https://example.com:443/")
        assertEquals("https://example.com", address?.origin)
    }

    @Test
    fun `子路径部署保留前缀`() {
        val address = ServerAddress.parse("https://example.com/moontv")
        assertEquals("/moontv", address?.pathPrefix)
        assertEquals("https://example.com/moontv/", address?.toStorage())
    }

    @Test
    fun `误贴页面路径时去掉应用路由段`() {
        assertEquals(
            "https://example.com/",
            ServerAddress.parse("https://example.com/tv")?.toStorage(),
        )
        assertEquals(
            "/moontv/",
            ServerAddress.parse("https://example.com/moontv/play")?.base?.encodedPath,
        )
    }

    @Test
    fun `无 scheme 时 https 优先并回退 http`() {
        val candidates = ServerAddress.candidates("tv.example.com")
        assertEquals(2, candidates.size)
        assertEquals("https", candidates[0].base.scheme)
        assertEquals("http", candidates[1].base.scheme)
    }

    @Test
    fun `显式 scheme 时只给一个候选`() {
        assertEquals(1, ServerAddress.candidates("https://tv.example.com").size)
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(ServerAddress.parse(""))
        assertNull(ServerAddress.parse("   "))
        assertNull(ServerAddress.parseStored(null))
        assertTrue(ServerAddress.candidates("  ").isEmpty())
    }

    @Test
    fun `无协议头的非法值不会抛异常`() {
        // 冒号会被当成端口分隔符，解析失败时整体退化为失败，不应崩溃
        val result = ServerAddress.candidates("::::")
        assertTrue(result.isEmpty() || result.all { it.base.host.isNotEmpty() })
    }
}
