package com.moontvplus.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.moontvplus.phone.danmaku.DanmakuLayoutEngine
import com.moontvplus.phone.danmaku.DanmakuComment
import com.moontvplus.phone.player.PlayerUiController

/**
 * 弹幕层。
 *
 * 轨道排布在 `shared/commonMain` 的 [DanmakuLayoutEngine] 里完成（可单测），
 * 这里只做按帧取元素 + Canvas 绘制；不创建任何原生 View，Android 与桌面共用。
 */
@Composable
fun DanmakuOverlay(controller: PlayerUiController, modifier: Modifier = Modifier) {
    val state by controller.danmaku.collectAsState()
    val playback by controller.playback.collectAsState()
    val prefs by controller.preferences.collectAsState()
    val measurer = rememberTextMeasurer()

    if (!state.active || !controller.capabilities.danmaku) return
    val settings = prefs.danmaku

    BoxWithSize { size ->
        val engine = remember(size, settings.fontScale, settings.speed, settings.areaPercent) {
            DanmakuLayoutEngine(
                widthPx = size.width.toFloat(),
                heightPx = size.height.toFloat(),
                fontPx = (20f * settings.fontScale).coerceIn(10f, 44f),
                areaFraction = settings.areaPercent,
                speedScale = settings.speed,
                scrollEnabled = settings.scrollEnabled,
                topEnabled = settings.topEnabled,
                bottomEnabled = settings.bottomEnabled,
            )
        }
        val placements = remember(engine, state.comments) {
            engine.plan(state.comments) { comment -> measuredWidth(measurer, comment, engine.lineHeightPx) }
        }
        Canvas(modifier.fillMaxSize()) {
            val frame = engine.frame(placements, playback.positionMs, settings.opacity)
            frame.forEach { item ->
                val layout = measurer.measure(
                    text = item.text,
                    style = TextStyle(
                        color = Color(item.colorArgb.toInt()),
                        fontSize = item.fontPx.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    constraints = Constraints(maxWidth = size.width),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = androidx.compose.ui.geometry.Offset(item.xPx, item.yPx),
                    alpha = item.alpha,
                )
            }
        }
    }
}

private fun measuredWidth(measurer: TextMeasurer, comment: DanmakuComment, lineHeightPx: Float): Float {
    val fontSize = (lineHeightPx / 1.45f) * (comment.sizePercent.coerceIn(60, 200) / 100f)
    return runCatching {
        measurer.measure(
            text = comment.text,
            style = TextStyle(fontSize = fontSize.sp, fontWeight = FontWeight.Bold),
        ).size.width.toFloat()
    }.getOrDefault(comment.text.length * fontSize)
}

/** 极简 BoxWithConstraints：拿到像素尺寸给弹幕引擎，兼容性比 BoxWithConstraints 更好。 */
@Composable
private fun BoxWithSize(content: @Composable (IntSize) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val size = IntSize(constraints.maxWidth, constraints.maxHeight)
        content(size)
    }
}
