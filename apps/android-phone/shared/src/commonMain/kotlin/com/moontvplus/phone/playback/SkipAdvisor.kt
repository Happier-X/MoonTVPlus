package com.moontvplus.phone.playback

import com.moontvplus.phone.core.storage.EpisodeSkip
import com.moontvplus.phone.core.storage.PlayerPreferences
import com.moontvplus.phone.core.storage.SkipSegment

/** 片头片尾的自动/手动动作。 */
sealed interface SkipAction {
    /** 跳到片头结束位置 */
    data class Intro(val targetMs: Long) : SkipAction
    /** 跳到下一集 */
    data object Outro : SkipAction
}

data class SkipAdvice(
    val autoAction: SkipAction? = null,
    val showIntroButton: Boolean = false,
    val showOutroButton: Boolean = false,
)

/**
 * 片头片尾判定。
 *
 * 规则与常见播放器一致：
 * - 片头：位置落在片头区间内，开了自动跳过就自动跳，没开就显示按钮；
 * - 片尾：位置到达片尾起点，开了自动跳过就进下一集，没开就显示「跳过片尾」。
 * 每集只触发一次自动动作，避免用户手动拖回片头后被反复弹走。
 */
class SkipAdvisor {

    fun advise(
        preferences: PlayerPreferences,
        episode: EpisodeSkip,
        positionMs: Long,
        durationMs: Long,
        introHandled: Boolean = false,
        outroHandled: Boolean = false,
    ): SkipAdvice {
        if (durationMs <= 0L || positionMs <= 0L) return SkipAdvice()
        val intro = episode.intro?.takeIf { it.valid }
        val outro = episode.outro?.takeIf { it.valid }

        val introActive = intro != null && !introHandled && intro.contains(positionMs) &&
            positionMs < intro.endMs - MIN_TRIGGER_GAP_MS
        val outroStart = outro?.startMs ?: Long.MAX_VALUE
        val outroActive = outro != null && !outroHandled && positionMs >= outroStart &&
            positionMs < outro.endMs.coerceAtMost(durationMs + 5_000L)

        val auto = when {
            outroActive && preferences.autoSkipOutro -> SkipAction.Outro
            introActive && preferences.autoSkipIntro -> SkipAction.Intro(intro!!.endMs)
            else -> null
        }
        return SkipAdvice(
            autoAction = auto,
            showIntroButton = introActive && !preferences.autoSkipIntro,
            showOutroButton = outroActive && !preferences.autoSkipOutro,
        )
    }

    /**
     * 把「从第 0 秒开始看、第一次按跳过片头」推断成片头区间：
     * 结束点取用户按下时的位置，起点取 0。这样不需要另外的编辑界面。
     */
    fun inferIntro(positionMs: Long): SkipSegment? {
        if (positionMs < MIN_MARK_MS) return null
        return SkipSegment(0L, positionMs)
    }

    /** 片尾区间推断：从当前位置到片尾结束（不知道总长时先记为到 24 小时）。 */
    fun inferOutro(positionMs: Long, durationMs: Long): SkipSegment? {
        if (positionMs <= 0L) return null
        val end = if (durationMs > positionMs) durationMs else positionMs + DEFAULT_OUTRO_MS
        return if (end - positionMs < MIN_MARK_MS) null else SkipSegment(positionMs, end)
    }

    private companion object {
        const val MIN_TRIGGER_GAP_MS = 1_000L
        const val MIN_MARK_MS = 3_000L
        const val DEFAULT_OUTRO_MS = 90_000L
    }
}

/** 自动下一集倒计时。纯逻辑，界面只按 remaining 画秒数。 */
data class AutoNextCountdown(
    val remainingMs: Long = 0L,
    val totalMs: Long = DEFAULT_MS,
    val enabled: Boolean = false,
) {
    val visible: Boolean get() = enabled && remainingMs > 0L
    val remainingSeconds: Int get() = ((remainingMs + 999L) / 1000L).toInt()
    val progress: Float get() = if (totalMs <= 0L) 0f else (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)

    fun tick(elapsedMs: Long): AutoNextCountdown =
        if (!visible) this else copy(remainingMs = (remainingMs - elapsedMs).coerceAtLeast(0L))

    companion object {
        const val DEFAULT_MS = 5_000L
        fun start(totalMs: Long = DEFAULT_MS) = AutoNextCountdown(totalMs, totalMs, enabled = true)
        val Off = AutoNextCountdown()
    }
}
