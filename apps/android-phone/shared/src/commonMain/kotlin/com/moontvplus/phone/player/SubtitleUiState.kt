package com.moontvplus.phone.player

import com.moontvplus.phone.core.storage.SubtitleAppearance
import com.moontvplus.phone.subtitles.SubtitleCue

/** 字幕面板里的一项。内嵌轨来自播放器，外挂轨来自本地/网络加载。 */
data class SubtitleTrackInfo(
    val id: String,
    val label: String,
    val isExternal: Boolean,
    val language: String? = null,
)

/** 外挂字幕 + 外观。内嵌字幕仍由播放器自己渲染（Android 的 PlayerView）。 */
data class SubtitleUiState(
    val external: List<SubtitleTrackInfo> = emptyList(),
    val cuesById: Map<String, List<SubtitleCue>> = emptyMap(),
    val activeExternalId: String? = null,
    val appearance: SubtitleAppearance = SubtitleAppearance(),
    val message: String? = null,
    val loading: Boolean = false,
) {
    /** 当前应显示的外挂字幕文本（没有就返回 null）。 */
    fun cueAt(positionMs: Long): SubtitleCue? {
        val id = activeExternalId ?: return null
        val cues = cuesById[id] ?: return null
        val shifted = positionMs - appearance.delayMs
        return cues.binarySearch(shifted)
    }

    private fun List<SubtitleCue>.binarySearch(positionMs: Long): SubtitleCue? {
        var low = 0
        var high = lastIndex
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = this[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> low = mid + 1
                else -> return cue
            }
        }
        return null
    }
}
