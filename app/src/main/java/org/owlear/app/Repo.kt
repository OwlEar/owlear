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
    var source = "BT"
    var heardName: String? = null
    var confidence = Confidence.HIGH
    /** Bluetooth SIG company ID from the advertisement, or -1. Used to name the maker. */
    var companyId = -1

    /** Name of the saved place where this fixed signal is a regular (label only). */
    var regularAt: String? = null
    /** Name of the saved place that muted this signal's alert, if any. */
    var mutedAt: String? = null
    var fingerprint: String? = null
    /** The person's own name for this device, if they tagged it. */
    var tag: DeviceTag? = null
    /** What OwlEar calls it, before any tag name is added in front. */
    var baseLabel = label

    /** Strongest signal heard in each 10° compass slice during a sweep. */
    val sweep = IntArray(36) { Int.MIN_VALUE }
    var bearing: Float? = null
    var bearingAt = 0L

    /** Recent signal strengths (newest last), at most one per second, for the details graph. */
    val history = ArrayDeque<Int>()
    private var historyAt = 0L

    fun addHistory(rssi: Int, now: Long) {
        if (now - historyAt < 1_000L) return
        historyAt = now
        history.addLast(rssi)
        while (history.size > HISTORY_MAX) history.removeFirst()
    }

    val minutesTracked: Long get() = (lastSeen - firstSeen) / 60_000L

    companion object {
        const val HISTORY_MAX = 90
    }
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

    /**
     * How long a signal stays on the Nearby list after it was last heard. Bluetooth repeats
     * several times a second; Wi-Fi is only re-read every 30 s, so it needs a little longer.
     * Dropping off the list is also what counts as "left" for watchlist alerts.
     */
    const val LIVE_BT_MS = 10_000L
    const val LIVE_WIFI_MS = 35_000L

    /**
     * Watchlist (and camera glasses) alerts sound once when a device is first heard, then stay
     * quiet. The device has to leave (drop off the list) and stay gone for this long before
     * coming back sounds again, so one camera parked down the street doesn't keep beeping.
     */
    const val WATCH_COOLDOWN_MS = 10 * 60_000L

    fun liveMs(s: Sighting): Long = if (s.source == "Wi-Fi") LIVE_WIFI_MS else LIVE_BT_MS
    fun isLive(s: Sighting, now: Long): Boolean = now - s.lastSeen < liveMs(s)

    /**
     * Watch-alert memory per "source address": when it last sounded and when it was last heard.
     * Kept apart from [Sighting] on purpose: a sighting can be rebuilt (category changed, a
     * nameless advertisement in between), and that must not count as a "first detection".
     */
    private class WatchState(var alertedAt: Long, var heardAt: Long)
    private val watchStates = HashMap<String, WatchState>()

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

    /** A Wi-Fi network: watchlist matches only. */
    fun recordWifi(bssid: String, ssid: String?, rssi: Int, now: Long): List<Alert> =
        recordDevice(bssid, ssid, rssi, null, null, now, "Wi-Fi")

    @Synchronized
    private fun recordDevice(
        address: String, name: String?, rssi: Int, tracker: Classified?, spotted: Spotted?,
        now: Long, source: String, companyId: Int = -1,
    ): List<Alert> {
        val key = "$source $address"
        val existing = sightings[key]
        // Bluetooth devices often send their name only in every other packet. Without this, a
        // name-matched watchlist device would flip to "everything else" and back, and every
        // flip back looked like a brand new detection (and beeped again).
        val matchName = name ?: existing?.takeIf { it.kind == Kind.WATCHLIST }?.heardName
        val watch = if (tracker == null && spotted == null) Watchlist.match(address, matchName) else null
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

        val s = if (existing != null && existing.kind == kind && existing.category == category) existing
        else Sighting(address, label, kind, now, category).also { sightings[key] = it }
        s.source = source
        s.baseLabel = label
        if (name != null) s.heardName = name
        if (companyId >= 0) s.companyId = companyId
        if (watch != null) s.confidence = watch.confidence
        if (spotted != null) {
            s.confidence = spotted.confidence
        }
        applyTag(s, key)

        s.lastSeen = now
        s.rssi = rssi
        s.addHistory(rssi, now)
        s.count++
        if (tracker != null && tracker.separated) s.separated = true

        if (key == findKey) {
            findReadings.addLast(now to rssi)
            while (findReadings.size > 0 && now - findReadings.first().first > FIND_KEEP_MS) findReadings.removeFirst()
        }

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
        // Tagged devices still alert unless the person chose to silence that tag.
        val silenced = s.tag?.silent == true
        if (kind == Kind.TRACKER && mode == Mode.ALERT && !silenced && s.separated && !s.followAlerted &&
            address !in ignored && isFollowing(s)
        ) {
            s.followAlerted = true
            alerts.add(Alert.Following(s))
        }
        // "Possible" maker-only matches show in the app but don't buzz your phone.
        if (kind == Kind.WATCHLIST || kind == Kind.SPOTTED) {
            val st = watchStates.getOrPut(key) { WatchState(0L, 0L) }
            // A new visit: heard for the first time, or back after being gone the whole cooldown.
            if (st.heardAt == 0L || now - st.heardAt >= WATCH_COOLDOWN_MS) st.alertedAt = 0L
            st.heardAt = now
            // One sound per visit, the first moment it's allowed to sound.
            if (mode == Mode.ALERT && !silenced && s.confidence == Confidence.HIGH && s.mutedAt == null &&
                st.alertedAt == 0L
            ) {
                st.alertedAt = now
                alerts.add(Alert.WatchHit(s))
            }
        }
        return alerts
    }

    /**
     * Looks up the person's tag for this signal by the one-way fingerprint of "source address"
     * (the same fingerprint saved places use), and puts the tag name in front of the label.
     * The label is rebuilt every time, so it follows a language change.
     */
    private fun applyTag(s: Sighting, key: String) {
        s.tag = if (Store.hasTags()) {
            (s.fingerprint ?: Vault.fingerprint(key)?.also { s.fingerprint = it })?.let { Store.tagFor(it) }
        } else null
        val what = if (s.kind == Kind.OTHER) Detection.describeOther(s) else s.baseLabel
        s.label = s.tag?.let { L10n.s(R.string.tagged_label, it.name, what) } ?: s.baseLabel
    }

    /** One-way fingerprint for tagging this signal, or null if the Keystore misbehaves. */
    @Synchronized
    fun fingerprintOf(s: Sighting): String? =
        s.fingerprint ?: Vault.fingerprint("${s.source} ${s.address}")?.also { s.fingerprint = it }

    /** Re-applies tags to everything in memory, right after a tag is added, changed or removed. */
    @Synchronized
    fun retag() {
        for (s in sightings.values) applyTag(s, "${s.source} ${s.address}")
    }

    /** True if the person has switched this sighting's category off since it was heard. */
    fun isHidden(s: Sighting): Boolean = s.category != null && Store.mode(s.category) == Mode.OFF

    fun isFollowing(s: Sighting): Boolean =
        s.lastSeen - s.firstSeen >= FOLLOW_MIN_MS && s.places.size >= FOLLOW_MIN_PLACES

    @Synchronized
    fun snapshot(): List<Sighting> = sightings.values.toList()

    /** Copy of a sighting's strength history, safe to read from the screen. */
    @Synchronized
    fun historyOf(s: Sighting): List<Int> = s.history.toList()

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
        watchStates.values.removeAll { now - it.heardAt > KEEP_TAGGED_MS }
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

    // ---- "Find it" (hot/cold) ----

    /** The one signal being hunted with Find it, as "source address"; null when not hunting. */
    @Volatile var findKey: String? = null
    private val findReadings = ArrayDeque<Pair<Long, Int>>()
    private const val FIND_KEEP_MS = 60_000L

    @Synchronized
    fun startFind(s: Sighting) {
        findKey = "${s.source} ${s.address}"
        findReadings.clear()
        // Start from the last thing heard, so the meter isn't blank while the first packet arrives.
        if (s.count > 0) findReadings.addLast(s.lastSeen to s.rssi)
    }

    @Synchronized
    fun stopFind() {
        findKey = null
        findReadings.clear()
    }

    /** Copy of every reading of the hunted signal from the last minute, oldest first. */
    @Synchronized
    fun findHistory(): List<Pair<Long, Int>> = findReadings.toList()

    /** True while the radio should listen as fast as it can (direction sweep or Find it). */
    fun wantsFastScan(now: Long): Boolean = findKey != null || now < sweepUntil

    fun hasFreshBearing(s: Sighting, now: Long): Boolean =
        s.bearing != null && now - s.bearingAt < BEARING_VALID_MS

    @Synchronized
    fun clear() {
        stopFind()
        sightings.clear()
        ignored.clear()
        watchStates.clear()
    }
}
