package com.moontvplus.phone.playback

import com.moontvplus.phone.core.storage.EpisodeSkip
import com.moontvplus.phone.core.storage.PlayerPreferences
import com.moontvplus.phone.core.storage.SkipSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipAdvisorTest {

    private val advisor = SkipAdvisor()
    private val episode = EpisodeSkip(
        intro = SkipSegment(0L, 90_000L),
        outro = SkipSegment(1_380_000L, 1_440_000L),
    )

    @Test
    fun autoSkipsIntroWhenEnabled() {
        val advice = advisor.advise(
            PlayerPreferences(autoSkipIntro = true),
            episode,
            positionMs = 10_000L,
            durationMs = 1_440_000L,
        )
        assertEquals(SkipAction.Intro(90_000L), advice.autoAction)
        assertFalse(advice.showIntroButton)
    }

    @Test
    fun showsIntroButtonWhenAutoSkipDisabled() {
        val advice = advisor.advise(
            PlayerPreferences(autoSkipIntro = false),
            episode,
            positionMs = 10_000L,
            durationMs = 1_440_000L,
        )
        assertEquals(null, advice.autoAction)
        assertTrue(advice.showIntroButton)
    }

    @Test
    fun autoSkipsOutroAtItsStart() {
        val advice = advisor.advise(
            PlayerPreferences(autoSkipOutro = true),
            episode,
            positionMs = 1_390_000L,
            durationMs = 1_440_000L,
        )
        assertEquals(SkipAction.Outro, advice.autoAction)
    }

    @Test
    fun handledFlagsPreventRepeatedAutoSkip() {
        val advice = advisor.advise(
            PlayerPreferences(autoSkipIntro = true),
            episode,
            positionMs = 10_000L,
            durationMs = 1_440_000L,
            introHandled = true,
        )
        assertEquals(null, advice.autoAction)
        assertFalse(advice.showIntroButton)
    }

    @Test
    fun noEpisodeMarksMeansNoActions() {
        val advice = advisor.advise(
            PlayerPreferences(autoSkipIntro = true, autoSkipOutro = true),
            EpisodeSkip(),
            positionMs = 10_000L,
            durationMs = 1_440_000L,
        )
        assertEquals(SkipAdvice(), advice)
    }

    @Test
    fun infersIntroAndOutroFromCurrentPosition() {
        assertEquals(SkipSegment(0L, 80_000L), advisor.inferIntro(80_000L))
        assertEquals(null, advisor.inferIntro(1_000L))
        val outro = advisor.inferOutro(1_300_000L, 1_440_000L)
        assertEquals(SkipSegment(1_300_000L, 1_440_000L), outro)
    }

    @Test
    fun countdownTicksToZero() {
        var countdown = AutoNextCountdown.start(5_000L)
        assertTrue(countdown.visible)
        countdown = countdown.tick(1_000L)
        assertEquals(4_000L, countdown.remainingMs)
        assertEquals(4, countdown.remainingSeconds)
        countdown = countdown.tick(10_000L)
        assertEquals(0L, countdown.remainingMs)
        assertFalse(countdown.visible)
    }
}
