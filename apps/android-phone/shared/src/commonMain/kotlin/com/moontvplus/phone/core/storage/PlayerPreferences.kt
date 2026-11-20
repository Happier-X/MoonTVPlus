package com.moontvplus.phone.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** 字幕外观：字号缩放、颜色、背景。数值都做了夹紧，坏配置不会把字幕画到屏幕外。 */
@Serializable
data class SubtitleAppearance(
    val fontScale: Float = 1f,
    val textColorArgb: Long = 0xFFFFFFFFL,
    val backgroundColorArgb: Long = 0x99000000L,
    val bottomPaddingPercent: Float = 0.06f,
    val delayMs: Long = 0L,
) {
    fun sanitized(): SubtitleAppearance = copy(
        fontScale = fontScale.coerceIn(0.6f, 2f),
        bottomPaddingPercent = bottomPaddingPercent.coerceIn(0f, 0.4f),
        delayMs = delayMs.coerceIn(-30_000L, 30_000L),
    )
}

/** 弹幕偏好。屏蔽词按行存，匹配时忽略大小写。 */
@Serializable
data class DanmakuPreferences(
    val enabled: Boolean = true,
    val opacity: Float = 0.85f,
    val speed: Float = 1f,
    val fontScale: Float = 1f,
    val areaPercent: Float = 0.55f,
    val scrollEnabled: Boolean = true,
    val topEnabled: Boolean = true,
    val bottomEnabled: Boolean = true,
    val blockedWords: List<String> = emptyList(),
    val appId: String = "",
    val appSecret: String = "",
) {
    fun sanitized(): DanmakuPreferences = copy(
        opacity = opacity.coerceIn(0.1f, 1f),
        speed = speed.coerceIn(0.5f, 2f),
        fontScale = fontScale.coerceIn(0.6f, 1.8f),
        areaPercent = areaPercent.coerceIn(0.2f, 0.9f),
    )

    val configured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()
}

/** 按集记忆的片头/片尾。片头是 [startMs, endMs)，片尾是 [startMs, 片尾结束)。 */
@Serializable
data class SkipSegment(val startMs: Long = 0L, val endMs: Long = 0L) {
    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val valid: Boolean get() = endMs > startMs && startMs >= 0L
}

/** 一集对应的片头片尾记录。 */
@Serializable
data class EpisodeSkip(
    val intro: SkipSegment? = null,
    val outro: SkipSegment? = null,
) {
    val empty: Boolean get() = intro == null && outro == null
}

/** 播放器持久化偏好。与 AppSettings 分开存，播放器写入频率和 AppSettings 不同。 */
@Serializable
data class PlayerPreferences(
    val speed: Float = 1f,
    val autoNext: Boolean = true,
    val autoSkipIntro: Boolean = true,
    val autoSkipOutro: Boolean = false,
    val autoPlay: Boolean = true,
    val loop: Boolean = false,
    val subtitle: SubtitleAppearance = SubtitleAppearance(),
    val danmaku: DanmakuPreferences = DanmakuPreferences(),
    /** key = "detailKey#episode" */
    val skips: Map<String, EpisodeSkip> = emptyMap(),
) {
    fun sanitized(): PlayerPreferences = copy(
        speed = speed.coerceIn(0.5f, 2f),
        subtitle = subtitle.sanitized(),
        danmaku = danmaku.sanitized(),
        // 长期使用后记录会无限增长：只保留最近的 400 条
        skips = if (skips.size > MAX_SKIPS) {
            skips.entries.drop(skips.size - MAX_SKIPS).associate { it.key to it.value }
        } else {
            skips
        },
    )

    fun skipKey(detailKey: String, episode: Int) = "$detailKey#$episode"

    fun skipOf(detailKey: String, episode: Int): EpisodeSkip =
        skips[skipKey(detailKey, episode)] ?: EpisodeSkip()

    companion object {
        const val SPEED_MIN = 0.5f
        const val SPEED_MAX = 2f
        val SPEED_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        private const val MAX_SKIPS = 400
        const val FILE = "player_prefs.json"
    }
}

/**
 * 播放器偏好的文件存储。
 *
 * 与 [SettingsStore] 共用 `TextFileStore`，但写在独立文件里：播放器会频繁更新
 * 倍速/片头记忆，混进 settings.json 会让账号相关的写入互相覆盖。
 */
class PlayerPreferencesStore(
    private val store: TextFileStore,
    private val json: kotlinx.serialization.json.Json,
) {
    private val _preferences = MutableStateFlow(PlayerPreferences())
    val preferences: StateFlow<PlayerPreferences> = _preferences.asStateFlow()

    suspend fun load(): PlayerPreferences {
        val raw = store.read(PlayerPreferences.FILE) ?: return _preferences.value
        runCatching { json.decodeFromString(PlayerPreferences.serializer(), raw) }
            .getOrNull()
            ?.let { _preferences.value = it.sanitized() }
        return _preferences.value
    }

    suspend fun update(block: (PlayerPreferences) -> PlayerPreferences): PlayerPreferences {
        _preferences.update { block(it).sanitized() }
        persist()
        return _preferences.value
    }

    private suspend fun persist() {
        runCatching {
            store.write(
                PlayerPreferences.FILE,
                json.encodeToString(PlayerPreferences.serializer(), _preferences.value),
            )
        }
    }
}
