package org.owlear.app

import android.content.Context

enum class Confidence { HIGH, POSSIBLE }

/**
 * A user-editable signal to watch for.
 *
 * pattern can be:
 *  - a MAC address prefix ("00:30:44" = the maker's ID, the first half of the address)
 *  - "re:<regex>" matched against the whole broadcast name (case-insensitive)
 *  - plain text matched anywhere in the broadcast name
 */
data class WatchEntry(
    val label: String,
    val pattern: String,
    val confidence: Confidence = Confidence.HIGH,
    val category: Category = Category.CUSTOM,
    /** Built-in entries carry a translated name; the English [label] stays as the saved key. */
    val labelRes: Int = 0,
    val labelArg: String? = null,
) {
    /** Name shown on screen: translated for built-ins, as typed for your own entries. */
    val displayLabel: String
        get() = when {
            labelRes == 0 -> label
            labelArg != null -> L10n.s(labelRes, labelArg)
            else -> L10n.s(labelRes)
        }

    val isMacPrefix: Boolean get() = MAC_PREFIX.matches(pattern)
    val isRegex: Boolean get() = pattern.startsWith("re:")

    private val regex: Regex? by lazy {
        if (!isRegex) null
        else try {
            Regex(pattern.removePrefix("re:"), RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            null
        }
    }

    fun matches(address: String, name: String?): Boolean = when {
        isMacPrefix -> address.uppercase().startsWith(pattern.uppercase())
        isRegex -> name != null && regex?.matches(name) == true
        else -> name != null && name.contains(pattern, ignoreCase = true)
    }

    fun describe(): String = when {
        isMacPrefix -> L10n.s(R.string.watch_describe_maker, L10n.ltr(pattern))
        isRegex -> L10n.s(R.string.watch_describe_regex, L10n.ltr(pattern.removePrefix("re:")))
        else -> L10n.s(R.string.watch_describe_contains, L10n.ltr(pattern))
    }

    companion object {
        val MAC_PREFIX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){0,5}$")
    }
}

object Watchlist {
    /** Plain-preferences file used by builds from before the encrypted store (and the rename). */
    private const val LEGACY_PREFS = "wavewatch"
    private const val LEGACY_KEY = "watchlist_v2"
    private const val LEGACY_DEFAULTS_KEY = "defaults_version"

    /** Bump when new built-in entries are added so existing installs pick them up. */
    private const val DEFAULTS_VERSION = 2

    /*
     * Police-fleet Wi-Fi signatures come from US Patent 12,177,766
     * ("Identification of emergency vehicles via wifi beacon analysis with false alarm
     * suppression", filed 2021, issued Dec 2024), which publishes the maker IDs and default
     * network-name patterns of in-vehicle routers it found on police fleets.
     * Mechanic version: the car is unmarked, but its dash router still answers roll call
     * with the factory name stamped on it.
     *
     * Maker-ID-only matches are "possible": the same router brands sit in buses, stores and
     * utility trucks, so they are shown greyed out and never trigger an alert.
     */
    private val defaults = listOf(
        // Axon (body cams, Tasers) - Bluetooth maker ID
        WatchEntry("Axon gear (body cams, Tasers)", "00:25:DF", category = Category.AXON, labelRes = R.string.wl_axon),

        // Flock Safety cameras - Bluetooth / Wi-Fi names
        WatchEntry("Flock camera", "Flock", category = Category.FLOCK, labelRes = R.string.wl_flock),
        WatchEntry("Flock camera (Penguin module)", "Penguin", category = Category.FLOCK, labelRes = R.string.wl_flock_penguin),
        WatchEntry("Flock camera (Pigvision)", "Pigvision", category = Category.FLOCK, labelRes = R.string.wl_flock_pigvision),
        WatchEntry("Flock camera battery", "FS Ext Battery", category = Category.FLOCK, labelRes = R.string.wl_flock_battery),

        // In-vehicle routers, high confidence (factory default network names)
        WatchEntry("Police fleet router (Cradlepoint IBR)", "re:IBR(600C?|900|1100)-[0-9A-F]{3}", category = Category.FLEET, labelRes = R.string.wl_fleet_router, labelArg = "Cradlepoint IBR"),
        WatchEntry("Police fleet router (Cradlepoint IBR1700)", "re:IBR1700-[0-9A-F]{3}", category = Category.FLEET, labelRes = R.string.wl_fleet_router, labelArg = "Cradlepoint IBR1700"),
        WatchEntry("Police fleet router (Compex)", "re:114K-634[0-9A-F]{3}", category = Category.FLEET, labelRes = R.string.wl_fleet_router, labelArg = "Compex"),
        WatchEntry("Police fleet router (InMotion)", "INMOTION", category = Category.FLEET, labelRes = R.string.wl_fleet_router, labelArg = "InMotion"),

        // In-vehicle routers, possible (maker ID only)
        WatchEntry("Possible fleet router (Cradlepoint)", "00:30:44", Confidence.POSSIBLE, Category.POSSIBLE_FLEET, labelRes = R.string.wl_possible_fleet, labelArg = "Cradlepoint"),
        WatchEntry("Possible fleet router (Compex)", "04:F0:21", Confidence.POSSIBLE, Category.POSSIBLE_FLEET, labelRes = R.string.wl_possible_fleet, labelArg = "Compex"),
        WatchEntry("Possible fleet router (Compex)", "00:80:48", Confidence.POSSIBLE, Category.POSSIBLE_FLEET, labelRes = R.string.wl_possible_fleet, labelArg = "Compex"),
        WatchEntry("Possible fleet router (Utility Inc)", "00:09:BC", Confidence.POSSIBLE, Category.POSSIBLE_FLEET, labelRes = R.string.wl_possible_fleet, labelArg = "Utility Inc"),

        // Cheap Wi-Fi cameras in setup mode broadcast their own network with a factory name.
        // Mechanic version: a camera that hasn't been hooked up yet still shouts its model
        // number. Once it joins the host's Wi-Fi it goes quiet, so this can't see every camera.
        WatchEntry("Possible hidden camera (HDWiFiCam)", "HDWiFiCam", category = Category.HIDDEN_CAM, labelRes = R.string.wl_hidden_cam, labelArg = "HDWiFiCam"),
        WatchEntry("Possible hidden camera (Yoosee)", "GW_AP_", category = Category.HIDDEN_CAM, labelRes = R.string.wl_hidden_cam, labelArg = "Yoosee"),
        WatchEntry("Possible hidden camera (V380)", "re:MV[0-9]{6,}", category = Category.HIDDEN_CAM, labelRes = R.string.wl_hidden_cam, labelArg = "V380"),
    )

    /**
     * Network names that mean "not a patrol car", from the same patent's false-alarm list:
     * building systems, photo kiosks, fire/EMS rigs, and routers left on their brand name.
     * Only applied to "possible" matches.
     */
    private val ignoreWords = listOf("hvac", "photo", "fire", "cradle", "wireless", "fd-", "ems", "amb")

    @Volatile private var entries: List<WatchEntry> = defaults
    private var initialized = false

    /**
     * Loads the watchlist from the encrypted store. Older builds kept it in plain
     * SharedPreferences; that copy is moved into the store once and then deleted.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        Store.init(context)
        val stored = Store.watchlistRaw
        if (stored != null) {
            entries = parse(stored)
            addNewDefaults(Store.watchlistDefaultsVersion)
            return
        }
        val old = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        old.getString(LEGACY_KEY, null)?.let { raw ->
            entries = parse(raw)
            addNewDefaults(old.getInt(LEGACY_DEFAULTS_KEY, 1))
            save()
        }
        old.edit().clear().apply()
    }

    /**
     * Adds built-ins that are newer than this install's saved list, without bringing back
     * anything the person deliberately removed from older defaults.
     */
    private fun addNewDefaults(seenVersion: Int) {
        if (seenVersion >= DEFAULTS_VERSION) return
        val have = entries.map { it.pattern }.toSet()
        entries = entries + addedInV2.filter { it.pattern !in have }
        save()
    }

    private fun parse(raw: String): List<WatchEntry> =
        raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val pattern = parts[1]
            val category = Category.entries.firstOrNull { it.name == parts.getOrNull(3) }
                ?: defaults.firstOrNull { it.pattern == pattern }?.category
                ?: Category.CUSTOM
            // Unchanged built-ins get their translated name back.
            val builtIn = defaults.firstOrNull { it.pattern == pattern && it.label == parts[0] }
            WatchEntry(
                parts[0], pattern,
                if (parts.getOrNull(2) == "POSSIBLE") Confidence.POSSIBLE else Confidence.HIGH,
                category, builtIn?.labelRes ?: 0, builtIn?.labelArg,
            )
        }

    /** After "Erase everything": back to the built-in list, nothing written to disk. */
    fun resetInMemory() {
        entries = defaults
    }

    private val addedInV2: List<WatchEntry> get() = defaults.filter { it.category == Category.HIDDEN_CAM }

    fun all(): List<WatchEntry> = entries

    fun add(entry: WatchEntry) {
        entries = entries + entry
        save()
    }

    fun remove(entry: WatchEntry) {
        entries = entries - entry
        save()
    }

    fun restoreDefaults() {
        entries = defaults
        save()
    }

    /*
     * Deliberately NO learned or location-based lockout. US Patent 12,177,766 claims
     * automatically locking out Wi-Fi signals after repeat sightings in the same area,
     * so OwlEar leaves that out. "Possible" matches simply never alert.
     */
    fun match(address: String, name: String?): WatchEntry? {
        val hit = entries.firstOrNull { it.matches(address, name) } ?: return null
        if (hit.confidence == Confidence.HIGH) return hit
        val lower = name?.lowercase().orEmpty()
        if (ignoreWords.any { lower.contains(it) }) return null
        return hit
    }

    private fun save() {
        val text = entries.joinToString("\n") { e ->
            listOf(e.label, e.pattern, e.confidence.name, e.category.name).joinToString("\t") {
                it.replace('\t', ' ').replace('\n', ' ')
            }
        }
        Store.setWatchlist(text, DEFAULTS_VERSION)
    }
}
