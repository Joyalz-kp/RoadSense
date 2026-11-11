package com.example.roadsense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Half-circle gauge showing a 0-100 road score. */
class RoadScoreGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var score: Int = 100
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(60, 128, 128, 128)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = Color.GRAY
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = min(w / 2f, h) * 0.18f
        val radius = min(w / 2f, h) - stroke
        val cx = w / 2f
        val cy = h - stroke
        track.strokeWidth = stroke
        fill.strokeWidth = stroke
        fill.color = when {
            score < 40 -> Color.rgb(211, 47, 47)
            score < 70 -> Color.rgb(245, 124, 0)
            else -> Color.rgb(56, 142, 60)
        }
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(oval, 180f, 180f, false, track)
        canvas.drawArc(oval, 180f, 180f * score / 100f, false, fill)
        text.textSize = radius * 0.6f
        canvas.drawText(score.toString(), cx, cy - radius * 0.1f, text)
    }
}
