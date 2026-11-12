package com.moontvplus.phone.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `脏字段也能解析`() {
        // id 是数字、year 是数字、episodes 是字符串、rate 是数字
        val raw = """
            {
              "id": 12345,
              "title": "测试剧",
              "poster": "https://img.example.com/a.jpg",
              "episodes": "https://cdn.example.com/1.m3u8#https://cdn.example.com/2.m3u8",
              "source": "demo",
              "source_name": "示例源",
              "year": 2024,
              "douban_id": "67890",
              "proxyMode": true
            }
        """.trimIndent()

        val item = json.decodeFromString(SearchResult.serializer(), raw)
        assertEquals("12345", item.id)
        assertEquals("2024", item.year)
        assertEquals(67890L, item.doubanId)
        assertEquals(1, item.episodes.size)
        assertEquals("demo+12345", item.key)
        assertTrue(item.proxyMode)
    }

    @Test
    fun `缺字段用默认值`() {
        val item = json.decodeFromString(SearchResult.serializer(), """{"title":"只有标题"}""")
        assertEquals("", item.id)
        assertTrue(item.episodes.isEmpty())
        assertEquals(0L, item.doubanId)
    }

    @Test
    fun `播放记录 map 能按 key 拆出源和 id`() {
        val raw = """
            {
              "demo+1001": {
                "title": "某剧",
                "source_name": "示例源",
                "year": "2024",
                "cover": "https://img.example.com/a.jpg",
                "index": 3,
                "total_episodes": 24,
                "play_time": 125.5,
                "total_time": 2700,
                "save_time": 1700000000000
              }
            }
        """.trimIndent()

        val records = json.decodeFromString<Map<String, PlayRecord>>(raw)
        val item = ContinueItem(records.keys.first(), records.values.first())
        assertEquals("demo", item.source)
        assertEquals("1001", item.videoId)
        assertEquals(3, item.record.index)
        assertTrue(item.record.progress in 0.04f..0.05f)
    }

    @Test
    fun `豆瓣列表解析`() {
        val raw = """
            {"code":200,"message":"获取成功","list":[
              {"id":"35267208","title":"流浪地球2","poster":"https://img1.doubanio.com/x.jpg","rate":"8.3","year":"2023"}
            ]}
        """.trimIndent()

        val response = json.decodeFromString(DoubanCategoriesResponse.serializer(), raw)
        assertEquals(200, response.code)
        assertEquals(1, response.list.size)
        assertEquals("8.3", response.list.first().rate)
    }

    @Test
    fun `TMDB 海报地址拼装`() {
        val item = TmdbItem(id = 1, title = "某片", posterPath = "/abc.jpg", releaseDate = "2026-03-05")
        assertEquals("https://image.tmdb.org/t/p/w500/abc.jpg", item.posterUrl)
        assertEquals("2026", item.year)
        assertEquals("", TmdbItem(posterPath = null).posterUrl)
    }

    @Test
    fun `Bangumi 取第一个可用海报`() {
        val images = BangumiImages(large = "", common = "https://lain.bgm.tv/c.jpg", grid = "g.jpg")
        assertEquals("https://lain.bgm.tv/c.jpg", images.best)
    }
}
