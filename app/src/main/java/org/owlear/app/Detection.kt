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
    DRONE(R.string.cat_drone, false),
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

/** A drone or camera-glasses broadcast. detail = the drone's serial number when it sends one. */
data class Spotted(
    val label: String,
    val confidence: Confidence,
    val category: Category,
    val detail: String? = null,
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

    // ---- drones and camera glasses ----

    /*
     * Remote ID: the FAA requires most drones to broadcast an ID under the public ASTM F3411
     * standard, over Bluetooth (service data FFFA, app code 0x0D) and Wi-Fi beacons
     * (vendor element FA:0B:BC, type 0x0D). It's meant to be received by anyone.
     * Mechanic version: a license plate the drone is legally required to show.
     */
    private val REMOTE_ID = uuid16("FFFA")
    private const val REMOTE_ID_APP_CODE: Byte = 0x0D

    /*
     * Camera glasses: Bluetooth SIG company IDs. Luxottica (0x0D53) makes the Ray-Ban and
     * Oakley Meta frames, so that's high confidence. The Meta IDs (0x01AB, 0x058E) are also
     * used by Quest headsets, so those are only "possible" and never buzz the phone.
     */
    private const val LUXOTTICA_ID = 0x0D53
    private val META_IDS = intArrayOf(0x01AB, 0x058E)

    fun classifySpotted(result: ScanResult): Spotted? {
        val record = result.scanRecord ?: return null

        val rid = record.serviceData?.get(REMOTE_ID)
        if (rid != null && rid.isNotEmpty() && rid[0] == REMOTE_ID_APP_CODE) {
            // [app code][counter][message...]
            return Spotted(L10n.s(R.string.label_drone), Confidence.HIGH, Category.DRONE, remoteIdSerial(rid, 2))
        }
        if (record.getManufacturerSpecificData(LUXOTTICA_ID) != null) {
            return Spotted(L10n.s(R.string.label_glasses), Confidence.HIGH, Category.GLASSES)
        }
        if (META_IDS.any { record.getManufacturerSpecificData(it) != null }) {
            return Spotted(L10n.s(R.string.label_possible_glasses), Confidence.POSSIBLE, Category.POSSIBLE_GLASSES)
        }
        return null
    }

    /** Remote ID inside a Wi-Fi beacon's vendor element: [FA 0B BC][0D][counter][message pack]. */
    fun classifyWifiRemoteId(vendorElement: ByteArray): Spotted? {
        if (vendorElement.size < 5) return null
        if (vendorElement[0] != 0xFA.toByte() || vendorElement[1] != 0x0B.toByte() ||
            vendorElement[2] != 0xBC.toByte() || vendorElement[3] != REMOTE_ID_APP_CODE
        ) return null
        return Spotted(L10n.s(R.string.label_drone), Confidence.HIGH, Category.DRONE, remoteIdSerial(vendorElement, 5))
    }

    /** Pulls the serial out of a Basic ID message (type 0), alone or inside a message pack (type 0xF). */
    private fun remoteIdSerial(d: ByteArray, start: Int): String? {
        if (d.size <= start) return null
        return when ((d[start].toInt() shr 4) and 0x0F) {
            0 -> if (d.size >= start + 22) ascii(d, start + 2, 20) else null
            0xF -> {
                if (d.size < start + 3) return null
                val size = d[start + 1].toInt() and 0xFF
                val count = d[start + 2].toInt() and 0xFF
                if (size != 25) return null
                (0 until count).firstNotNullOfOrNull { i ->
                    val o = start + 3 + i * 25
                    if (o + 22 <= d.size && ((d[o].toInt() shr 4) and 0x0F) == 0) ascii(d, o + 2, 20) else null
                }
            }
            else -> null
        }
    }

    private fun ascii(d: ByteArray, from: Int, len: Int): String? {
        val sb = StringBuilder()
        for (i in from until minOf(from + len, d.size)) {
            val c = d[i].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        return sb.toString().trim().ifEmpty { null }
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
