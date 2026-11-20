package com.moontvplus.phone.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubtitleParserTest {

    @Test
    fun parsesSrtWithCommaAndCrlf() {
        val srt = """
            1
            00:00:01,500 --> 00:00:04,000
            第一行
            第二行

            2
            00:00:05,000 --> 00:00:06,250
            下一句
        """.trimIndent().replace("\n", "\r\n")
        val document = SubtitleParser.parse(srt, "movie.srt")
        assertEquals(SubtitleDocument.Format.SRT, document.format)
        assertEquals(2, document.cues.size)
        assertEquals(1_500L, document.cues[0].startMs)
        assertEquals("第一行\n第二行", document.cues[0].text)
        assertTrue(document.cues[0].visibleAt(2_000L))
    }

    @Test
    fun parsesWebVttWithCueSettings() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000 align:start position:10%
            <i>斜体字幕</i>

            00:02.000 --> 00:03.500
            短格式时间
        """.trimIndent()
        val document = SubtitleParser.parse(vtt, "sub.vtt")
        assertEquals(SubtitleDocument.Format.VTT, document.format)
        assertEquals(2, document.cues.size)
        assertEquals("斜体字幕", document.cues[0].text)
        assertEquals(2_000L, document.cues[1].startMs, "没有小时段的 MM:SS.mmm 也要能解析")
    }

    @Test
    fun parsesAssDialogueWithCommasAndLineBreaks() {
        val ass = """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\an8}第一行\N第二行,带逗号
        """.trimIndent()
        val document = SubtitleParser.parse(ass, "sub.ass")
        assertEquals(SubtitleDocument.Format.ASS, document.format)
        assertEquals(1, document.cues.size)
        assertEquals(1_000L, document.cues[0].startMs)
        assertEquals(3_500L, document.cues[0].endMs)
        assertEquals("第一行\n第二行,带逗号", document.cues[0].text)
    }

    @Test
    fun cueAtUsesBinarySearchWindow() {
        val document = SubtitleParser.parse(
            """
            1
            00:00:01,000 --> 00:00:02,000
            A

            2
            00:00:02,000 --> 00:00:03,000
            B
            """.trimIndent(),
            "a.srt",
        )
        assertEquals("A", document.cueAt(1_500L)?.text)
        assertEquals("B", document.cueAt(2_500L)?.text)
        assertEquals(null, document.cueAt(500L))
        assertEquals(null, document.cueAt(9_000L))
    }

    @Test
    fun unknownContentIsReportedInsteadOfCrashing() {
        val document = SubtitleParser.parse("这不是字幕", "x.txt")
        assertEquals(SubtitleDocument.Format.UNKNOWN, document.format)
        assertTrue(document.empty)
    }

    @Test
    fun detectsMimeTypeFromUrl() {
        assertEquals("text/vtt", SubtitleParser.mimeTypeOf("https://a/b/c.vtt?token=1"))
        assertEquals("text/x-ssa", SubtitleParser.mimeTypeOf("https://a/b/c.ass"))
        assertEquals("application/x-subrip", SubtitleParser.mimeTypeOf("https://a/b/c.srt"))
    }

    @Test
    fun timestampParsingHandlesMissingMillis() {
        assertEquals(65_000L, SubtitleParser.parseTimestamp("01:05"))
        assertEquals(3_600_000L, SubtitleParser.parseTimestamp("01:00:00.000"))
        assertNotNull(SubtitleParser.parseTimestamp("00:00:00.500"))
    }
}
