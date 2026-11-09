package org.owlear.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The icon set. Each group of equipment gets its own shape, so a row in the Nearby list can be
 * read at a glance. Shape = what it is, colour = how worried to be.
 *
 * Mechanic version: dashboard warning lights. Learn the symbol once; the legend is the manual.
 */
enum class Glyph(val titleRes: Int) {
    TRACKER(R.string.glyph_tracker),  // circle
    VEHICLE(R.string.glyph_vehicle),  // square
    BODYCAM(R.string.glyph_bodycam),  // diamond
    CAMERA(R.string.glyph_camera),    // triangle
    TAGGED(R.string.glyph_tagged),    // hexagon (devices you named)
    GLASSES(R.string.glyph_glasses),  // two rings
    OTHER(R.string.glyph_other);      // star (hidden cams, your own entries)

    val title: String get() = L10n.s(titleRes)

    companion object {
        fun of(s: Sighting): Glyph = if (s.tag != null) TAGGED else when (s.category) {
            Category.APPLE, Category.SAMSUNG, Category.TILE, Category.CHIPOLO, Category.GOOGLE -> TRACKER
            Category.FLEET, Category.POSSIBLE_FLEET -> VEHICLE
            Category.AXON -> BODYCAM
            Category.FLOCK -> CAMERA
            Category.GLASSES, Category.POSSIBLE_GLASSES -> GLASSES
            else -> if (s.kind == Kind.TRACKER) TRACKER else OTHER
        }

        /** Red = may be following you, yellow = on your alert list, white = shown only, grey = maker match only. */
        fun colorFor(s: Sighting): Int = when {
            s.kind == Kind.TRACKER && s.separated && Repo.isFollowing(s) -> RED
            s.confidence == Confidence.POSSIBLE -> DIM
            s.category != null && Store.mode(s.category) == Mode.ALERT && s.mutedAt == null &&
                s.kind != Kind.TRACKER -> AMBER
            else -> WHITE
        }

        private val RED = Color.parseColor("#FF5A5F")
        private val AMBER = Color.parseColor("#FFC24B")
        private val WHITE = Color.parseColor("#E8EEEA")
        private val DIM = Color.parseColor("#8A968F")

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
                TAGGED -> {
                    path.reset()
                    for (i in 0 until 6) {
                        val a = Math.toRadians((i * 60 - 90).toDouble())
                        val px = x + size * 1.1f * cos(a).toFloat()
                        val py = y + size * 1.1f * sin(a).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
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

/** A small view that shows one icon: used in the Nearby list, the details card and the legend. */
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

    private const val PREFS = "owlear_settings"
    private const val KEY_FEET = "distance_in_feet"

    /** The person's own choice from Settings; null until they pick one. */
    @Volatile private var chosenFeet: Boolean? = null

    /** Countries that measure short distances in feet. */
    private val countryUsesFeet: Boolean
        get() = Locale.getDefault().country.uppercase() in setOf("US", "LR", "MM")

    /** Feet or meters: the Settings choice if there is one, otherwise the phone's country. */
    val usesFeet: Boolean
        get() = chosenFeet ?: countryUsesFeet

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        chosenFeet = if (p.contains(KEY_FEET)) p.getBoolean(KEY_FEET, false) else null
    }

    fun setFeet(context: Context, feet: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FEET, feet).apply()
        chosenFeet = feet
    }

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
}
