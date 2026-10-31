package org.owlear.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager

/**
 * Fake cell tower (IMSI catcher) warning signs, without root.
 *
 * Tech: reads the cell the phone is registered to (TelephonyManager.getAllCellInfo) every
 * 30 s. Two red flags:
 *  1. The phone is on 2G (GSM/CDMA). US carriers have shut 2G down, and fake towers often
 *     force phones onto 2G because its encryption is weak or missing.
 *  2. The registered 4G cell reports an impossible area code (TAC 0 or 65535), which real
 *     towers don't use but cheap fake ones sometimes do.
 * Mechanic version: a check-engine light, not a diagnosis. It says "pull over and look",
 * never "this is definitely a fake tower".
 *
 * Without root, Android hides the deeper signs (encryption turned off, forced re-identify
 * requests), so this can miss a good fake tower. Nothing is logged or sent anywhere.
 */
object CellWatch {

    enum class Level { OK, SUSPICIOUS, UNAVAILABLE }

    data class Reading(val level: Level, val summary: String, val at: Long)

    private const val REALERT_GAP_MS = 30 * 60_000L
    private var lastAlertAt = 0L

    /** Returns the reading, plus true if this reading should send a notification. */
    @SuppressLint("MissingPermission")
    fun check(context: Context, now: Long): Pair<Reading, Boolean> {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return Reading(Level.UNAVAILABLE, context.getString(R.string.cell_no_radio), now) to false
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return Reading(Level.UNAVAILABLE, context.getString(R.string.cell_allow_location), now) to false
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return Reading(Level.UNAVAILABLE, context.getString(R.string.cell_unavailable), now) to false

        val cells: List<CellInfo> = try {
            tm.allCellInfo ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val serving = cells.filter { it.isRegistered }
        if (serving.isEmpty()) {
            return Reading(Level.UNAVAILABLE, context.getString(R.string.cell_not_connected), now) to false
        }

        val reasons = ArrayList<String>()
        if (serving.any { it is CellInfoGsm || it is CellInfoCdma }) {
            reasons.add(context.getString(R.string.cell_reason_2g))
        }
        for (c in serving) {
            if (c is CellInfoLte && Build.VERSION.SDK_INT >= 28) {
                val tac = c.cellIdentity.tac
                if (tac == 0 || tac == 65535) reasons.add(context.getString(R.string.cell_reason_tac, tac))
            }
        }

        if (reasons.isEmpty()) {
            val gen = when {
                Build.VERSION.SDK_INT >= 29 && serving.any { it is CellInfoNr } -> "5G"
                serving.any { it is CellInfoLte } -> "4G"
                serving.any { it is CellInfoWcdma } -> "3G"
                else -> context.getString(R.string.cell_generic)
            }
            return Reading(Level.OK, context.getString(R.string.cell_ok, gen), now) to false
        }

        val alert = now - lastAlertAt > REALERT_GAP_MS
        if (alert) lastAlertAt = now
        val text = context.getString(
            R.string.cell_suspicious,
            reasons.joinToString(context.getString(R.string.reason_separator)),
        )
        return Reading(Level.SUSPICIOUS, text, now) to alert
    }
}
