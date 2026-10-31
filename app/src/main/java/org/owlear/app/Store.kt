package org.owlear.app

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.EnumMap
import java.util.TimeZone
import java.util.UUID

/** What to do when a category of equipment is heard. */
enum class Mode(private val titleRes: Int) {
    ALERT(R.string.mode_alert),
    SHOW(R.string.mode_show),
    OFF(R.string.mode_off);

    val title: String get() = L10n.s(titleRes)
}

/** A spot the person saved and named, like Home or Work. */
class Place(
    val id: String,
    var name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Float,
    val muted: MutableSet<Category>,
) {
    fun contains(loc: Location): Boolean {
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, loc.latitude, loc.longitude, out)
        return out[0] <= radiusM
    }
}

/** One fixed signal heard at one saved place, and on which days. */
class Learned(val placeId: String, val days: MutableSet<Long>, var lastDay: Long)

/**
 * Everything OwlEar keeps between launches, in one encrypted file.
 *
 * What is saved: your category choices, your named places (a centre point rounded to about
 * 11 m), your watchlist, and fingerprints of fixed gear heard at those places.
 * What is never saved: where you went, when you moved, raw signal addresses, trackers.
 *
 * The file lives in no-backup storage, cloud backup and phone-to-phone transfer are blocked in
 * the manifest, and the key stays in this phone's Keystore, so a copy is useless elsewhere.
 */
object Store {
    const val PLACE_RADIUS_M = 150f
    const val REGULAR_DAYS = 3
    const val FORGET_AFTER_DAYS = 30L
    private const val MAX_DAYS_KEPT = 10
    private const val FILE = "vault.bin"

    private var file: File? = null
    private var loaded = false
    private var dirty = false

    private val modes = EnumMap<Category, Mode>(Category::class.java)
    private val places = mutableListOf<Place>()
    private val learned = HashMap<String, Learned>()

    @Volatile var secureScreen = false
        private set
    var watchlistRaw: String? = null
        private set
    /** Which set of built-in watchlist entries this install has already been offered. */
    var watchlistDefaultsVersion = 1
        private set

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val f = File(context.applicationContext.noBackupFilesDir, FILE)
        file = f
        if (!f.exists()) return
        try {
            fromJson(JSONObject(String(Vault.open(f.readBytes()))))
        } catch (e: Exception) {
            // Unreadable (keys reset by the system, or a damaged file). Start clean rather than crash.
            f.delete()
        }
    }

    // ---- category modes ----

    @Synchronized
    fun mode(c: Category): Mode = modes[c] ?: c.defaultMode

    @Synchronized
    fun setMode(c: Category, m: Mode) {
        modes[c] = if (m == Mode.ALERT && !c.canAlert) Mode.SHOW else m
        save()
    }

    // ---- places ----

    @Synchronized
    fun places(): List<Place> = places.toList()

    @Synchronized
    fun placeAt(loc: Location): Place? = places.firstOrNull { it.contains(loc) }

    @Synchronized
    fun addPlace(name: String, loc: Location): Place {
        // Round to 4 decimals (about 11 m): enough to know you're home, no more.
        val p = Place(
            UUID.randomUUID().toString().take(8), name,
            Math.round(loc.latitude * 1e4) / 1e4, Math.round(loc.longitude * 1e4) / 1e4,
            PLACE_RADIUS_M, mutableSetOf(),
        )
        places.add(p)
        save()
        return p
    }

    @Synchronized
    fun setMuted(place: Place, muted: Set<Category>) {
        place.muted.clear()
        place.muted.addAll(muted.filter { !it.isTracker })
        save()
    }

    @Synchronized
    fun removePlace(place: Place) {
        places.removeAll { it.id == place.id }
        learned.values.removeAll { it.placeId == place.id }
        save()
    }

    // ---- label-only area learning ----

    private fun today(now: Long): Long =
        (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L

    /**
     * Notes that a fixed signal was heard at a saved place today. Returns the place name once it
     * has been heard there on REGULAR_DAYS separate days. This only labels; it never silences.
     */
    @Synchronized
    fun learn(fingerprint: String, place: Place, now: Long): String? {
        val day = today(now)
        val e = learned.getOrPut("$fingerprint@${place.id}") { Learned(place.id, mutableSetOf(), day) }
        if (e.days.add(day)) {
            e.lastDay = day
            while (e.days.size > MAX_DAYS_KEPT) e.days.remove(e.days.min())
            dirty = true
        }
        return if (e.days.size >= REGULAR_DAYS) place.name else null
    }

    /** Called every so often by the scanner: forgets stale fingerprints and writes changes. */
    @Synchronized
    fun maintain(now: Long) {
        val cutoff = today(now) - FORGET_AFTER_DAYS
        if (learned.values.removeAll { it.lastDay < cutoff }) dirty = true
        if (dirty) save()
    }

    @Synchronized
    fun learnedCount(): Int = learned.size

    // ---- privacy ----

    @Synchronized
    fun setSecureScreen(on: Boolean) {
        secureScreen = on
        save()
    }

    @Synchronized
    fun setWatchlist(raw: String, defaultsVersion: Int = watchlistDefaultsVersion) {
        watchlistRaw = raw
        watchlistDefaultsVersion = defaultsVersion
        save()
    }

    /** Deletes the file and destroys the keys. Nothing can be recovered afterwards. */
    @Synchronized
    fun eraseAll() {
        file?.delete()
        Vault.destroyKeys()
        modes.clear()
        places.clear()
        learned.clear()
        secureScreen = false
        watchlistRaw = null
        watchlistDefaultsVersion = 1
        dirty = false
    }

    // ---- disk ----

    @Synchronized
    private fun save() {
        dirty = false
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeBytes(Vault.seal(toJson().toString().toByteArray()))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            dirty = true // try again on the next maintain()
        }
    }

    private fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("modes", JSONObject().apply { modes.forEach { (c, m) -> put(c.name, m.name) } })
        put("places", JSONArray().apply {
            places.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("r", p.radiusM.toDouble())
                    put("muted", JSONArray(p.muted.map { it.name }))
                })
            }
        })
        put("learned", JSONObject().apply {
            learned.forEach { (k, e) ->
                put(k, JSONObject().apply {
                    put("place", e.placeId)
                    put("days", JSONArray(e.days.toList()))
                    put("last", e.lastDay)
                })
            }
        })
        put("secure", secureScreen)
        watchlistRaw?.let { put("watchlist", it) }
        put("watchlistDefaults", watchlistDefaultsVersion)
    }

    private fun fromJson(o: JSONObject) {
        o.optJSONObject("modes")?.let { m ->
            m.keys().forEach { k ->
                val c = Category.entries.firstOrNull { it.name == k }
                val v = Mode.entries.firstOrNull { it.name == m.optString(k) }
                if (c != null && v != null) modes[c] = v
            }
        }
        o.optJSONArray("places")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i) ?: continue
                val muted = mutableSetOf<Category>()
                p.optJSONArray("muted")?.let { ma ->
                    for (j in 0 until ma.length()) {
                        Category.entries.firstOrNull { it.name == ma.optString(j) }?.let { muted.add(it) }
                    }
                }
                places.add(
                    Place(
                        p.optString("id"), p.optString("name"), p.optDouble("lat"), p.optDouble("lon"),
                        p.optDouble("r", PLACE_RADIUS_M.toDouble()).toFloat(), muted,
                    )
                )
            }
        }
        o.optJSONObject("learned")?.let { l ->
            l.keys().forEach { k ->
                val e = l.optJSONObject(k) ?: return@forEach
                val days = mutableSetOf<Long>()
                e.optJSONArray("days")?.let { d -> for (i in 0 until d.length()) days.add(d.optLong(i)) }
                learned[k] = Learned(e.optString("place"), days, e.optLong("last"))
            }
        }
        secureScreen = o.optBoolean("secure", false)
        watchlistRaw = if (o.has("watchlist")) o.optString("watchlist") else null
        watchlistDefaultsVersion = o.optInt("watchlistDefaults", 1)
    }
}
