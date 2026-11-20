package com.moontvplus.phone.danmaku

import kotlin.math.max
import kotlin.math.min

/**
 * 一条已经排好轨道的弹幕。
 *
 * [durationMs] 是它在屏幕上停留的时长；[textWidthPx] 由调用方按实际字体测量后给出
 * （界面用 `TextMeasurer`），这里只负责轨迹计算，方便单测。
 */
data class DanmakuPlacement(
    val comment: DanmakuComment,
    val lane: Int,
    val startMs: Long,
    val durationMs: Long,
    val textWidthPx: Float,
    val fixed: Boolean,
) {
    val endMs: Long get() = startMs + durationMs
}

/** 每帧要画的一项。[xPx] 是左边缘，[yPx] 是文字行顶部。 */
data class DanmakuFrameItem(
    val text: String,
    val colorArgb: Long,
    val xPx: Float,
    val yPx: Float,
    val fontPx: Float,
    val alpha: Float,
)

/**
 * 弹幕轨道排布。
 *
 * 先一次性把整集弹幕排到固定轨道（[plan]），每帧只按时间过滤并计算位移（[frame]）。
 * 这样 30 万条弹幕的剧集也只要 O(n) 预处理，播放时不再做碰撞计算。
 *
 * 轨道规则与 B 站一致：滚动弹幕优先找「空闲最久」的轨道；顶部/底部各占固定轨道，
 * 相互之间在纵向区间内避让。
 */
class DanmakuLayoutEngine(
    private val widthPx: Float,
    private val heightPx: Float,
    private val fontPx: Float,
    /** 弹幕显示区域占画面高度的比例 */
    private val areaFraction: Float = 0.55f,
    /** 基础滚动速度 px/s，实际速度 = 基础速度 × speedScale */
    private val speedScale: Float = 1f,
    private val scrollEnabled: Boolean = true,
    private val topEnabled: Boolean = true,
    private val bottomEnabled: Boolean = true,
    /** 顶部弹幕固定停留时间 */
    private val fixedDurationMs: Long = 4_000L,
) {
    val lineHeightPx: Float = fontPx * 1.45f
    val laneCount: Int = max(1, (heightPx * areaFraction / lineHeightPx).toInt())

    private fun baseSpeedPxPerSec(): Float = max(60f, widthPx / 8f) * speedScale

    /**
     * 把整集弹幕排进轨道。[textWidth] 让界面用真实字体测量宽度；不传则按
     * 中文全角 1 字宽、英文半角 0.55 字宽估算（单测/预排用）。
     */
    fun plan(
        comments: List<DanmakuComment>,
        textWidth: (DanmakuComment) -> Float = ::estimateWidth,
    ): List<DanmakuPlacement> {
        if (comments.isEmpty()) return emptyList()
        val speed = baseSpeedPxPerSec()
        // 每条轨道上一次占用的结束时间，用来避免重叠
        val scrollFree = LongArray(laneCount)
        val topFree = LongArray(laneCount)
        val bottomFree = LongArray(laneCount)
        val result = ArrayList<DanmakuPlacement>(comments.size)

        for (comment in comments.sortedBy { it.timeMs }) {
            val width = textWidth(comment)
            val fixed = comment.mode == DanmakuMode.TOP || comment.mode == DanmakuMode.BOTTOM
            if (!fixed && comment.mode != DanmakuMode.SCROLL && comment.mode != DanmakuMode.REVERSE) {
                // 高级/脚本弹幕暂时按滚动弹幕降级，而不是直接丢弃
                if (!scrollEnabled) continue
            }
            val enabled = when {
                comment.mode == DanmakuMode.TOP -> topEnabled
                comment.mode == DanmakuMode.BOTTOM -> bottomEnabled
                else -> scrollEnabled
            }
            if (!enabled) continue

            if (fixed) {
                val free = if (comment.mode == DanmakuMode.TOP) topFree else bottomFree
                val lane = bestLane(free, comment.timeMs)
                val duration = fixedDurationMs
                free[lane] = comment.timeMs + duration + TRAIL_GAP_MS
                result += DanmakuPlacement(comment, lane, comment.timeMs, duration, width, fixed = true)
            } else {
                // 滚动时长 = (屏宽 + 文字宽) / 速度，保证整条完整划出屏幕
                val duration = (((widthPx + width) / speed) * 1000f).toLong().coerceAtLeast(1_500L)
                val lane = bestLane(scrollFree, comment.timeMs)
                // 轨道被占时直接丢弃该条，而不是叠在一起（与 B 站容量策略一致）
                if (scrollFree[lane] > comment.timeMs) continue
                scrollFree[lane] = comment.timeMs + min(duration, MAX_SCROLL_MS) + TRAIL_GAP_MS
                result += DanmakuPlacement(comment, lane, comment.timeMs, duration, width, fixed = false)
            }
        }
        return result
    }

    private fun bestLane(free: LongArray, timeMs: Long): Int {
        var candidate = 0
        var bestFree = Long.MAX_VALUE
        for (lane in free.indices) {
            if (free[lane] <= timeMs) return lane
            if (free[lane] < bestFree) {
                bestFree = free[lane]
                candidate = lane
            }
        }
        return candidate
    }

    /** 当前时刻应该出现在屏幕上的弹幕（含位置/透明度）。 */
    fun frame(placements: List<DanmakuPlacement>, positionMs: Long, opacity: Float = 0.85f): List<DanmakuFrameItem> {
        if (placements.isEmpty()) return emptyList()
        val result = ArrayList<DanmakuFrameItem>(32)
        val speed = baseSpeedPxPerSec()
        for (placement in placements) {
            if (positionMs < placement.startMs || positionMs > placement.endMs) continue
            val elapsed = (positionMs - placement.startMs).toFloat()
            val item = if (placement.fixed) {
                val y = if (placement.comment.mode == DanmakuMode.BOTTOM) {
                    val bottomIndex = laneCount - 1 - placement.lane
                    bottomIndex * lineHeightPx
                } else {
                    placement.lane * lineHeightPx
                }
                DanmakuFrameItem(
                    text = placement.comment.text,
                    colorArgb = placement.comment.colorArgb,
                    xPx = (widthPx - placement.textWidthPx) / 2f,
                    yPx = max(0f, y),
                    fontPx = fontPx,
                    alpha = 1f,
                )
            } else {
                val x = widthPx - elapsed / 1000f * speed
                DanmakuFrameItem(
                    text = placement.comment.text,
                    colorArgb = placement.comment.colorArgb,
                    xPx = x,
                    yPx = placement.lane * lineHeightPx,
                    fontPx = fontPx,
                    alpha = 1f,
                )
            }
            // 两侧淡入淡出，避免文字硬切
            val edgeFade = min(
                1f,
                min(
                    if (elapsed < FADE_MS) elapsed / FADE_MS else 1f,
                    if (placement.endMs - positionMs < FADE_MS) (placement.endMs - positionMs).toFloat() / FADE_MS else 1f,
                ),
            )
            result += item.copy(alpha = (opacity * edgeFade).coerceIn(0f, 1f))
        }
        return result
    }

    /** 粗略估算文字宽度：CJK 按 1em，ASCII 按 0.55em。 */
    private fun estimateWidth(comment: DanmakuComment): Float {
        var units = 0f
        for (char in comment.text) {
            units += if (char.code > 0x2E80) 1f else 0.55f
        }
        return units * fontPx * (comment.sizePercent.coerceIn(60, 200) / 100f)
    }

    private companion object {
        const val TRAIL_GAP_MS = 120L
        const val MAX_SCROLL_MS = 20_000L
        const val FADE_MS = 180L
    }
}
