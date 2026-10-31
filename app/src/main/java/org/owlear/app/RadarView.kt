package org.owlear.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar display. Distance from the centre = signal strength (closer = stronger).
 * Angle = estimated direction from the last sweep, rotated to where the phone points now.
 * Signals with no direction yet are drawn hollow at a fixed, meaningless angle.
 */
class RadarView(context: Context) : View(context) {

    var items: List<Sighting> = emptyList()
    var heading = 0f
    var animating = false

    private var sweepAngle = 0f
    private val density = resources.displayMetrics.density
    private val box = RectF()

    private val ringVeryClose = context.getString(R.string.very_close)
    private val ringNearby = context.getString(R.string.nearby)
    private val ringFar = context.getString(R.string.far)
    private val north = context.getString(R.string.compass_north)

    private val green = Color.parseColor("#3DDC84")
    private val red = Color.parseColor("#FF5A5F")
    private val amber = Color.parseColor("#FFC24B")
    private val white = Color.parseColor("#E8EEEA")
    private val dim = Color.parseColor("#8A968F")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#24392E")
        strokeWidth = 1.5f * density
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = green
        alpha = 45
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dim
        textSize = 11f * density
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        textSize = 13f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    /** -40 dBm sits near the centre, -95 dBm at the edge. */
    private fun radiusFor(rssi: Int, max: Float): Float =
        ((-rssi - 40f) / 55f).coerceIn(0.08f, 1f) * max

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 18f * density
        val now = System.currentTimeMillis()

        // Distance rings: very close / nearby / far
        canvas.drawCircle(cx, cy, radiusFor(-60, r), ringPaint)
        canvas.drawCircle(cx, cy, radiusFor(-75, r), ringPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, ringPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, ringPaint)
        canvas.drawText(ringVeryClose, cx + 4 * density, cy - radiusFor(-60, r) + 12 * density, textPaint)
        canvas.drawText(ringNearby, cx + 4 * density, cy - radiusFor(-75, r) + 12 * density, textPaint)
        canvas.drawText(ringFar, cx + 4 * density, cy - r + 12 * density, textPaint)

        // North marker, rotated against the phone's heading
        val northRad = Math.toRadians((-heading - 90f).toDouble())
        canvas.drawText(
            north,
            cx + (r + 9 * density) * cos(northRad).toFloat(),
            cy + (r + 9 * density) * sin(northRad).toFloat() + 5 * density,
            northPaint,
        )

        // Rotating beam (looks only)
        if (animating) {
            box.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(box, sweepAngle - 90f - 35f, 35f, true, beamPaint)
        }

        // Signals
        for (s in items) {
            val dist = radiusFor(s.rssi, r)
            val known = Repo.hasFreshBearing(s, now)
            val angle = if (known) (s.bearing ?: 0f) - heading
            else ((s.address.hashCode() and 0x7fffffff) % 360).toFloat()
            val rad = Math.toRadians((angle - 90f).toDouble())
            val x = cx + dist * cos(rad).toFloat()
            val y = cy + dist * sin(rad).toFloat()

            dotPaint.color = when {
                s.kind == Kind.TRACKER && s.separated && Repo.isFollowing(s) -> red
                (s.kind == Kind.WATCHLIST || s.kind == Kind.SPOTTED) && s.confidence == Confidence.POSSIBLE -> dim
                s.kind == Kind.WATCHLIST || s.kind == Kind.SPOTTED -> amber
                else -> white
            }
            if (known) {
                dotPaint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, 7 * density, dotPaint)
            } else {
                dotPaint.style = Paint.Style.STROKE
                dotPaint.strokeWidth = 2 * density
                canvas.drawCircle(x, y, 6 * density, dotPaint)
            }
            val short = s.label.substringBefore(" (")
            val tag = if (short.length > 18) short.take(17) + "…" else short
            canvas.drawText(tag, x + 10 * density, y + 4 * density, textPaint)
        }

        // You
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = green
        canvas.drawCircle(cx, cy, 5 * density, dotPaint)

        if (animating) {
            sweepAngle = (sweepAngle + 2.5f) % 360f
            postInvalidateOnAnimation()
        }
    }
}
