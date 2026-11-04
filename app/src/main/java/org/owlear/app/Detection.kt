package org.owlear.app

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid

enum class Kind { TRACKER, WATCHLIST, SPOTTED, OTHER }

/**
 * Groups of equipment the person can switch between Alert, Show only and Off.
 * Trackers can only alert through the "following you" check; possible routers never alert.
 */
enum class Category(
    private val titleRes: Int,
    val isTracker: Boolean,
    val canAlert: Boolean = true,
    val defaultMode: Mode = Mode.ALERT,
) {
    APPLE(R.string.cat_apple, true),
    SAMSUNG(R.string.cat_samsung, true),
    TILE(R.string.cat_tile, true),
    CHIPOLO(R.string.cat_chipolo, true),
    GOOGLE(R.string.cat_google, true),
    FLOCK(R.string.cat_flock, false),
    AXON(R.string.cat_axon, false),
    FLEET(R.string.cat_fleet, false),
    POSSIBLE_FLEET(R.string.cat_possible_fleet, false, canAlert = false, defaultMode = Mode.SHOW),
    GLASSES(R.string.cat_glasses, false),
    POSSIBLE_GLASSES(R.string.cat_possible_glasses, false, canAlert = false, defaultMode = Mode.SHOW),
    HIDDEN_CAM(R.string.cat_hidden_cam, false),
    CUSTOM(R.string.cat_custom, false);

    /** Name shown on screen, in the phone's language. */
    val title: String get() = L10n.s(titleRes)

    /** Title without the bracketed detail, for short lists. */
    val shortTitle: String get() = title.substringBefore(" (")
}

/** What a single Bluetooth advertisement turned out to be. */
data class Classified(val label: String, val separated: Boolean, val category: Category)

/** A camera-glasses broadcast. */
data class Spotted(
    val label: String,
    val confidence: Confidence,
    val category: Category,
)

/**
 * Recognises item trackers from the shape of their Bluetooth advertisements.
 *
 * Mechanic version: every tracker brand stamps its broadcasts with its own
 * "part number" (a company ID or service ID). We read the stamp, not the contents.
 *
 * Signatures come from public research (e.g. the open-source AirGuard project
 * and the Google/Apple unwanted-tracking spec).
 */
object Detection {

    private fun uuid16(hex: String): ParcelUuid =
        ParcelUuid.fromString("0000$hex-0000-1000-8000-00805F9B34FB")

    private const val APPLE_COMPANY_ID = 0x004C
    private const val APPLE_FIND_MY_TYPE: Byte = 0x12      // "offline finding" broadcast
    private const val APPLE_SEPARATED_LEN: Byte = 0x19     // long payload = away from its owner

    private val SAMSUNG_SMARTTAG = uuid16("FD5A")
    private val TILE_A = uuid16("FEED")
    private val TILE_B = uuid16("FEEC")
    private val CHIPOLO = uuid16("FE33")
    private val GOOGLE_FMDN = uuid16("FEAA")              // Find My Device network frames 0x40 / 0x41

    fun classifyTracker(result: ScanResult): Classified? {
        val record = result.scanRecord ?: return null

        val apple = record.getManufacturerSpecificData(APPLE_COMPANY_ID)
        if (apple != null && apple.isNotEmpty() && apple[0] == APPLE_FIND_MY_TYPE) {
            val separated = apple.size > 1 && apple[1] == APPLE_SEPARATED_LEN
            return Classified(L10n.s(R.string.label_apple), separated, Category.APPLE)
        }

        val serviceData: Map<ParcelUuid, ByteArray> = record.serviceData ?: emptyMap()
        val serviceUuids: List<ParcelUuid> = record.serviceUuids ?: emptyList()
        fun has(u: ParcelUuid) = serviceData.containsKey(u) || serviceUuids.contains(u)

        // These brands don't say whether they're near their owner, so we treat them as "could be".
        if (has(SAMSUNG_SMARTTAG)) return Classified(L10n.s(R.string.label_samsung), true, Category.SAMSUNG)
        if (has(TILE_A) || has(TILE_B)) return Classified(L10n.s(R.string.label_tile), true, Category.TILE)
        if (has(CHIPOLO)) return Classified(L10n.s(R.string.label_chipolo), true, Category.CHIPOLO)

        val fmdn = serviceData[GOOGLE_FMDN]
        if (fmdn != null && fmdn.isNotEmpty()) {
            val frame = fmdn[0]
            if (frame == 0x40.toByte() || frame == 0x41.toByte()) {
                // 0x41 = the tag has flagged itself as separated from its owner.
                return Classified(L10n.s(R.string.label_google), frame == 0x41.toByte(), Category.GOOGLE)
            }
        }
        return null
    }

    // ---- camera glasses ----

    /*
     * Camera glasses: Bluetooth SIG company IDs. Luxottica (0x0D53) makes the Ray-Ban and
     * Oakley Meta frames, so that's high confidence. The Meta IDs (0x01AB, 0x058E) are also
     * used by Quest headsets, so those are only "possible" and never buzz the phone.
     */
    private const val LUXOTTICA_ID = 0x0D53
    private val META_IDS = intArrayOf(0x01AB, 0x058E)

    fun classifySpotted(result: ScanResult): Spotted? {
        val record = result.scanRecord ?: return null

        if (record.getManufacturerSpecificData(LUXOTTICA_ID) != null) {
            return Spotted(L10n.s(R.string.label_glasses), Confidence.HIGH, Category.GLASSES)
        }
        if (META_IDS.any { record.getManufacturerSpecificData(it) != null }) {
            return Spotted(L10n.s(R.string.label_possible_glasses), Confidence.POSSIBLE, Category.POSSIBLE_GLASSES)
        }
        return null
    }

    // ---- naming everyday devices (for the "Everything else" list) ----

    /** A few common Bluetooth SIG company IDs, so the list can say who made a device. */
    private val BRANDS = mapOf(
        0x004C to "Apple",
        0x0006 to "Microsoft",
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x0087 to "Garmin",
        0x009E to "Bose",
        0x012D to "Sony",
        0x038F to "Xiaomi",
    )

    fun brand(companyId: Int): String? = BRANDS[companyId]

    /**
     * Phones and laptops hide their real Wi-Fi address behind a made-up one. The giveaway is
     * the "locally administered" bit (2nd hex digit is 2, 6, A or E).
     * Mechanic version: a temporary paper plate instead of the real one, so the maker ID is
     * meaningless.
     */
    fun isPrivateAddress(address: String): Boolean {
        val c = address.getOrNull(1)?.uppercaseChar() ?: return false
        return c == '2' || c == '6' || c == 'A' || c == 'E'
    }

    /** Best plain-language guess at what an unlabelled device is. */
    fun describeOther(s: Sighting): String {
        val name = s.heardName?.takeIf { it.isNotBlank() }?.let { L10n.ltr(it) }
        if (s.source == "Wi-Fi") {
            return if (name != null) L10n.s(R.string.wifi_named, name) else L10n.s(R.string.wifi_hidden)
        }
        val maker = brand(s.companyId)
        return when {
            name != null && maker != null -> L10n.s(R.string.device_with_maker, name, maker)
            name != null -> name
            maker == "Apple" -> L10n.s(R.string.apple_device)
            maker != null -> L10n.s(R.string.maker_device, maker)
            else -> L10n.s(R.string.unnamed_bt)
        }
    }

    fun proximity(rssi: Int): String = L10n.s(
        when {
            rssi >= -60 -> R.string.very_close
            rssi >= -75 -> R.string.nearby
            else -> R.string.far
        }
    )
}
