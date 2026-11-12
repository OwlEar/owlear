package org.owlear.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Three-ring picture above the Nearby list. Only one thing on it means anything:
 * the closer an icon sits to the centre (you), the closer the device seems to be.
 *
 *  - No sweeping beam, no compass, no distance labels. A phone can't tell direction,
 *    so the side an icon sits on is only its group's own slice, never "where".
 *  - Shape = what it is ([Glyph]), colour = how worried to be, same as the list.
 *  - Tap an icon to open its details card.
 *
 * Mechanic version: a bullseye. The nearer the dart to the middle, the nearer the device.
 */
class RadarView(context: Context) : View(context) {

    var items: List<Sighting> = emptyList()
    var onPick: ((Sighting) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val hitX = ArrayList<Float>()
    private val hitY = ArrayList<Float>()
    private val hitS = ArrayList<Sighting>()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#24392E")
        strokeWidth = 1.5f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val youColor = Color.parseColor("#3DDC84")

    init {
        isClickable = true
        contentDescription = context.getString(R.string.section_nearby)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    /**
     * 0 = on top of you, 1 = outer ring. Log scale, so the first few metres get room:
     * about 3 m on the inner ring, about 12 m on the middle one, 50 m or more on the outer.
     */
    private fun fraction(meters: Double): Float =
        (ln(meters / NEAREST_M) / ln(FARTHEST_M / NEAREST_M)).toFloat().coerceIn(0.06f, 1f)

    /** Icon half-width: 5 dp for a whisper (−95 dBm) up to 10 dp for a shout (−40 dBm). */
    private fun sizeFor(rssi: Int): Float =
        (5f + 5f * ((rssi + 95f) / 55f).coerceIn(0f, 1f)) * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 14f * density

        for (i in 1..3) canvas.drawCircle(cx, cy, r * i / 3f, ringPaint)

        hitX.clear(); hitY.clear(); hitS.clear()
        val slice = 360f / Glyph.entries.size
        // Farthest first, so the nearest (most important) icons end up on top.
        for (s in items.sortedByDescending { Distance.meters(it.rssi, it.source) }) {
            val glyph = Glyph.of(s)
            val size = sizeFor(s.rssi)
            // Never on top of the green "you" dot: the closest icons sit just beside it.
            val dist = maxOf(fraction(Distance.meters(s.rssi, s.source)) * r, YOU_DP * density + size * 1.3f + 3 * density)
            // Each kind gets its own slice; the address spreads devices out inside it.
            val jitter = ((s.address.hashCode() and 0x7fffffff) % 1000) / 1000f
            val angle = glyph.ordinal * slice + (0.15f + 0.7f * jitter) * slice
            val rad = Math.toRadians((angle - 90f).toDouble())
            val x = cx + dist * cos(rad).toFloat()
            val y = cy + dist * sin(rad).toFloat()

            dotPaint.color = Glyph.colorFor(s)
            dotPaint.style = Paint.Style.FILL
            Glyph.draw(canvas, glyph, x, y, size, dotPaint)
            hitX.add(x); hitY.add(y); hitS.add(s)
        }

        // You
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = youColor
        canvas.drawCircle(cx, cy, YOU_DP * density, dotPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            var best = -1
            var bestD = 28f * density // finger-sized target
            for (i in hitS.indices) {
                val d = hypot(event.x - hitX[i], event.y - hitY[i])
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0) {
                performClick()
                onPick?.invoke(hitS[best])
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val NEAREST_M = 0.75
        const val FARTHEST_M = 50.0
        const val YOU_DP = 5f
    }
}
