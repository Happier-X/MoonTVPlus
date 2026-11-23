package com.moontvplus.nshhost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Choreographer
import android.view.View
import android.util.TypedValue
import android.util.Log

data class DanmakuItem(
    val time: Long,
    val text: String,
    val mode: Int,
    val color: Int
)

class DanmakuView(context: Context) : View(context) {
    private var items: List<DanmakuItem> = emptyList()
    private var enabled = true
    private var opacity = 0.85f
    private var fontSize = 18f
    private var mode = "all"

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(18f)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        textSize = sp(18f)
    }

    private var lastLogAt = 0L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (enabled && items.isNotEmpty()) {
                invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun update(
        nextItems: List<DanmakuItem>,
        nextEnabled: Boolean,
        nextOpacity: Float,
        nextFontSize: Float,
        nextMode: String
    ) {
        items = nextItems.sortedBy { it.time }
        enabled = nextEnabled
        opacity = nextOpacity.coerceIn(0.1f, 1f)
        fontSize = nextFontSize.coerceIn(12f, 36f)
        mode = nextMode
        paint.textSize = sp(fontSize)
        stroke.textSize = sp(fontSize)
        if (enabled && items.isNotEmpty()) {
            Choreographer.getInstance().removeFrameCallback(frame)
            Choreographer.getInstance().postFrameCallback(frame)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabled || items.isEmpty() || width <= 0) return
        val position = PlayerBridge.position()
        val lineHeight = sp(fontSize) * 1.25f
        val lanes = (height / lineHeight).toInt().coerceAtLeast(1)
        var drawn = 0

        for (index in items.indices) {
            val item = items[index]
            if (item.time > position) {
                if (item.time - position > 9000) break
                continue
            }
            val elapsed = position - item.time
            val lane = (index * 7) % lanes
            val type = when {
                mode == "all" -> item.mode
                mode == "top" -> 1
                mode == "bottom" -> 2
                mode == "scroll" -> 0
                else -> item.mode
            }
            if (mode != "all" && item.mode != type) continue

            if (item.mode == 0) {
                val duration = 8000f
                if (elapsed > duration) continue
                val textWidth = paint.measureText(item.text)
                val x = width - (elapsed / duration) * (width + textWidth)
                draw(canvas, item, x, lineHeight * (lane + 1))
            } else {
                if (elapsed > 4000) continue
                val textWidth = paint.measureText(item.text)
                val x = (width - textWidth) / 2
                val y = if (item.mode == 1) lineHeight * (lane + 1) else height - lineHeight * (lane + 1)
                draw(canvas, item, x, y)
            }
            drawn++
            if (drawn > 100) break
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (drawn > 0 && now - lastLogAt > 2000) {
            lastLogAt = now
            Log.d("nsh-host", "danmaku drawn=" + drawn + " position=" + position)
        }
    }

    private fun draw(canvas: Canvas, item: DanmakuItem, x: Float, y: Float) {
        paint.color = item.color
        paint.alpha = (255 * opacity).toInt()
        stroke.alpha = (255 * opacity).toInt()
        canvas.drawText(item.text, x, y, stroke)
        canvas.drawText(item.text, x, y, paint)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
