package org.owlear.app

import android.bluetooth.le.ScanResult
import android.location.Location

class Sighting(
    val address: String,
    var label: String,
    val kind: Kind,
    val firstSeen: Long,
    val category: Category?,
) {
    var lastSeen = firstSeen
    var rssi = 0
    var count = 0
    var separated = false
    val places = mutableListOf<Location>()
    var followAlerted = false
    var watchAlertedAt = 0L
    var source = "BT"
    var heardName: String? = null
    var confidence = Confidence.HIGH
    var detail: String? = null
    /** Bluetooth SIG company ID from the advertisement, or -1. Used to name the maker. */
    var companyId = -1

    /** Name of the saved place where this fixed signal is a regular (label only). */
    var regularAt: String? = null
    /** Name of the saved place that muted this signal's alert, if any. */
    var mutedAt: String? = null
    var fingerprint: String? = null

    /** Strongest signal heard in each 10° compass slice during a sweep. */
    val sweep = IntArray(36) { Int.MIN_VALUE }
    var bearing: Float? = null
    var bearingAt = 0L

    val minutesTracked: Long get() = (lastSeen - firstSeen) / 60_000L
}

sealed class Alert {
    class Following(val sighting: Sighting) : Alert()
    class WatchHit(val sighting: Sighting) : Alert()
}

/**
 * In-memory record of what the phone has heard. Nothing is written to disk or sent anywhere.
 *
 * "Following" rule: the same tracker (away from its owner) has stayed with you for at least
 * FOLLOW_MIN_MS and has been heard at FOLLOW_MIN_PLACES spots at least PLACE_RADIUS_M apart.
 * Mechanic version: one sighting is a coincidence; the same tag at home, the gas station
 * and work is a pattern.
 */
object Repo {
    const val FOLLOW_MIN_MS = 20 * 60_000L
    const val FOLLOW_MIN_PLACES = 3
    const val PLACE_RADIUS_M = 300f

    private const val KEEP_TAGGED_MS = 6 * 60 * 60_000L
    private const val KEEP_OTHER_MS = 10 * 60_000L
    private const val WATCH_REALERT_GAP_MS = 10 * 60_000L

    private val sightings = LinkedHashMap<String, Sighting>()
    private val ignored = HashSet<String>()

    @Volatile var location: Location? = null
    @Volatile var running = false
    @Volatile var status = ""

    /** Latest cell-network check from [CellWatch]; null until the first check runs. */
    @Volatile var cell: CellWatch.Reading? = null

    /** Compass heading of the phone in degrees (0 = north), fed by the screen's sensor. */
    @Volatile var heading = 0f
    @Volatile var sweepUntil = 0L
    const val BEARING_VALID_MS = 3 * 60_000L

    /** A Bluetooth advertisement. */
    fun record(result: ScanResult, now: Long): List<Alert> {
        val address = result.device?.address ?: return emptyList()
        val tracker = Detection.classifyTracker(result)
        val spotted = if (tracker == null) Detection.classifySpotted(result) else null
        val mfr = result.scanRecord?.manufacturerSpecificData
        val companyId = if (mfr != null && mfr.size() > 0) mfr.keyAt(0) else -1
        return recordDevice(address, result.scanRecord?.deviceName, result.rssi,
            tracker, spotted, now, "BT", companyId)
    }

    /** A Wi-Fi network: watchlist, plus drones that send Remote ID in their beacons. */
    fun recordWifi(bssid: String, ssid: String?, rssi: Int, spotted: Spotted?, now: Long): List<Alert> =
        recordDevice(bssid, ssid, rssi, null, spotted, now, "Wi-Fi")

    @Synchronized
    private fun recordDevice(
        address: String, name: String?, rssi: Int, tracker: Classified?, spotted: Spotted?,
        now: Long, source: String, companyId: Int = -1,
    ): List<Alert> {
        val watch = if (tracker == null && spotted == null) Watchlist.match(address, name) else null
        val category = tracker?.category ?: spotted?.category ?: watch?.category

        // Category switched off: drop it before it's even held in memory.
        val mode = category?.let { Store.mode(it) } ?: Mode.OFF
        if (category != null && mode == Mode.OFF) return emptyList()

        val kind = when {
            tracker != null -> Kind.TRACKER
            spotted != null -> Kind.SPOTTED
            watch != null -> Kind.WATCHLIST
            else -> Kind.OTHER
        }
        val label = tracker?.label ?: spotted?.label ?: watch?.displayLabel ?: (name ?: L10n.s(R.string.unknown_device))

        val key = "$source $address"
        val existing = sightings[key]
        val s = if (existing != null && existing.kind == kind && existing.category == category) existing
        else Sighting(address, label, kind, now, category).also { sightings[key] = it }
        s.source = source
        s.label = label // refreshed each time, so it follows a language change
        if (name != null) s.heardName = name
        if (companyId >= 0) s.companyId = companyId
        if (watch != null) s.confidence = watch.confidence
        if (spotted != null) {
            s.confidence = spotted.confidence
            if (spotted.detail != null) s.detail = spotted.detail
        }

        val previouslySeen = if (s.count == 0) 0L else s.lastSeen
        s.lastSeen = now
        s.rssi = rssi
        s.count++
        if (tracker != null && tracker.separated) s.separated = true

        if (now < sweepUntil && source == "BT") {
            val slice = ((((heading % 360f) + 360f) % 360f) / 10f).toInt().coerceIn(0, 35)
            if (rssi > s.sweep[slice]) s.sweep[slice] = rssi
        }

        val loc = location
        if (loc != null && kind != Kind.OTHER && s.places.none { it.distanceTo(loc) < PLACE_RADIUS_M }) {
            s.places.add(Location(loc))
        }

        // Saved places: label regulars and apply per-place mutes. Trackers are skipped:
        // their addresses rotate daily, and a tag following you home must always alert.
        val place = if (loc != null && (kind == Kind.WATCHLIST || kind == Kind.SPOTTED)) Store.placeAt(loc) else null
        s.mutedAt = if (place != null && category != null && category in place.muted) place.name else null
        if (place != null) {
            val fp = s.fingerprint ?: Vault.fingerprint(key)?.also { s.fingerprint = it }
            if (fp != null) Store.learn(fp, place, now)?.let { s.regularAt = it }
        }

        val alerts = ArrayList<Alert>(1)
        if (kind == Kind.TRACKER && mode == Mode.ALERT && s.separated && !s.followAlerted &&
            address !in ignored && isFollowing(s)
        ) {
            s.followAlerted = true
            alerts.add(Alert.Following(s))
        }
        // "Possible" maker-only matches show in the app but don't buzz your phone.
        if ((kind == Kind.WATCHLIST || kind == Kind.SPOTTED) && mode == Mode.ALERT &&
            s.confidence == Confidence.HIGH && s.mutedAt == null &&
            (s.watchAlertedAt == 0L || now - previouslySeen > WATCH_REALERT_GAP_MS)
        ) {
            s.watchAlertedAt = now
            alerts.add(Alert.WatchHit(s))
        }
        return alerts
    }

    /** True if the person has switched this sighting's category off since it was heard. */
    fun isHidden(s: Sighting): Boolean = s.category != null && Store.mode(s.category) == Mode.OFF

    fun isFollowing(s: Sighting): Boolean =
        s.lastSeen - s.firstSeen >= FOLLOW_MIN_MS && s.places.size >= FOLLOW_MIN_PLACES

    @Synchronized
    fun snapshot(): List<Sighting> = sightings.values.toList()

    @Synchronized
    fun ignore(address: String) {
        ignored.add(address)
    }

    @Synchronized
    fun isIgnored(address: String): Boolean = address in ignored

    @Synchronized
    fun prune(now: Long) {
        val it = sightings.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val keep = if (s.kind == Kind.OTHER) KEEP_OTHER_MS else KEEP_TAGGED_MS
            if (now - s.lastSeen > keep) it.remove()
        }
    }

    // ---- direction finding ("fox hunt" sweep) ----

    @Synchronized
    fun startSweep(now: Long, durationMs: Long) {
        for (s in sightings.values) s.sweep.fill(Int.MIN_VALUE)
        sweepUntil = now + durationMs
    }

    /** Returns how many signals got a direction. */
    @Synchronized
    fun finishSweep(now: Long): Int {
        sweepUntil = 0L
        var found = 0
        for (s in sightings.values) {
            val b = estimateBearing(s.sweep) ?: continue
            s.bearing = b
            s.bearingAt = now
            found++
        }
        return found
    }

    /**
     * Picks the compass slice where the signal was loudest, smoothed with its neighbours.
     * Needs readings from at least 8 slices and a 6 dB loud-to-quiet difference,
     * otherwise the "direction" would just be noise.
     */
    private fun estimateBearing(a: IntArray): Float? {
        val heard = a.filter { it != Int.MIN_VALUE }
        if (heard.size < 8) return null
        if (heard.max() - heard.min() < 6) return null
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until 36) {
            if (a[i] == Int.MIN_VALUE) continue
            var sum = 0.0
            var n = 0
            for (d in -1..1) {
                val v = a[(i + d + 36) % 36]
                if (v != Int.MIN_VALUE) {
                    sum += v
                    n++
                }
            }
            val score = sum / n
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return if (best < 0) null else best * 10f + 5f
    }

    fun hasFreshBearing(s: Sighting, now: Long): Boolean =
        s.bearing != null && now - s.bearingAt < BEARING_VALID_MS

    @Synchronized
    fun clear() {
        sightings.clear()
        ignored.clear()
    }
}
