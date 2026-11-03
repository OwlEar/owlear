package org.owlear.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar display, built to be read without words.
 *
 *  - Distance from the centre = rough distance estimated from signal strength (rings say ~m / ~ft).
 *  - Icon shape = what it is ([Glyph]); size = how strong the signal is; colour = how worried to be.
 *  - Filled icon = real direction from the last sweep, turned to where the phone points now.
 *    Outline icon = no direction yet, so it sits in its group's own slice of the circle
 *    (the angle then means "what kind", never "where").
 *  - Tap an icon to open everything known about it.
 *
 * Mechanic version: a phone antenna hears every direction equally, like a microphone in the
 * middle of the engine bay. It can tell loud from quiet, not left from right, until you move.
 */
class RadarView(context: Context) : View(context) {

    var items: List<Sighting> = emptyList()
    var heading = 0f
    var animating = false
    var onPick: ((Sighting) -> Unit)? = null

    private var sweepAngle = 0f
    private val density = resources.displayMetrics.density
    private val box = RectF()
    private val north = context.getString(R.string.compass_north)

    private val green = Color.parseColor("#3DDC84")
    private val red = Color.parseColor("#FF5A5F")
    private val amber = Color.parseColor("#FFC24B")
    private val white = Color.parseColor("#E8EEEA")
    private val dim = Color.parseColor("#8A968F")

    /** Where each icon was last drawn, for tap hit-testing. */
    private val hitX = ArrayList<Float>()
    private val hitY = ArrayList<Float>()
    private val hitS = ArrayList<Sighting>()

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
        textSize = 10f * density
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = green
        textSize = 13f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        contentDescription = context.getString(R.string.radar_description)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    /** Icon half-width: 4 dp for a whisper (−95 dBm) up to 11 dp for a shout (−40 dBm). */
    private fun sizeFor(rssi: Int): Float =
        (4f + 7f * ((rssi + 95f) / 55f).coerceIn(0f, 1f)) * density

    fun colorFor(s: Sighting): Int = when {
        s.kind == Kind.TRACKER && s.separated && Repo.isFollowing(s) -> red
        s.confidence == Confidence.POSSIBLE -> dim
        s.category != null && Store.mode(s.category) == Mode.ALERT && s.mutedAt == null &&
            s.kind != Kind.TRACKER -> amber
        else -> white
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 18f * density
        val now = System.currentTimeMillis()

        // Distance rings, labelled with rough distance ("~10 m" / "~30 ft").
        for ((meters, shown) in Distance.rings()) {
            val rr = Distance.fraction(meters) * r
            canvas.drawCircle(cx, cy, rr, ringPaint)
            val text = context.getString(if (Distance.usesFeet) R.string.dist_ft else R.string.dist_m, shown)
            canvas.drawText(text, cx + 3 * density, cy - rr - 3 * density, textPaint)
        }
        canvas.drawLine(cx - r, cy, cx + r, cy, ringPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, ringPaint)

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

        hitX.clear(); hitY.clear(); hitS.clear()

        // Weakest first, so the strongest (most important) icons end up on top.
        for (s in items.sortedBy { it.rssi }) {
            val dist = Distance.fraction(Distance.meters(s.rssi, s.source)) * r
            val glyph = Glyph.of(s)
            val known = Repo.hasFreshBearing(s, now)
            val angle = if (known) (s.bearing ?: 0f) - heading
            else glyph.sector + ((s.address.hashCode() and 0x7fffffff) % 40) - 20f
            val rad = Math.toRadians((angle - 90f).toDouble())
            val x = cx + dist * cos(rad).toFloat()
            val y = cy + dist * sin(rad).toFloat()

            dotPaint.color = colorFor(s)
            dotPaint.style = if (known) Paint.Style.FILL else Paint.Style.STROKE
            dotPaint.strokeWidth = 2 * density
            Glyph.draw(canvas, glyph, x, y, sizeFor(s.rssi), dotPaint)

            hitX.add(x); hitY.add(y); hitS.add(s)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            var best = -1
            var bestD = 28f * density // generous finger-sized target
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
}
