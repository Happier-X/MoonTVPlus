package com.moontvplus.phone.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 在 Android 运行时（ICU 正则）上跑一遍字幕解析。
 *
 * 1.9.6 的外挂字幕就是在这里炸的：`\{[^}]*}` 在桌面 JVM 上合法，
 * 但 Android 的 ICU 正则把结尾孤立的 `}` 当非法量词，加载字幕直接闪退。
 * 桌面单测跑不出这个问题，所以需要一个 Android 侧的用例守着。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubtitleParserAndroidTest {

    @Test
    fun srtWithStyleTagsParsesOnAndroid() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            <i>斜体</i>{\an8}正片开始

        """.trimIndent()
        val document = SubtitleParser.parse(srt, "movie.srt")
        assertEquals(1, document.cues.size)
        assertEquals("斜体正片开始", document.cues.single().text)
    }

    @Test
    fun assDialogueTagsAreStrippedOnAndroid() {
        val ass = """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\an8\pos(1,2)}第一行\N第二行
        """.trimIndent()
        val document = SubtitleParser.parse(ass, "sub.ass")
        assertTrue(document.cues.isNotEmpty())
        assertEquals("第一行\n第二行", document.cues.single().text)
    }

    @Test
    fun plainSubtitleWithoutTagsParsesOnAndroid() {
        val srt = """
            1
            00:00:00,000 --> 00:00:30,000
            字幕回归测试 SUBTITLE OK

        """.trimIndent()
        assertEquals("字幕回归测试 SUBTITLE OK", SubtitleParser.parse(srt, "test.srt").cues.single().text)
    }
}
