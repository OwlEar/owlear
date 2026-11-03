package org.owlear.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Tiny line graph of signal strength over time (newest on the right).
 * Rising line = getting closer (or it's getting closer to you).
 * Mechanic version: a vacuum gauge needle you watch while you tune. The trend matters more
 * than any single reading.
 */
class SparkView(context: Context, private val values: List<Int>, private val lineColor: Int, private val gridColor: Int) :
    View(context) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = lineColor
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = gridColor
        alpha = 80
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(0f, h - 1, w, h - 1, grid)
        if (values.size < 2) return
        // Fixed scale −100..−30 dBm so graphs can be compared with each other.
        fun y(v: Int) = h - ((v + 100f) / 70f).coerceIn(0f, 1f) * h
        val step = w / (Sighting.HISTORY_MAX - 1)
        val start = w - step * (values.size - 1)
        path.reset()
        values.forEachIndexed { i, v ->
            val x = start + i * step
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        canvas.drawPath(path, line)
    }
}
