package com.moontvplus.phone.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerGestureMachineTest {

    private val machine = PlayerGestureMachine(
        GestureConfig(
            doubleTapWindowMs = 280,
            longPressMs = 500,
            touchSlopPx = 10f,
            seekStepMs = 10_000,
            horizontalSeekRangeMs = 90_000,
        ),
    )

    private val context = GestureContext(
        playing = true,
        positionMs = 30_000,
        durationMs = 120_000,
        brightness = 0.5f,
        volume = 0.5f,
        seekable = true,
    )

    private fun down(x: Float, y: Float, now: Long = 0L, ctx: GestureContext = context) =
        machine.down(x, y, 1000f, 600f, now, ctx)

    @Test
    fun singleTapWaitsForDoubleTapWindowThenTogglesControls() {
        down(500f, 300f)
        assertTrue(machine.up(100).isEmpty(), "单击不应该立刻切换控制栏")
        assertTrue(machine.hasPendingTap)
        assertEquals(listOf(GestureEffect.ToggleControls), machine.commitPendingTap())
        assertFalse(machine.hasPendingTap)
    }

    @Test
    fun doubleTapInTheMiddleTogglesPlayback() {
        down(500f, 300f, 0)
        machine.up(60)
        down(505f, 302f, 120)
        assertEquals(listOf(GestureEffect.TogglePlay), machine.up(180))
        // 双击之后不应再补一次单击
        assertTrue(machine.commitPendingTap().isEmpty())
    }

    @Test
    fun doubleTapOnTheSidesSeeksByTenSeconds() {
        down(100f, 300f, 0)
        machine.up(50)
        down(100f, 300f, 100)
        assertEquals(listOf(GestureEffect.SeekRelative(-10_000)), machine.up(150))

        down(950f, 300f, 400)
        machine.up(450)
        down(950f, 300f, 500)
        assertEquals(listOf(GestureEffect.SeekRelative(10_000)), machine.up(550))
    }

    @Test
    fun horizontalDragPreviewsThenCommitsSeek() {
        down(500f, 300f)
        // 拖到一半屏幕：+45 秒
        val preview = machine.move(1000f, 305f)
        assertTrue(preview.single() is GestureEffect.SeekPreview)
        val target = (preview.single() as GestureEffect.SeekPreview).targetMs
        assertEquals(75_000L, target)
        assertEquals(listOf(GestureEffect.SeekCommit(75_000L)), machine.up(400))
    }

    @Test
    fun verticalDragOnTheLeftChangesBrightness() {
        down(100f, 400f)
        val effects = machine.move(105f, 100f) // 向上拖 = 提高亮度
        val brightness = (effects.single() as GestureEffect.Brightness).value
        assertTrue(brightness > 0.5f, "向上拖动应该提高亮度，实际 $brightness")
        assertTrue(machine.up(400).isEmpty(), "亮度已在拖动中实时生效")
    }

    @Test
    fun verticalDragOnTheRightChangesVolume() {
        down(900f, 400f)
        val effects = machine.move(905f, 550f) // 向下拖 = 降低音量
        val volume = (effects.single() as GestureEffect.Volume).value
        assertTrue(volume < 0.5f, "向下拖动应该降低音量，实际 $volume")
    }

    @Test
    fun longPressBoostsThenRestores() {
        down(500f, 300f, now = 0)
        assertEquals(listOf(GestureEffect.SpeedBoostStart), machine.onLongPressElapsed(520))
        assertEquals(listOf(GestureEffect.SpeedBoostEnd), machine.up(2_000))
    }

    @Test
    fun longPressTimerIsCancelledByMovement() {
        down(500f, 300f, now = 0)
        machine.move(500f, 100f)
        assertTrue(machine.onLongPressElapsed(600).isEmpty(), "已经变成拖动的手势不应触发长按倍速")
    }

    @Test
    fun smallMovementStillCountsAsTap() {
        down(500f, 300f)
        assertTrue(machine.move(504f, 303f).isEmpty())
        assertTrue(machine.up(80).isEmpty())
        assertTrue(machine.hasPendingTap)
    }

    @Test
    fun lockedPlayerOnlyReportsLockedTap() {
        down(100f, 300f, 0, context.copy(locked = true))
        // 锁定状态下拖动不产生任何效果
        assertTrue(machine.move(900f, 100f).isEmpty())
        assertEquals(listOf(GestureEffect.LockedTap), machine.up(150))
        assertFalse(machine.hasPendingTap)
    }

    @Test
    fun nonSeekableSourceFallsBackToLockedChannelBehaviour() {
        val machine = PlayerGestureMachine(GestureConfig(touchSlopPx = 10f))
        machine.down(500f, 300f, 1000f, 600f, 0, context.copy(seekable = false, durationMs = 0))
        val effects = machine.move(900f, 300f)
        // 不可 seek 时横向拖动不产生进度预览
        assertTrue(effects.none { it is GestureEffect.SeekPreview })
    }
}
