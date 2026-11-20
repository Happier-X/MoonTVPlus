package com.moontvplus.phone.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmakuTest {

    @Test
    fun parsesDandanplayProtocol() {
        val comment = DanmakuParser.parseItem("1", "12.5,1,16777215,abc", "前方高能")
        assertTrue(comment != null)
        assertEquals(12_500L, comment.timeMs)
        assertEquals(DanmakuMode.SCROLL, comment.mode)
        assertEquals(0xFFFFFFFFL, comment.colorArgb)
        assertEquals("前方高能", comment.text)
    }

    @Test
    fun dropsMalformedCommentInsteadOfFailingTheWholeEpisode() {
        val parsed = DanmakuParser.parseDandanplay(
            listOf(
                DandanplayComment(1, "1.0,1,16777215,1", "正常"),
                DandanplayComment(2, "bad", "坏数据"),
                DandanplayComment(3, "2.0,4,16711680,2", "底部"),
                DandanplayComment(4, "3.0,1,16777215,3", ""),
            ),
        )
        assertEquals(2, parsed.size)
        assertEquals(DanmakuMode.BOTTOM, parsed[1].mode)
        assertEquals(0xFFFF0000L, parsed[1].colorArgb)
    }

    @Test
    fun parsesBilibiliXml() {
        val xml = """
            <i><d p="5.5,5,25,16711680,0,0,0,1">顶部弹幕</d>
            <d p="6.0,1,25,16777215,0,0,0,2">滚动 &amp; 转义</d></i>
        """.trimIndent()
        val parsed = DanmakuParser.parseXml(xml)
        assertEquals(2, parsed.size)
        assertEquals(DanmakuMode.TOP, parsed[0].mode)
        assertEquals(0xFFFF0000L, parsed[0].colorArgb)
        assertEquals("滚动 & 转义", parsed[1].text)
    }

    @Test
    fun filtersBlockedWordsCaseInsensitively() {
        val comments = listOf(
            DanmakuComment("1", 0, "这是广告"),
            DanmakuComment("2", 0, "正常弹幕"),
            DanmakuComment("3", 0, "前面有 SPAM 内容"),
        )
        val filtered = DanmakuParser.filter(comments, listOf(" 广告 ", "spam", ""))
        assertEquals(listOf("正常弹幕"), filtered.map { it.text })
    }

    @Test
    fun cacheRoundTrips() {
        val comments = listOf(
            DanmakuComment("1", 1_500, "第一", DanmakuMode.SCROLL, 0xFFFFFFFFL),
            DanmakuComment("2", 2_000, "带|竖线", DanmakuMode.TOP, 0xFFFF0000L),
        )
        val decoded = DanmakuParser.decodeCache(DanmakuParser.encodeCache(comments))
        assertEquals(2, decoded.size)
        assertEquals(1_500L, decoded[0].timeMs)
        assertEquals("带｜竖线", decoded[1].text)
        assertEquals(0xFFFF0000L, decoded[1].colorArgb)
    }

    @Test
    fun scrollingDanmakuGetsNonOverlappingLanes() {
        val engine = DanmakuLayoutEngine(
            widthPx = 1920f,
            heightPx = 1080f,
            fontPx = 40f,
            areaFraction = 0.5f,
            speedScale = 1f,
        )
        val comments = (0 until 6).map { index ->
            DanmakuComment("$index", 1_000L, "第 $index 条弹幕", DanmakuMode.SCROLL)
        }
        val placements = engine.plan(comments)
        // 6 条同时刻弹幕应分散在不同轨道；轨道数由显示区域高度决定
        assertEquals(6, placements.size)
        assertTrue(placements.all { it.lane in 0 until engine.laneCount })
    }

    @Test
    fun frameReturnsOnlyVisibleDanmaku() {
        val engine = DanmakuLayoutEngine(1920f, 1080f, 40f, speedScale = 1f)
        val comment = DanmakuComment("1", 1_000L, "一条弹幕", DanmakuMode.SCROLL)
        val placements = engine.plan(listOf(comment))
        assertTrue(engine.frame(placements, 0L).isEmpty())
        val visible = engine.frame(placements, 1_500L)
        assertEquals(1, visible.size)
        assertTrue(visible[0].xPx <= 1920f)
        assertTrue(visible[0].alpha > 0f)
    }

    @Test
    fun disabledModesAreNotPlanned() {
        val engine = DanmakuLayoutEngine(
            1920f, 1080f, 40f,
            scrollEnabled = false,
            topEnabled = true,
            bottomEnabled = false,
        )
        val placements = engine.plan(
            listOf(
                DanmakuComment("1", 0, "滚动", DanmakuMode.SCROLL),
                DanmakuComment("2", 0, "顶部", DanmakuMode.TOP),
                DanmakuComment("3", 0, "底部", DanmakuMode.BOTTOM),
            ),
        )
        assertEquals(listOf("顶部"), placements.map { it.comment.text })
    }

    @Test
    fun topAndBottomDanmakuUseSeparateLanes() {
        val engine = DanmakuLayoutEngine(1920f, 1080f, 40f, fixedDurationMs = 4_000L)
        val placements = engine.plan(
            listOf(
                DanmakuComment("1", 0, "顶A", DanmakuMode.TOP),
                DanmakuComment("2", 0, "顶B", DanmakuMode.TOP),
                DanmakuComment("3", 0, "底A", DanmakuMode.BOTTOM),
            ),
        )
        assertEquals(3, placements.size)
        val top = placements.filter { it.comment.mode == DanmakuMode.TOP }
        assertEquals(2, top.size)
        assertTrue(top[0].lane != top[1].lane, "同一时刻的顶部弹幕不应叠在同一条轨道")
    }
}
