package com.moontvplus.phone.feature.search

import com.moontvplus.phone.core.model.SearchResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class SearchAggregatorTest {

    private fun item(
        title: String,
        source: String,
        year: String = "2024",
        className: String? = "movie",
        episodes: Int = 1,
        weight: Double = 0.0,
    ) = SearchResult(
        id = "$source-id",
        title = title,
        source = source,
        sourceName = source,
        year = year,
        className = className,
        episodes = List(episodes) { "https://cdn/$it.m3u8" },
        weight = weight,
    )

    @Test
    fun `同名同年的不同源聚合成一条`() {
        val results = listOf(
            item("流浪地球2", "a", episodes = 1, weight = 5.0),
            item("流浪地球 2", "b", episodes = 2, weight = 10.0),
            item("流浪地球2", "c", episodes = 1, weight = 1.0),
        )

        val groups = SearchAggregator.aggregate(results)
        assertEquals(1, groups.size)
        assertEquals(3, groups.first().sourceCount)
        // 权重最高的排在最前
        assertEquals("b", groups.first().primary.source)
    }

    @Test
    fun `年份或类型不同不合并`() {
        val results = listOf(
            item("三体", "a", year = "2023"),
            item("三体", "b", year = "2024"),
            item("三体", "c", year = "2023", className = "tv"),
        )

        val groups = SearchAggregator.aggregate(results)
        assertEquals(3, groups.size)
    }

    @Test
    fun `组间按权重和源数量排序`() {
        val results = listOf(
            item("低权重多源", "a", weight = 1.0),
            item("低权重多源", "b", weight = 1.0),
            item("高权重单源", "c", weight = 9.0),
        )

        val groups = SearchAggregator.aggregate(results)
        assertEquals("高权重单源", groups.first().title)
        assertEquals(2, groups.size)
    }

    @Test
    fun `空标题被丢弃`() {
        val groups = SearchAggregator.aggregate(listOf(item("", "a"), item("有标题", "b")))
        assertEquals(1, groups.size)
        assertEquals("有标题", groups.first().title)
    }

    @Test
    fun `类型识别`() {
        assertEquals("综艺", SearchAggregator.typeLabelOf(item("x", "a", className = "综艺")))
        assertEquals("剧集", SearchAggregator.typeLabelOf(item("x", "a", className = "国产剧")))
        assertEquals("短剧", SearchAggregator.typeLabelOf(item("x", "a", className = "短剧")))
        assertEquals("电影", SearchAggregator.typeLabelOf(item("x", "a", className = "动作片")))
        assertEquals(null, SearchAggregator.typeLabelOf(item("x", "a", className = null)))
    }

    @Test
    fun `标题规范化忽略空白与标点`() {
        assertEquals("庆余年第二季", SearchAggregator.normalizeTitle(" 庆余年 第二季 "))
        assertEquals("spiderman", SearchAggregator.normalizeTitle("Spider-Man"))
        assertTrue(SearchAggregator.aggregate(emptyList()).isEmpty())
    }
}
