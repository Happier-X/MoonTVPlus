package com.moontvplus.phone.playback

/**
 * 手势参数。默认值对齐 B 站移动端的手感：左右半屏亮/音量、双击中间暂停、
 * 双击两侧 ±10 秒、长按 2 倍速。
 */
data class GestureConfig(
    val doubleTapWindowMs: Long = 280L,
    val longPressMs: Long = 500L,
    val touchSlopPx: Float = 18f,
    val seekStepMs: Long = 10_000L,
    val longPressSpeed: Float = 2f,
    /** 横向拖满整屏对应的进度跨度 */
    val horizontalSeekRangeMs: Long = 90_000L,
    /** 纵向拖满整屏对应的亮度/音量跨度；1.6 表示半屏约 80% */
    val verticalSensitivity: Float = 1.6f,
    /** 左侧/右侧触控区占屏幕宽度的比例，中间剩余区域响应双击暂停 */
    val edgeZoneFraction: Float = 0.35f,
)

/** 一次手势期间的只读上下文，由界面在 down 时快照。 */
data class GestureContext(
    val locked: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val brightness: Float = 0.5f,
    val volume: Float = 0.5f,
    val seekable: Boolean = true,
)

/** 手势产生的意图。界面只负责执行，不参与判定。 */
sealed interface GestureEffect {
    /** 单击：切换控制栏显隐（是否显示由界面按当前状态决定） */
    data object ToggleControls : GestureEffect
    data object TogglePlay : GestureEffect
    data class SeekRelative(val deltaMs: Long) : GestureEffect
    /** 拖动进度时的实时预览 */
    data class SeekPreview(val targetMs: Long, val deltaMs: Long) : GestureEffect
    /** 松手，落到目标进度 */
    data class SeekCommit(val targetMs: Long) : GestureEffect
    data class Brightness(val value: Float) : GestureEffect
    data class Volume(val value: Float) : GestureEffect
    data object SpeedBoostStart : GestureEffect
    data object SpeedBoostEnd : GestureEffect
    /** 锁屏状态下的点击，界面可以短暂显示解锁提示 */
    data object LockedTap : GestureEffect
}

/**
 * 纯 Kotlin 手势状态机。
 *
 * 为什么不直接用 Compose 的 `detectTapGestures` + `detectDragGestures`：
 * 双击、长按、横拖、左右半屏纵拖需要共享同一个指针流，两套检测器一起用会互相吞事件
 * （长按被拖动检测器抢走、双击后拖动失效）。把判定搬到这里以后，行为可以单测，
 * Compose 层只做 `pointerInput` → 事件转发的薄封装。
 *
 * 使用顺序：down → (move*) → up；长按由界面在 down 之后 delay([GestureConfig.longPressMs])
 * 调用 [onLongPressElapsed]；单击延迟由界面 delay([GestureConfig.doubleTapWindowMs]) 后调用
 * [commitPendingTap]。
 */
class PlayerGestureMachine(private val config: GestureConfig = GestureConfig()) {

    private enum class Mode { NONE, PRESSING, SPEED_BOOST, SEEK, BRIGHTNESS, VOLUME }

    private var mode = Mode.NONE
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var width = 0f
    private var height = 0f
    private var downAt = 0L
    private var context = GestureContext()
    private var moved = false
    private var secondTap = false
    private var pendingTap = false
    private var dragValue = 0f
    private var seekTarget = 0L

    /** 存在一个「可能是单击」的抬手，等双击窗口结束后再提交。 */
    val hasPendingTap: Boolean get() = pendingTap

    /** 正在跟踪指针（界面据此决定是否拦截父级滚动）。 */
    val isTracking: Boolean get() = mode != Mode.NONE || pendingTap

    fun down(x: Float, y: Float, viewWidth: Float, viewHeight: Float, nowMs: Long, context: GestureContext): List<GestureEffect> {
        val effects = mutableListOf<GestureEffect>()
        // 拖动过程中又按下（多指）：按取消处理，避免状态串味
        if (mode in setOf(Mode.SEEK, Mode.BRIGHTNESS, Mode.VOLUME)) {
            finishDrag(effects)
        }
        // 锁屏时不进入任何拖动分支，但保留 down/up 以便给出「已锁定」提示
        secondTap = if (context.locked) false else pendingTap
        if (context.locked) pendingTap = false
        if (!secondTap) pendingTap = false
        mode = Mode.PRESSING
        moved = false
        startX = x; startY = y; lastX = x; lastY = y
        width = viewWidth.coerceAtLeast(1f)
        height = viewHeight.coerceAtLeast(1f)
        downAt = nowMs
        this.context = context
        return effects
    }

    fun move(x: Float, y: Float): List<GestureEffect> {
        if (mode == Mode.NONE) return emptyList()
        if (context.locked) return emptyList()
        lastX = x
        lastY = y
        val dx = x - startX
        val dy = y - startY
        if (!moved && (dx * dx + dy * dy) > config.touchSlopPx * config.touchSlopPx) {
            moved = true
            // 拖动开始：之前挂起的单击立即生效，避免拖完才切换控制栏
            val began = mutableListOf<GestureEffect>()
            if (pendingTap) {
                pendingTap = false
                began += GestureEffect.ToggleControls
            }
            secondTap = false
            mode = when {
                !context.seekable -> Mode.NONE
                kotlin.math.abs(dx) > kotlin.math.abs(dy) -> {
                    dragValue = context.positionMs.toFloat()
                    seekTarget = context.positionMs
                    Mode.SEEK
                }
                x < width * config.edgeZoneFraction -> {
                    dragValue = context.brightness
                    Mode.BRIGHTNESS
                }
                x > width * (1f - config.edgeZoneFraction) -> {
                    dragValue = context.volume
                    Mode.VOLUME
                }
                else -> Mode.NONE
            }
            if (mode == Mode.NONE) return began
            return began + moveInternal(dx, dy)
        }
        if (!moved) return emptyList()
        return moveInternal(dx, dy)
    }

    private fun moveInternal(dx: Float, dy: Float): List<GestureEffect> = when (mode) {
        Mode.SEEK -> {
            val span = context.durationMs.coerceAtLeast(1L).toFloat()
            val range = config.horizontalSeekRangeMs.toFloat().coerceAtMost(span)
            val delta = (dx / width * range).toLong()
            val target = (context.positionMs + delta).coerceIn(0L, context.durationMs.coerceAtLeast(0L))
            seekTarget = target
            listOf(GestureEffect.SeekPreview(target, target - context.positionMs))
        }
        Mode.BRIGHTNESS -> {
            val value = (dragValue - dy / height * config.verticalSensitivity).coerceIn(0f, 1f)
            listOf(GestureEffect.Brightness(value))
        }
        Mode.VOLUME -> {
            val value = (dragValue - dy / height * config.verticalSensitivity).coerceIn(0f, 1f)
            listOf(GestureEffect.Volume(value))
        }
        else -> emptyList()
    }

    fun up(nowMs: Long): List<GestureEffect> {
        val effects = mutableListOf<GestureEffect>()
        when (mode) {
            Mode.SPEED_BOOST -> effects += GestureEffect.SpeedBoostEnd
            Mode.SEEK -> {
                effects += GestureEffect.SeekCommit(seekTarget)
            }
            Mode.BRIGHTNESS, Mode.VOLUME -> Unit // 已实时应用
            Mode.PRESSING -> if (!moved && nowMs - downAt < config.longPressMs) {
                if (context.locked) {
                    effects += GestureEffect.LockedTap
                } else if (secondTap) {
                    secondTap = false
                    pendingTap = false
                    effects += doubleTapEffect(startX)
                } else {
                    // 先不切换控制栏：等双击窗口过去，用户可能还要点第二下
                    pendingTap = true
                }
            }
            Mode.NONE -> Unit
        }
        mode = Mode.NONE
        moved = false
        return effects
    }

    /** 界面在 down 之后延时调用；仍然按住且没移动才进入长按倍速。 */
    fun onLongPressElapsed(nowMs: Long): List<GestureEffect> {
        if (mode != Mode.PRESSING || moved || context.locked) return emptyList()
        if (nowMs - downAt < config.longPressMs) return emptyList()
        mode = Mode.SPEED_BOOST
        return listOf(GestureEffect.SpeedBoostStart)
    }

    /** 双击窗口结束，把挂起的单击提交出去。 */
    fun commitPendingTap(): List<GestureEffect> {
        if (!pendingTap) return emptyList()
        pendingTap = false
        secondTap = false
        return listOf(GestureEffect.ToggleControls)
    }

    /** 视图被回收/手势被系统打断。 */
    fun cancel(): List<GestureEffect> {
        val effects = mutableListOf<GestureEffect>()
        if (mode == Mode.SPEED_BOOST) effects += GestureEffect.SpeedBoostEnd
        if (mode == Mode.SEEK) effects += GestureEffect.SeekCommit(seekTarget)
        mode = Mode.NONE
        moved = false
        secondTap = false
        pendingTap = false
        return effects
    }

    private fun finishDrag(effects: MutableList<GestureEffect>) {
        if (mode == Mode.SPEED_BOOST) effects += GestureEffect.SpeedBoostEnd
        if (mode == Mode.SEEK) effects += GestureEffect.SeekCommit(seekTarget)
        mode = Mode.NONE
        moved = false
    }

    private fun doubleTapEffect(x: Float): GestureEffect = when {
        x < width * config.edgeZoneFraction -> GestureEffect.SeekRelative(-config.seekStepMs)
        x > width * (1f - config.edgeZoneFraction) -> GestureEffect.SeekRelative(config.seekStepMs)
        else -> GestureEffect.TogglePlay
    }

    val configuredLongPressSpeed: Float get() = config.longPressSpeed
    val configuredSeekStepMs: Long get() = config.seekStepMs
}
