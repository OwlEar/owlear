package org.owlear.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The radar's icon set. Each group of equipment gets its own shape so the radar needs no words.
 * Shape = what it is, colour = how worried to be, size = how strong the signal is,
 * filled = direction known, outline = direction not known yet.
 *
 * Mechanic version: dashboard warning lights. Learn the symbol once; the legend is the manual.
 */
enum class Glyph(val titleRes: Int, val sector: Float) {
    TRACKER(R.string.glyph_tracker, 0f),       // circle
    VEHICLE(R.string.glyph_vehicle, 60f),      // square
    BODYCAM(R.string.glyph_bodycam, 120f),     // diamond
    CAMERA(R.string.glyph_camera, 180f),       // triangle
    GLASSES(R.string.glyph_glasses, 300f),     // two rings
    OTHER(R.string.glyph_other, 330f);         // star (hidden cams, your own entries)

    val title: String get() = L10n.s(titleRes)

    companion object {
        fun of(s: Sighting): Glyph = when (s.category) {
            Category.APPLE, Category.SAMSUNG, Category.TILE, Category.CHIPOLO, Category.GOOGLE -> TRACKER
            Category.FLEET, Category.POSSIBLE_FLEET -> VEHICLE
            Category.AXON -> BODYCAM
            Category.FLOCK -> CAMERA
            Category.GLASSES, Category.POSSIBLE_GLASSES -> GLASSES
            else -> if (s.kind == Kind.TRACKER) TRACKER else OTHER
        }

        private val path = Path()

        /** Draws [g] centred on (x, y). [size] is the half-width. */
        fun draw(canvas: Canvas, g: Glyph, x: Float, y: Float, size: Float, paint: Paint) {
            when (g) {
                TRACKER -> canvas.drawCircle(x, y, size, paint)
                VEHICLE -> canvas.drawRect(x - size * 0.9f, y - size * 0.9f, x + size * 0.9f, y + size * 0.9f, paint)
                BODYCAM -> {
                    path.reset()
                    path.moveTo(x, y - size * 1.2f); path.lineTo(x + size, y)
                    path.lineTo(x, y + size * 1.2f); path.lineTo(x - size, y); path.close()
                    canvas.drawPath(path, paint)
                }
                CAMERA -> {
                    path.reset()
                    path.moveTo(x, y - size * 1.15f); path.lineTo(x + size * 1.1f, y + size * 0.85f)
                    path.lineTo(x - size * 1.1f, y + size * 0.85f); path.close()
                    canvas.drawPath(path, paint)
                }
                GLASSES -> {
                    canvas.drawCircle(x - size * 0.6f, y, size * 0.55f, paint)
                    canvas.drawCircle(x + size * 0.6f, y, size * 0.55f, paint)
                }
                OTHER -> {
                    path.reset()
                    for (i in 0 until 10) {
                        val r = if (i % 2 == 0) size * 1.2f else size * 0.5f
                        val a = Math.toRadians((i * 36 - 90).toDouble())
                        val px = x + r * cos(a).toFloat()
                        val py = y + r * sin(a).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}

/** A small view that shows one radar icon: used in the filter chips and the legend. */
class GlyphView(context: Context, val glyph: Glyph, var color: Int, var filled: Boolean = true) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = color
        paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        val size = minOf(width, height) * 0.3f
        Glyph.draw(canvas, glyph, width / 2f, height / 2f, size, paint)
    }
}

/**
 * Rough distance from signal strength (log-distance path-loss model).
 *
 * Tech: d = 10 ^ ((P1m − RSSI) / (10·n)). P1m is the strength one metre away: about −59 dBm for a
 * Bluetooth tag, about −40 dBm for a Wi-Fi router, which transmits far louder. n ≈ 2.7 allows for
 * walls, people and cars. Real results can be off by 2–3× either way, so it's always shown as "~".
 * Mechanic version: judging how far off a truck is by how loud its engine sounds. A diesel
 * (Wi-Fi router) sounds close from much farther away than a scooter (Bluetooth tag).
 */
object Distance {
    private const val PATH_LOSS = 2.7

    fun meters(rssi: Int, source: String): Double {
        val oneMeter = if (source == "Wi-Fi") -40.0 else -59.0
        return 10.0.pow((oneMeter - rssi) / (10.0 * PATH_LOSS)).coerceIn(0.5, 500.0)
    }

    /** Countries that measure short distances in feet. */
    val usesFeet: Boolean
        get() = Locale.getDefault().country.uppercase() in setOf("US", "LR", "MM")

    /** Radar rings, in metres, paired with the number printed on them. */
    fun rings(): List<Pair<Double, Int>> =
        if (usesFeet) listOf(10, 30, 100, 300).map { it * 0.3048 to it }
        else listOf(3, 10, 30, 100).map { it.toDouble() to it }

    /** "~12 m" / "~40 ft", rounded to a sensible step. */
    fun label(meters: Double): String {
        val v = if (usesFeet) meters / 0.3048 else meters
        val rounded = when {
            v < 10 -> v.toInt().coerceAtLeast(1)
            v < 100 -> ((v / 5).toInt() * 5)
            else -> ((v / 50).toInt() * 50)
        }
        return L10n.s(if (usesFeet) R.string.dist_ft else R.string.dist_m, rounded)
    }

    /** 0..1 position on the radar: log scale, 1 m near the centre, outer ring at the edge. */
    fun fraction(meters: Double): Float {
        val outer = rings().last().first
        return (ln(meters.coerceAtLeast(1.0)) / ln(outer)).toFloat().coerceIn(0.06f, 1f)
    }
}
