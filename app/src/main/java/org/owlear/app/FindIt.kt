package org.owlear.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Turns the raw readings of one signal into a steady 0–100 "how close" number and a
 * warmer/colder trend.
 *
 * Tech: Bluetooth strength (RSSI) jumps ±5 dB from one packet to the next, so we take the
 * median of the last 2.5 s and compare it to the median of 3.5–7 s ago. A change of 3 dB or
 * more counts as a trend. Wi-Fi only updates every ~30 s, so when there aren't enough recent
 * readings we fall back to the last few readings, whatever their age.
 * Mechanic version: you don't tune an idle by one flick of the tach needle. You watch where
 * it settles, then whether it settles higher or lower after you turn the screw.
 */
object FindMeter {
    class Reading(val level: Int, val dbm: Int, val trend: Int, val heardAgoMs: Long)

    private const val RECENT_MS = 2_500L
    private const val OLDER_FROM_MS = 7_000L
    private const val OLDER_TO_MS = 3_500L
    private const val TREND_DB = 3

    fun read(readings: List<Pair<Long, Int>>, now: Long): Reading? {
        if (readings.isEmpty()) return null
        var recent = readings.filter { now - it.first <= RECENT_MS }
        if (recent.size < 3) recent = readings.takeLast(3)
        val firstRecent = recent.first().first
        var older = readings.filter { now - it.first in OLDER_TO_MS..OLDER_FROM_MS && it.first < firstRecent }
        if (older.isEmpty()) older = readings.filter { it.first < firstRecent }.takeLast(3)
        val dbm = median(recent.map { it.second })
        val trend = if (older.isEmpty()) 0 else {
            val diff = dbm - median(older.map { it.second })
            when {
                diff >= TREND_DB -> 1
                diff <= -TREND_DB -> -1
                else -> 0
            }
        }
        return Reading(level(dbm), dbm, trend, now - readings.last().first)
    }

    /** −100 dBm (barely there) → 0, −40 dBm (right on top of it) → 100. */
    fun level(dbm: Int): Int = ((dbm + 100) * 100 / 60).coerceIn(0, 100)

    private fun median(v: List<Int>): Int {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** 0 cold, 1 cool, 2 warm, 3 hot. */
    fun bucket(level: Int): Int = when {
        level >= 80 -> 3
        level >= 55 -> 2
        level >= 30 -> 1
        else -> 0
    }
}

/** Half-circle gauge with the big 0–100 number in the middle. */
class FindGauge(context: Context) : View(context) {
    var level = 0
    var word = ""
    var stale = true

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 18f * density
        color = Color.parseColor("#24392E")
    }
    private val arc = Paint(track)
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 64f * density
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 22f * density
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = track.strokeWidth
        val r = minOf(width / 2f - pad, height - pad * 2f)
        val cx = width / 2f
        val cy = pad + r
        box.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, 180f, 180f, false, track)
        val c = if (stale) DIM else colorFor(level)
        arc.color = c
        if (level > 0) canvas.drawArc(box, 180f, 180f * level / 100f, false, arc)
        number.color = c
        canvas.drawText(if (stale) "–" else level.toString(), cx, cy - r * 0.15f, number)
        caption.color = c
        canvas.drawText(word, cx, cy + caption.textSize * 0.2f, caption)
    }

    companion object {
        val DIM = Color.parseColor("#8A968F")

        fun colorFor(level: Int): Int = when (FindMeter.bucket(level)) {
            3 -> Color.parseColor("#FF5A5F")
            2 -> Color.parseColor("#FFC24B")
            1 -> Color.parseColor("#3DDC84")
            else -> Color.parseColor("#5AA9FF")
        }
    }
}

/**
 * "Find it": one big hot/cold meter for one signal, with a beep (or buzz) that speeds up as
 * you get closer. Walk around and follow the number. Scanning runs at full speed while open.
 * Mechanic version: a stud finder. You don't need a map of the wall, just "louder here".
 */
class FindScreen(private val act: Activity, private val s: Sighting, private val iconColor: Int) {

    private val handler = Handler(Looper.getMainLooper())
    private val dialog = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val density = act.resources.displayMetrics.density
    private val wifi = s.source == "Wi-Fi"
    private val staleMs = if (wifi) 75_000L else 5_000L

    private val fg = Color.parseColor("#E8EEEA")
    private val dim = Color.parseColor("#8A968F")
    private val green = Color.parseColor("#3DDC84")
    private val bg = Color.parseColor("#0B0F0D")

    private lateinit var gauge: FindGauge
    private lateinit var trendText: TextView
    private lateinit var distText: TextView
    private lateinit var soundButton: Button
    private val spark = ArrayList<Int>()
    private lateinit var sparkView: SparkView

    private var tone: ToneGenerator? = null
    private var lastSaid = ""
    private var latest: FindMeter.Reading? = null
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private fun dp(v: Int) = (v * density).toInt()

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(act).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 14f * density
    }

    fun show() {
        // Hold the screen in whatever way it's facing right now. Turning the phone would
        // otherwise rebuild the main screen, and that tears Find it down mid-walk.
        savedOrientation = act.requestedOrientation
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        Repo.startFind(s)
        nudgeScanner()
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (e: Exception) {
            tone = null // no audio on this phone; the meter still works
        }
        dialog.setContentView(build())
        dialog.window?.let { w ->
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Store.secureScreen) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.setOnDismissListener { close() }
        dialog.show()
        handler.post(meterTick)
        handler.post(sparkTick)
        handler.post(soundTick)
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun close() {
        handler.removeCallbacksAndMessages(null)
        Repo.stopFind()
        nudgeScanner()
        tone?.release()
        tone = null
        act.requestedOrientation = savedOrientation
    }

    /** Tells the scanner to switch speed (fast while Find it is open, normal after). */
    private fun nudgeScanner() {
        if (!Repo.running) return
        try {
            act.startService(Intent(act, ScanService::class.java).setAction(ScanService.ACTION_FIND))
        } catch (e: Exception) {
            // scanning carries on at normal speed
        }
    }

    private fun build(): View {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (s.kind != Kind.OTHER) {
                addView(GlyphView(act, Glyph.of(s), iconColor), LinearLayout.LayoutParams(dp(40), dp(40)))
            }
            val name = if (s.kind == Kind.OTHER) Detection.describeOther(s) else s.label
            addView(label(name, 20f, fg, bold = true).apply {
                if (s.kind != Kind.OTHER) setPadding(dp(10), 0, 0, 0)
            })
        })
        col.addView(label(act.getString(R.string.find_it_title), 13f, dim).apply { setPadding(0, dp(4), 0, 0) })

        gauge = FindGauge(act).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        col.addView(gauge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)).apply {
            topMargin = dp(16)
        })

        trendText = label("", 22f, fg, bold = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(trendText)
        distText = label("", 14f, dim).apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(distText)

        sparkView = SparkView(act, spark, green, dim)
        col.addView(sparkView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(16)
        })
        col.addView(label(act.getString(R.string.detail_history), 10f, dim))

        col.addView(label(act.getString(R.string.find_tips), 13f, dim).apply { setPadding(0, dp(16), 0, 0) })
        if (wifi) col.addView(label(act.getString(R.string.find_wifi_slow), 13f, dim).apply { setPadding(0, dp(8), 0, 0) })

        soundButton = Button(act).apply {
            isAllCaps = false
            setTextColor(fg)
            background = rounded(Color.parseColor("#24392E"))
            setOnClickListener {
                setSound((sound() + 1) % 3)
                drawSoundButton()
            }
        }
        drawSoundButton()
        val done = Button(act).apply {
            text = act.getString(R.string.close)
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = rounded(green)
            setOnClickListener { dismiss() }
        }
        col.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(soundButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            addView(done, LinearLayout.LayoutParams(0, dp(48), 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        return ScrollView(act).apply {
            setBackgroundColor(bg)
            addView(col)
        }
    }

    // ---- live updates ----

    private val meterTick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 250L)
        }
    }

    private val sparkTick = object : Runnable {
        override fun run() {
            spark.clear()
            spark.addAll(Repo.historyOf(s))
            sparkView.invalidate()
            handler.postDelayed(this, 1_000L)
        }
    }

    private fun update() {
        val now = System.currentTimeMillis()
        val r = FindMeter.read(Repo.findHistory(), now)
        latest = r
        val words = act.resources.getStringArray(R.array.find_buckets)
        val stale = r == null || r.heardAgoMs > staleMs
        gauge.stale = stale
        gauge.level = r?.level ?: 0
        gauge.word = if (r == null || stale) "" else words[FindMeter.bucket(r.level)]
        gauge.invalidate()

        val say: String
        when {
            !Repo.running -> {
                trendText.text = act.getString(R.string.status_start_first)
                trendText.setTextColor(dim)
                distText.text = ""
                say = trendText.text.toString()
            }
            r == null -> {
                trendText.text = act.getString(R.string.find_waiting)
                trendText.setTextColor(dim)
                distText.text = ""
                say = trendText.text.toString()
            }
            stale -> {
                trendText.text = act.getString(R.string.find_lost, (r.heardAgoMs / 1000L).toInt())
                trendText.setTextColor(dim)
                distText.text = ""
                say = act.getString(R.string.find_lost_short)
            }
            else -> {
                val (textRes, color) = when (r.trend) {
                    1 -> R.string.find_warmer to FindGauge.colorFor(100)
                    -1 -> R.string.find_colder to FindGauge.colorFor(0)
                    else -> R.string.find_steady to fg
                }
                trendText.text = act.getString(textRes)
                trendText.setTextColor(color)
                distText.text = act.getString(R.string.find_distance,
                    Distance.label(Distance.meters(r.dbm, s.source)).replace("~", ""), r.dbm)
                say = words[FindMeter.bucket(r.level)] + ". " + trendText.text
            }
        }
        gauge.contentDescription = if (stale || r == null) say
        else act.getString(R.string.find_gauge_desc, r.level, words[FindMeter.bucket(r.level)])
        // TalkBack: speak only when the word or trend changes, not four times a second.
        if (say != lastSaid) {
            lastSaid = say
            gauge.announceForAccessibility(say)
        }
    }

    // ---- beep / buzz ----

    private val soundTick = object : Runnable {
        override fun run() {
            val r = latest
            var wait = 1_000L
            if (r != null && r.heardAgoMs <= staleMs && Repo.running) {
                when (sound()) {
                    SOUND_BEEP -> try {
                        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                    } catch (e: Exception) {
                        // audio busy; skip this beep
                    }
                    SOUND_BUZZ -> buzz()
                }
                // Weak signal: a beep every 1.5 s. Right on top of it: about 7 a second.
                wait = 1_500L - r.level * 135L / 10L
            }
            handler.postDelayed(this, wait.coerceAtLeast(150L))
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val v = act.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // no vibration motor
        }
    }

    private fun prefs() = act.getSharedPreferences("owlear_settings", Context.MODE_PRIVATE)
    private fun sound(): Int = prefs().getInt("find_sound", SOUND_BEEP).coerceIn(0, 2)
    private fun setSound(v: Int) = prefs().edit().putInt("find_sound", v).apply()

    private fun drawSoundButton() {
        soundButton.text = act.getString(when (sound()) {
            SOUND_BEEP -> R.string.find_sound_beep
            SOUND_BUZZ -> R.string.find_sound_buzz
            else -> R.string.find_sound_off
        })
    }

    companion object {
        const val SOUND_BEEP = 0
        const val SOUND_BUZZ = 1
        const val SOUND_OFF = 2
    }
}
