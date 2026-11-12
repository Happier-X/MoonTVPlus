package com.moontvplus.phone.data.media

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class EpisodeUrlResolverTest {

    private val origin = "https://tv.example.com"

    @Test
    fun `服务端已经套好代理时原样使用`() {
        val url = "https://tv.example.com/api/proxy-m3u8?url=https%3A%2F%2Fcdn.example.com%2Fa.m3u8"
        val plan = EpisodeUrlResolver.resolve(origin, url, "demo", proxyMode = false)

        assertNotNull(plan)
        assertEquals(url, plan!!.url)
        assertTrue(plan.isHls)
        assertTrue(plan.viaServer)
    }

    @Test
    fun `proxyMode 的 HLS 走站内代理`() {
        val plan = EpisodeUrlResolver.resolve(
            origin,
            "https://cdn.example.com/show/01.m3u8",
            "demo",
            proxyMode = true,
        )

        assertNotNull(plan)
        assertTrue(plan!!.url.startsWith("$origin/api/proxy/vod/m3u8?url="))
        assertTrue(plan.url.contains("source=demo"))
        assertTrue(plan.isHls)
        assertTrue(plan.viaServer)
    }

    @Test
    fun `普通 m3u8 直连`() {
        val plan = EpisodeUrlResolver.resolve(
            origin,
            "https://cdn.example.com/show/01.m3u8?token=abc",
            "demo",
            proxyMode = false,
        )

        assertNotNull(plan)
        assertEquals("https://cdn.example.com/show/01.m3u8?token=abc", plan!!.url)
        assertTrue(plan.isHls)
        assertFalse(plan.viaServer)
        assertFalse(plan.unsupported)
    }

    @Test
    fun `mp4 直连且不是 HLS`() {
        val plan = EpisodeUrlResolver.resolve(origin, "https://cdn/x.mp4", "demo", false)
        assertEquals(false, plan?.isHls)
        assertEquals(false, plan?.unsupported)
    }

    @Test
    fun `avi 这类容器标记为不支持`() {
        val plan = EpisodeUrlResolver.resolve(origin, "https://cdn/x.avi", "demo", false)
        assertTrue(plan!!.unsupported)
    }

    @Test
    fun `站内相对路径补成绝对地址`() {
        val plan = EpisodeUrlResolver.resolve(origin, "/api/netdisk/quark/play?id=1", "demo", false)
        assertEquals("$origin/api/netdisk/quark/play?id=1", plan?.url)
    }

    @Test
    fun `空地址返回 null`() {
        assertNull(EpisodeUrlResolver.resolve(origin, null, "demo", false))
        assertNull(EpisodeUrlResolver.resolve(origin, "   ", "demo", false))
        assertNull(EpisodeUrlResolver.resolve(origin, "magnet:?xt=abc", "demo", false))
    }

    @Test
    fun `能从代理地址里解出原始直链`() {
        val original = "https://cdn.example.com/show/01.m3u8"
        val proxied = "$origin/api/proxy-m3u8?url=" +
            java.net.URLEncoder.encode(original, "UTF-8")
        assertEquals(original, EpisodeUrlResolver.unwrapProxyUrl(proxied))
    }
}
