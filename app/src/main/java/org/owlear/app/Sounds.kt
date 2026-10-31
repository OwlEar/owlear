package org.owlear.app

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * The sound played when a watchlist match (or drone / camera glasses) shows up.
 *
 * Tech: the watchlist notification channel is created silent, and the app plays the chosen
 * sound itself. Android locks a channel's sound once it's created, so this is the only way
 * to let you switch sounds from inside the app. Tones come from the phone's built-in
 * ToneGenerator, so there are no sound files and nothing to download.
 * Mechanic version: instead of one horn wired in at the factory, we put in a switch with a
 * few horns to pick from.
 */
object Sounds {

    private const val PREFS = "owlear_settings"
    private const val KEY = "watch_sound"

    class Choice(private val labelRes: Int, val tone: Int?, val durationMs: Int) {
        val label: String get() = L10n.s(labelRes)
    }

    /** tone = null means "the phone's default notification sound". */
    val choices = listOf(
        Choice(R.string.sound_default, null, 0),
        Choice(R.string.sound_sonar, ToneGenerator.TONE_SUP_PIP, 700),
        Choice(R.string.sound_double, ToneGenerator.TONE_PROP_BEEP2, 400),
        Choice(R.string.sound_chirp, ToneGenerator.TONE_PROP_ACK, 400),
        Choice(R.string.sound_urgent, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900),
        Choice(R.string.sound_silent, -1, 0),
    )

    private val handler = Handler(Looper.getMainLooper())

    fun selected(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 1)
            .coerceIn(0, choices.lastIndex)

    fun select(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, index).apply()
    }

    fun play(context: Context, index: Int = selected(context)) {
        val c = choices.getOrNull(index) ?: return
        try {
            when (c.tone) {
                -1 -> Unit
                null -> {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
                    RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
                }
                else -> {
                    val gen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
                    gen.startTone(c.tone, c.durationMs)
                    handler.postDelayed({ gen.release() }, c.durationMs + 300L)
                }
            }
        } catch (e: Exception) {
            // sound unavailable (e.g. phone on silent); the notification still shows
        }
    }
}
