package com.moontvplus.phone.subtitles

/** 一条字幕。[startMs, endMs) 期间显示。 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    fun visibleAt(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/** 解析后的字幕文件。 */
data class SubtitleDocument(
    val cues: List<SubtitleCue>,
    val format: Format,
) {
    enum class Format { SRT, VTT, ASS, UNKNOWN }

    val empty: Boolean get() = cues.isEmpty()

    /** 二分查找当前应显示的字幕，O(log n)。 */
    fun cueAt(positionMs: Long): SubtitleCue? {
        var low = 0
        var high = cues.lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> low = mid + 1
                else -> return cue
            }
        }
        return null
    }
}

/**
 * SRT / WebVTT / ASS 解析。
 *
 * 不引入字幕库：这三类字幕格式的公共子集很小，自己解析能控制容错
 * （时间戳缺毫秒、行尾 CRLF、VTT 的 cue settings、ASS 的样式覆盖都能吃下），
 * 也方便在 commonTest 里直接覆盖。
 */
object SubtitleParser {

    fun detectFormat(content: String, fileNameOrUrl: String = ""): SubtitleDocument.Format {
        val head = content.take(400).lowercase()
        val name = fileNameOrUrl.lowercase()
        return when {
            head.contains("[script info]") || head.contains("[events]") || name.endsWith(".ass") ||
                name.endsWith(".ssa") -> SubtitleDocument.Format.ASS
            head.startsWith("webvtt") || name.endsWith(".vtt") -> SubtitleDocument.Format.VTT
            name.endsWith(".srt") || head.contains("-->") -> SubtitleDocument.Format.SRT
            else -> SubtitleDocument.Format.UNKNOWN
        }
    }

    fun parse(content: String, fileNameOrUrl: String = ""): SubtitleDocument {
        val normalized = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return when (detectFormat(normalized, fileNameOrUrl)) {
            SubtitleDocument.Format.ASS -> SubtitleDocument(parseAss(normalized), SubtitleDocument.Format.ASS)
            SubtitleDocument.Format.VTT -> SubtitleDocument(parseBlocks(normalized, vtt = true), SubtitleDocument.Format.VTT)
            SubtitleDocument.Format.SRT -> SubtitleDocument(parseBlocks(normalized, vtt = false), SubtitleDocument.Format.SRT)
            SubtitleDocument.Format.UNKNOWN -> SubtitleDocument(emptyList(), SubtitleDocument.Format.UNKNOWN)
        }
    }

    private fun parseBlocks(text: String, vtt: Boolean): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = text.split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.isEmpty()) continue
            val timeIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeIndex < 0) continue
            val times = lines[timeIndex].split("-->")
            if (times.size < 2) continue
            val start = parseTimestamp(times[0].trim()) ?: continue
            // VTT 允许在结束时间后追加 cue settings（align:start 等），只取第一段
            val end = parseTimestamp(times[1].trim().substringBefore(' ')) ?: continue
            if (end <= start) continue
            val body = lines.drop(timeIndex + 1)
                .joinToString("\n")
                .let { stripTags(it) }
                .trim()
            if (body.isEmpty()) continue
            cues += SubtitleCue(start, end, body)
        }
        return cues.sortedBy { it.startMs }
    }

    /** 支持 `HH:MM:SS,mmm`、`HH:MM:SS.mmm`、`MM:SS.mmm` 以及缺毫秒的写法。 */
    fun parseTimestamp(raw: String): Long? {
        val cleaned = raw.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(':')
        val (hours, minutes, secondsPart) = when (parts.size) {
            3 -> Triple(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, parts[2])
            2 -> Triple(0L, parts[0].toLongOrNull() ?: return null, parts[1])
            1 -> Triple(0L, 0L, parts[0])
            else -> return null
        }
        val seconds = secondsPart.toDoubleOrNull() ?: return null
        return ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()).coerceAtLeast(0L)
    }

    /** ASS 时间：`H:MM:SS.cc`（百分秒） */
    private fun parseAssTimestamp(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val lines = text.lines()
        val eventsStart = lines.indexOfFirst { it.trim().equals("[Events]", ignoreCase = true) }
        if (eventsStart < 0) return emptyList()
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val cues = mutableListOf<SubtitleCue>()
        for (line in lines.drop(eventsStart + 1)) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Format:", ignoreCase = true) -> {
                    fields = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                }
                trimmed.startsWith("Dialogue:", ignoreCase = true) -> {
                    val payload = trimmed.substringAfter(':').trimStart()
                    // Text 是最后一个字段且可以包含逗号：按字段数限制切分
                    val values = payload.split(',', limit = fields.size)
                    if (values.size < fields.size) continue
                    val startIndex = fields.indexOf("start")
                    val endIndex = fields.indexOf("end")
                    val textIndex = fields.indexOf("text")
                    if (startIndex < 0 || endIndex < 0 || textIndex < 0) continue
                    val start = parseAssTimestamp(values[startIndex]) ?: continue
                    val end = parseAssTimestamp(values[endIndex]) ?: continue
                    if (end <= start) continue
                    val body = stripTags(
                        values[textIndex]
                            .replace("\\N", "\n")
                            .replace("\\n", "\n")
                            .replace("\\h", " "),
                    ).trim()
                    if (body.isEmpty()) continue
                    cues += SubtitleCue(start, end, body)
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /** 去掉 `<i>`、`{\an8}` 之类的样式标记，保留可读文字。 */
    private fun stripTags(text: String): String = text
        .replace(Regex("""<[^>]+>"""), "")
        .replace(Regex("""\{[^}]*}"""), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    /** 按 URL 后缀猜 MIME，media3 的 SubtitleConfiguration 要它。 */
    fun mimeTypeOf(urlOrName: String): String {
        val clean = urlOrName.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".vtt") -> "text/vtt"
            clean.endsWith(".ass") || clean.endsWith(".ssa") -> "text/x-ssa"
            clean.endsWith(".ttml") || clean.endsWith(".xml") -> "application/ttml+xml"
            else -> "application/x-subrip"
        }
    }

    fun labelOf(urlOrName: String, fallback: String = "外挂字幕"): String {
        val name = urlOrName.substringBefore('?').substringAfterLast('/')
        return name.ifBlank { fallback }.take(48)
    }
}
