package org.owlear.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** One plain screen built in code: no layout files, no libraries. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var followingBox: LinearLayout
    private lateinit var trackersBox: LinearLayout
    private lateinit var hitsBox: LinearLayout
    private lateinit var spottedBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var soundRow: TextView
    private lateinit var unitsRow: TextView
    private lateinit var cellText: TextView
    private lateinit var nearbyText: TextView
    private lateinit var watchlistBox: LinearLayout
    private lateinit var togglesBox: LinearLayout
    private lateinit var placesBox: LinearLayout
    private lateinit var privacyBox: LinearLayout
    private lateinit var checkupBox: LinearLayout
    private lateinit var tagsBox: LinearLayout
    private var checkupResult: Checkup.Result? = null
    private var checkupRunning = false
    private var shownPlaceId: String? = "unset"
    private lateinit var nearbyBox: LinearLayout
    private lateinit var radar: RadarView
    private var findScreen: FindScreen? = null
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2_000L)
        }
    }

    // ---- colors ----
    private val bg = Color.parseColor("#0B0F0D")
    private val card = Color.parseColor("#151B18")
    private val green = Color.parseColor("#3DDC84")
    private val red = Color.parseColor("#FF5A5F")
    private val amber = Color.parseColor("#FFC24B")
    private val fg = Color.parseColor("#E8EEEA")
    private val dim = Color.parseColor("#8A968F")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L10n.init(this)
        Distance.load(this)
        Watchlist.init(this)
        applySecureScreen()
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        renderWatchlist()
        renderToggles()
        renderPlaces()
        renderPrivacy()
        renderCheckup()
        renderTags()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    override fun onDestroy() {
        findScreen?.dismiss()
        findScreen = null
        super.onDestroy()
    }

    // ---- Find it (hot/cold) ----

    private fun openFind(s: Sighting) {
        if (!Repo.running) {
            Repo.status = getString(R.string.status_start_first)
            refresh()
            Toast.makeText(this, R.string.status_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        findScreen?.dismiss()
        findScreen = FindScreen(this, s, Glyph.colorFor(s)).also { it.show() }
    }

    // ---- start / stop ----

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) list.add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun canScan(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION
        return checkSelfPermission(needed) == PackageManager.PERMISSION_GRANTED
    }

    private fun onToggle() {
        if (Repo.running) {
            stopService(Intent(this, ScanService::class.java))
            return
        }
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScanning() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (canScan()) startScanning()
        else Repo.status = getString(R.string.status_need_bluetooth)
        refresh()
    }

    private fun startScanning() {
        Repo.status = getString(R.string.status_starting)
        try {
            startForegroundService(Intent(this, ScanService::class.java))
        } catch (e: Exception) {
            Repo.status = getString(R.string.status_couldnt_start, e.message.orEmpty())
        }
        // First time ever: show what the icons mean, once.
        if (!Store.legendSeen) {
            Store.setLegendSeen()
            showLegend()
        }
    }

    // ---- UI building ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            // Lay text out in the app language's direction, even when it starts with a
            // Latin brand name inside an Arabic, Persian or Hebrew sentence.
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    /** One foldable card: its title row, the part that folds away, and the count shown when shut. */
    private class Fold(val header: TextView, val body: View, val title: String, val startOpen: Boolean) {
        var count = 0
    }

    private val folds = HashMap<LinearLayout, Fold>()

    /**
     * A card that folds open and shut when its title is tapped (▾ open, ▸ shut), so the screen
     * is a short list of headings instead of one long page. Open/shut is remembered while the
     * app runs. [startOpen] is for the Nearby list and the "following you" warning.
     * Mechanic version: a parts drawer. Labelled on the front, open only the one you need.
     */
    private fun section(title: String, hint: String?, startOpen: Boolean = false): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (hint != null) addView(label(hint, 12f, dim).apply { setPadding(0, dp(2), 0, dp(6)) })
            addView(box)
        }
        val header = label("", 16f, fg, bold = true).apply { setPadding(0, dp(2), 0, dp(2)) }
        val fold = Fold(header, body, title, startOpen)
        folds[box] = fold
        header.setOnClickListener {
            opened[title] = !(opened[title] ?: startOpen)
            drawFold(fold)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(header)
            addView(body)
        }, spaced())
        drawFold(fold)
        return box
    }

    private fun drawFold(f: Fold) {
        val open = opened.getOrPut(f.title) { f.startOpen }
        f.body.visibility = if (open) View.VISIBLE else View.GONE
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        f.header.text = (if (open) "▾  " else if (rtl) "◂  " else "▸  ") + f.title + (if (f.count > 0) "  (${f.count})" else "")
    }

    /** Shows "(3)" after a section's title, so a folded section still says something's in it. */
    private fun setCount(box: LinearLayout, n: Int) {
        val f = folds[box] ?: return
        if (f.count == n) return
        // A new "following you" warning always unfolds its card, even if it was folded shut.
        if (box === followingBox && n > f.count) opened[f.title] = true
        f.count = n
        drawFold(f)
    }

    private fun spaced() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(12) }

    private lateinit var root: LinearLayout

    private fun buildUi(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        root.addView(label(getString(R.string.app_name), 28f, fg, bold = true))
        root.addView(label(getString(R.string.app_tagline), 13f, dim))

        status = label("", 13f, green).apply { setPadding(0, dp(10), 0, 0) }
        root.addView(status)

        toggle = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.BLACK)
            background = rounded(green)
            setOnClickListener { onToggle() }
        }
        root.addView(toggle, spaced().apply { height = dp(52) })

        nearbyBox = section(getString(R.string.section_nearby), getString(R.string.hint_nearby), startOpen = true)
        val nearbyCard = nearbyBox.parent as LinearLayout
        // The rings sit above the list: closer to the centre = closer to you.
        radar = RadarView(this).apply { onPick = { showDetails(it) } }
        nearbyCard.addView(radar, nearbyCard.indexOfChild(nearbyBox), spaced().apply { topMargin = dp(4) })
        nearbyCard.addView(label(getString(R.string.legend_link), 13f, green).apply {
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { showLegend() }
        })

        recentBox = section(getString(R.string.section_recent), getString(R.string.hint_recent))

        followingBox = section(
            getString(R.string.section_following),
            getString(R.string.hint_following, (Repo.FOLLOW_MIN_MS / 60_000).toInt(), Repo.FOLLOW_MIN_PLACES),
            startOpen = true,
        )
        hitsBox = section(getString(R.string.section_hits), getString(R.string.hint_hits))
        spottedBox = section(getString(R.string.section_spotted), getString(R.string.hint_spotted))
        val cellBox = section(getString(R.string.section_cell), getString(R.string.hint_cell))
        cellText = label(getString(R.string.cell_waiting), 13f, dim)
        cellBox.addView(cellText)
        checkupBox = section(getString(R.string.section_checkup), getString(R.string.hint_checkup))
        trackersBox = section(getString(R.string.section_trackers), getString(R.string.hint_trackers))
        val nearbyBox = section(getString(R.string.section_others), getString(R.string.hint_others))
        nearbyText = label("", 13f, dim).apply { setOnClickListener { showOthers() } }
        nearbyBox.addView(nearbyText)

        val settingsBox = section(getString(R.string.section_settings), null)
        soundRow = label("", 14f, fg)
        settingsBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(label(getString(R.string.setting_sound), 12f, dim))
            addView(soundRow)
            setOnClickListener { pickSound() }
        })
        updateSoundRow()
        unitsRow = label("", 14f, fg)
        settingsBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(label(getString(R.string.setting_units), 12f, dim))
            addView(unitsRow)
            setOnClickListener {
                Distance.setFeet(this@MainActivity, !Distance.usesFeet)
                updateUnitsRow()
                refresh()
            }
        })
        updateUnitsRow()
        // Android 13+ lets each app have its own language, picked in the phone's settings.
        if (Build.VERSION.SDK_INT >= 33) {
            settingsBox.addView(row(
                getString(R.string.setting_language) + "  " + getString(R.string.chevron),
                getString(R.string.setting_language_hint),
                fg,
            ) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                } catch (e: Exception) {
                    // settings screen missing on this phone
                }
            })
        }

        // OwlEar can't reach the internet, so updates are checked in the browser instead.
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            ""
        }
        settingsBox.addView(row(
            getString(R.string.setting_updates) + "  " + getString(R.string.chevron),
            getString(R.string.setting_updates_hint, L10n.ltr(version)),
            fg,
        ) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
            } catch (e: Exception) {
                Toast.makeText(this, RELEASES_URL, Toast.LENGTH_LONG).show()
            }
        })

        watchlistBox = section(getString(R.string.section_watchlist), getString(R.string.hint_watchlist))
        tagsBox = section(getString(R.string.section_tags), getString(R.string.hint_tags))

        togglesBox = section(getString(R.string.section_toggles), getString(R.string.hint_toggles))
        placesBox = section(getString(R.string.section_places), getString(R.string.hint_places, Store.REGULAR_DAYS))
        privacyBox = section(getString(R.string.section_privacy), getString(R.string.hint_privacy))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
        // Android 15 draws apps edge-to-edge; keep content clear of the status/nav bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        return scroll
    }

    private fun row(title: String, detail: String, color: Int, onTap: (() -> Unit)? = null): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(label(title, 14f, color, bold = true))
            addView(label(detail, 12f, dim))
            if (onTap != null) setOnClickListener { onTap() }
        }

    private fun empty(text: String) = label(text, 13f, dim).apply { setPadding(0, dp(4), 0, dp(4)) }

    private fun ago(ms: Long): String {
        // Signals expire in seconds now, so "just now" only covers the last few seconds.
        val sec = ms / 1_000L
        return when {
            sec < 5 -> getString(R.string.just_now)
            sec < 60 -> getString(R.string.seconds_ago, sec.toInt())
            else -> getString(R.string.minutes_ago, (sec / 60).toInt())
        }
    }

    // ---- refresh ----

    private fun refresh() {
        status.text = Repo.status.ifEmpty { getString(R.string.status_stopped) }
        toggle.text = getString(if (Repo.running) R.string.stop_listening else R.string.start_listening)
        toggle.background = rounded(if (Repo.running) amber else green)

        val now = System.currentTimeMillis()
        val all = Repo.snapshot().filter { !Repo.isHidden(it) }

        val here = Repo.location?.let { Store.placeAt(it) }
        if (here?.id != shownPlaceId) renderPlaces()

        // Only what's being heard right now is Nearby; the rest drops to "Recently out of range".
        // Both use the same test, so nothing lands in Recently out of range that Nearby never showed.
        val live = all.filter { belongsInNearby(it) && Repo.isLive(it, now) }
        renderNearby(live)

        recentBox.removeAllViews()
        val recent = all.filter {
            belongsInNearby(it) && !Repo.isLive(it, now) && now - it.lastSeen < 60 * 60_000L
        }.sortedByDescending { it.lastSeen }
        setCount(recentBox, recent.size)
        if (recent.isEmpty()) recentBox.addView(empty(getString(R.string.empty_recent)))
        for (s in recent.take(30)) {
            val possible = s.confidence == Confidence.POSSIBLE
            recentBox.addView(row(
                s.label,
                L10n.dots(
                    getString(R.string.last_heard, ago(now - s.lastSeen)), L10n.ltr(s.source),
                    getString(R.string.was_proximity, approx(s)),
                ),
                if (s.kind == Kind.TRACKER || possible) dim else amber,
            ) { showDetails(s) })
        }
        // Following
        followingBox.removeAllViews()
        val following = all.filter {
            it.kind == Kind.TRACKER && it.separated && Repo.isFollowing(it) && !Repo.isIgnored(it.address)
        }
        setCount(followingBox, following.size)
        if (following.isEmpty()) followingBox.addView(empty(getString(R.string.empty_following)))
        for (s in following.sortedByDescending { it.lastSeen }) {
            followingBox.addView(row(
                s.label,
                L10n.dots(
                    getString(R.string.with_you, s.minutesTracked.toInt()),
                    L10n.p(R.plurals.places_count, s.places.size),
                    approx(s),
                    getString(R.string.last_heard, ago(now - s.lastSeen)),
                ),
                red,
            ) { showDetails(s) })
        }

        // Watchlist hits
        hitsBox.removeAllViews()
        val hits = all.filter { it.kind == Kind.WATCHLIST && now - it.lastSeen < 60 * 60_000L }
            .sortedByDescending { it.lastSeen }
        setCount(hitsBox, hits.size)
        if (hits.isEmpty()) hitsBox.addView(empty(getString(R.string.empty_none_yet)))
        for (s in hits.take(50)) {
            val possible = s.confidence == Confidence.POSSIBLE
            val quiet = s.category?.let { Store.mode(it) != Mode.ALERT } == true || s.mutedAt != null
            hitsBox.addView(row(
                s.label,
                L10n.dots(
                    if (possible) getString(R.string.maker_only) else null,
                    strength(s), L10n.ltr(s.source),
                    s.heardName?.let { getString(R.string.quoted, L10n.ltr(it)) },
                    ago(now - s.lastSeen),
                    s.regularAt?.let { getString(R.string.regular_at, L10n.ltr(it)) },
                    s.mutedAt?.let { getString(R.string.muted_at, L10n.ltr(it)) },
                ),
                if (possible || quiet) dim else amber,
            ) { showDetails(s) })
        }

        // Camera glasses
        spottedBox.removeAllViews()
        val spotted = all.filter { it.kind == Kind.SPOTTED && now - it.lastSeen < 30 * 60_000L }
            .sortedByDescending { it.lastSeen }
        setCount(spottedBox, spotted.size)
        if (spotted.isEmpty()) spottedBox.addView(empty(getString(R.string.empty_none_yet)))
        for (s in spotted.take(30)) {
            val possible = s.confidence == Confidence.POSSIBLE
            val quiet = s.category?.let { Store.mode(it) != Mode.ALERT } == true || s.mutedAt != null
            spottedBox.addView(row(
                s.label,
                L10n.dots(
                    strength(s), L10n.ltr(s.source),
                    ago(now - s.lastSeen),
                    s.mutedAt?.let { getString(R.string.muted_at, L10n.ltr(it)) },
                ),
                if (possible || quiet) dim else amber,
            ) { showDetails(s) })
        }

        // Cell network
        val cell = Repo.cell
        if (cell != null && Repo.running) {
            cellText.text = cell.summary
            cellText.setTextColor(when (cell.level) {
                CellWatch.Level.OK -> green
                CellWatch.Level.SUSPICIOUS -> red
                CellWatch.Level.UNAVAILABLE -> dim
            })
        }

        // Trackers nearby
        trackersBox.removeAllViews()
        val trackers = all.filter {
            it.kind == Kind.TRACKER && now - it.lastSeen < 15 * 60_000L && it !in following
        }.sortedWith(compareBy<Sighting> { it.tag == null }.thenByDescending { it.rssi })  // tagged first, then nearest
        setCount(trackersBox, trackers.size)
        if (trackers.isEmpty()) trackersBox.addView(empty(getString(R.string.empty_trackers)))
        for (s in trackers.take(30)) {
            val away = getString(if (s.separated) R.string.tracker_away else R.string.tracker_near)
            trackersBox.addView(row(
                s.label,
                L10n.dots(
                    away, approx(s),
                    getString(R.string.tracker_heard, s.minutesTracked.toInt(), L10n.p(R.plurals.places_count, s.places.size)),
                ),
                fg,
            ) { showDetails(s) })
        }

        val others = all.count { it.kind == Kind.OTHER && now - it.lastSeen < 2 * 60_000L }
        nearbyText.text = L10n.p(R.plurals.others_count, others) + " " + getString(R.string.tap_to_view)
    }

    // ---- settings ----

    private fun updateUnitsRow() {
        unitsRow.text = getString(if (Distance.usesFeet) R.string.units_feet else R.string.units_meters) +
            "  " + getString(R.string.units_switch)
    }

    private fun updateSoundRow() {
        soundRow.text = Sounds.choices[Sounds.selected(this)].label + "  " + getString(R.string.chevron)
    }

    private fun pickSound() {
        var picked = Sounds.selected(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_sound)
            .setSingleChoiceItems(Sounds.choices.map { it.label }.toTypedArray(), picked) { _, which ->
                picked = which
                Sounds.play(this, which) // preview
            }
            .setPositiveButton(R.string.save) { _, _ ->
                Sounds.select(this, picked)
                updateSoundRow()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Nearby list ----

    /**
     * Everything flagged that's being heard right now, nearest first. Your watchlist hits and
     * tagged devices are pinned on top; the rest sit in foldable groups, one per icon.
     * Trackers still with their owner are left out (passers-by); they're under "Trackers nearby".
     * Mechanic version: the parts tray, closest part first, with the ones you marked up front.
     */
    private fun renderNearby(live: List<Sighting>) {
        nearbyBox.removeAllViews()
        val shown = live.sortedBy { Distance.meters(it.rssi, it.source) }
        radar.items = shown.take(RADAR_MAX)
        radar.invalidate()
        setCount(nearbyBox, shown.size)
        if (shown.isEmpty()) {
            nearbyBox.addView(empty(getString(R.string.empty_nearby)))
            return
        }
        val pinned = shown.filter { it.kind == Kind.WATCHLIST || it.tag != null }
        if (pinned.isNotEmpty()) group(KEY_PINNED, getString(R.string.group_pinned), null, pinned)
        val rest = shown.filter { it !in pinned }.groupBy { Glyph.of(it) }
        for (g in Glyph.entries) rest[g]?.let { group(g.name, g.title, g, it) }
    }

    /**
     * Whether a signal is the kind Nearby shows (and so Recently out of range too). Left out:
     * ordinary "everything else" devices, ignored ones, and trackers still with their owner
     * (passers-by, which rotate their address every few minutes and would pile up).
     */
    private fun belongsInNearby(s: Sighting): Boolean =
        (s.kind != Kind.OTHER || s.tag != null) &&
            !(s.kind == Kind.TRACKER && !s.separated && s.tag == null) &&
            !Repo.isIgnored(s.address)

    /** One foldable group inside Nearby: tap its title to fold it. Remembered while the app runs. */
    private fun group(key: String, title: String, glyph: Glyph?, items: List<Sighting>) {
        val open = groupOpen[key] ?: true
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        nearbyBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            addView(label(if (open) "▾" else if (rtl) "◂" else "▸", 13f, dim).apply { setPadding(0, 0, dp(8), 0) })
            if (glyph != null) addView(GlyphView(this@MainActivity, glyph, dim), LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(label("$title  (${items.size})", 13f, dim, bold = true).apply {
                setPadding(if (glyph != null) dp(8) else 0, 0, 0, 0)
            })
            setOnClickListener {
                groupOpen[key] = !open
                refresh()
            }
        })
        if (!open) return
        for (s in items.take(GROUP_MAX)) nearbyBox.addView(nearbyRow(s))
        if (items.size > GROUP_MAX) nearbyBox.addView(empty(getString(R.string.nearby_more, items.size - GROUP_MAX)))
    }

    /** Icon, name, then rough distance ("~10 m") and Bluetooth or Wi-Fi. Tap for details. */
    private fun nearbyRow(s: Sighting): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        val color = Glyph.colorFor(s)
        addView(GlyphView(this@MainActivity, Glyph.of(s), color), LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(label(s.label, 14f, color, bold = true))
            addView(label(L10n.dots(
                approx(s),
                L10n.ltr(if (s.source == "BT") "Bluetooth" else s.source),
                if (s.kind == Kind.TRACKER && s.separated && Repo.isFollowing(s)) getString(R.string.section_following) else null,
            ), 12f, dim))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { showDetails(s) }
    }

    private fun showLegend() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        for (g in Glyph.entries) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(GlyphView(this@MainActivity, g, fg), LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(label(g.title, 14f, fg).apply { setPadding(dp(10), 0, 0, 0) })
            })
        }
        for (id in intArrayOf(R.string.legend_colors, R.string.legend_radar, R.string.legend_distance)) {
            list.addView(label(getString(id), 13f, dim).apply { setPadding(0, dp(8), 0, 0) })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.legend_title)
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---- details card ----

    /** Everything OwlEar knows about one signal. Refreshes every second while it's open. */
    private fun showDetails(s: Sighting) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val title = if (s.kind == Kind.OTHER && s.tag == null) Detection.describeOther(s) else s.label
        // "Find it" sits above the details, so the way to walk to it is one tap away.
        val findButton = Button(this).apply {
            text = getString(R.string.find_it)
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.BLACK)
            background = rounded(green)
        }
        val tagButton = Button(this).apply {
            text = getString(if (s.tag != null) R.string.tag_button_edit else R.string.tag_button)
            isAllCaps = false
            setTextColor(fg)
            background = rounded(Color.parseColor("#24392E"))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(findButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                setMargins(dp(20), dp(8), dp(20), 0)
            })
            addView(tagButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                setMargins(dp(20), dp(8), dp(20), 0)
            })
            addView(list)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.close, null)
        when {
            s.kind == Kind.TRACKER -> builder.setNeutralButton(R.string.ignore) { _, _ -> confirmIgnore(s) }
            s.kind == Kind.OTHER -> builder.setNeutralButton(R.string.add_to_watchlist) { _, _ -> offerWatch(s) }
        }
        val dialog = builder.show()
        findButton.setOnClickListener {
            dialog.dismiss()
            openFind(s)
        }
        tagButton.setOnClickListener {
            dialog.dismiss()
            editTag(s)
        }

        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                fillDetails(list, s)
                handler.postDelayed(this, 1_000L)
            }
        }
        tick.run()
    }

    private fun fillDetails(list: LinearLayout, s: Sighting) {
        val now = System.currentTimeMillis()
        list.removeAllViews()
        fun field(name: Int, value: String?) {
            if (value.isNullOrEmpty()) return
            list.addView(label(getString(name), 11f, dim).apply { setPadding(0, dp(8), 0, 0) })
            list.addView(label(value, 14f, fg))
        }

        // Icon + group, so the card matches the row that was tapped.
        // Ordinary devices from "Everything else" get no icon: the ★ means hidden cameras and
        // your own entries, and an ordinary laptop shouldn't look like one.
        if (s.kind != Kind.OTHER || s.tag != null) {
            val glyph = Glyph.of(s)
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(GlyphView(this@MainActivity, glyph, Glyph.colorFor(s)), LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(label(s.category?.title ?: glyph.title, 13f, dim).apply { setPadding(dp(10), 0, 0, 0) })
            })
        }

        s.tag?.let { t ->
            field(R.string.detail_tag, L10n.dots(t.name, getString(if (t.silent) R.string.tag_alerts_silenced else R.string.tag_alerts_on)))
        }
        if (s.kind == Kind.TRACKER) {
            val status = mutableListOf(getString(if (s.separated) R.string.tracker_away else R.string.tracker_near))
            if (s.separated && Repo.isFollowing(s)) status.add(0, getString(R.string.section_following))
            field(R.string.detail_status, L10n.dots(*status.toTypedArray(), getString(R.string.with_you, s.minutesTracked.toInt())))
        }
        if (s.confidence == Confidence.POSSIBLE) field(R.string.detail_status, getString(R.string.maker_only))

        // The sentence already says "about", so drop the "~" from the distance here.
        field(R.string.detail_signal,
            getString(R.string.detail_signal_value, s.rssi, Distance.label(Distance.meters(s.rssi, s.source)).replace("~", "")))
        list.addView(SparkView(this, Repo.historyOf(s), green, dim), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(4) })
        list.addView(label(getString(R.string.detail_history), 10f, dim))

        field(R.string.detail_name, s.heardName?.takeIf { it.isNotBlank() }?.let { getString(R.string.quoted, L10n.ltr(it)) })
        field(R.string.detail_address,
            if (s.kind != Kind.TRACKER && Detection.isPrivateAddress(s.address)) getString(R.string.private_address)
            else L10n.ltr(s.address))
        field(R.string.detail_maker, Detection.brand(s.companyId))
        field(R.string.detail_source, L10n.ltr(if (s.source == "BT") "Bluetooth" else s.source))
        field(R.string.detail_first, ago(now - s.firstSeen))
        field(R.string.detail_last, ago(now - s.lastSeen))
        field(R.string.detail_times, s.count.toString())
        if (s.kind != Kind.OTHER) field(R.string.detail_places, L10n.p(R.plurals.places_count, s.places.size))
        field(R.string.detail_status, s.regularAt?.let { getString(R.string.regular_at, L10n.ltr(it)) })
        field(R.string.detail_status, s.mutedAt?.let { getString(R.string.muted_at, L10n.ltr(it)) })
    }

    // ---- "Everything else" list ----

    private fun showOthers() {
        val now = System.currentTimeMillis()
        val others = Repo.snapshot()
            .filter { it.kind == Kind.OTHER && now - it.lastSeen < 2 * 60_000L }
            .sortedByDescending { it.rssi }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        if (others.isEmpty()) list.addView(empty(getString(R.string.empty_others)))
        for (s in others.take(150)) {
            val isPrivate = Detection.isPrivateAddress(s.address)
            val addr = if (isPrivate) getString(R.string.private_address) else L10n.ltr(s.address)
            list.addView(row(
                Detection.describeOther(s),
                L10n.dots(L10n.ltr(s.source), strength(s), addr, ago(now - s.lastSeen)),
                fg,
            ) { showDetails(s) })
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.others_title, others.size))
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** Lets you turn something from the list into a watchlist entry. */
    private fun offerWatch(s: Sighting) {
        val name = s.heardName?.takeIf { it.isNotBlank() }
        val makerId = if (!Detection.isPrivateAddress(s.address)) s.address.take(8) else null
        val options = ArrayList<Pair<String, WatchEntry>>()
        if (name != null) options.add(getString(R.string.offer_watch_name, L10n.ltr(name)) to WatchEntry(name, name))
        if (makerId != null) {
            options.add(
                getString(R.string.offer_watch_maker, L10n.ltr(makerId)) to
                    WatchEntry(getString(R.string.maker_label, makerId), makerId)
            )
        }
        if (options.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(Detection.describeOther(s))
                .setMessage(R.string.offer_nothing_stable)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_to_watchlist_title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                Watchlist.add(options[which].second)
                renderWatchlist()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmIgnore(s: Sighting) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ignore_title)
            .setMessage(R.string.ignore_message)
            .setPositiveButton(R.string.ignore) { _, _ ->
                Repo.ignore(s.address)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- watchlist editor ----

    private fun renderWatchlist() {
        watchlistBox.removeAllViews()
        for (e in Watchlist.all()) {
            val how = L10n.dots(
                e.describe(),
                if (e.confidence == Confidence.POSSIBLE) getString(R.string.watch_possible) else null,
                getString(R.string.tap_to_remove),
            )
            watchlistBox.addView(row(e.displayLabel, how, fg) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.remove_title, L10n.ltr(e.displayLabel)))
                    .setPositiveButton(R.string.remove) { _, _ ->
                        Watchlist.remove(e)
                        renderWatchlist()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            })
        }

        val nameInput = input(getString(R.string.hint_label_input))
        val patternInput = input(getString(R.string.hint_pattern_input)).apply {
            // Maker IDs and patterns are written left to right in every language.
            textDirection = View.TEXT_DIRECTION_LTR
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val add = Button(this).apply {
            text = getString(R.string.add_to_watchlist)
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = rounded(green)
            setOnClickListener {
                val l = nameInput.text.toString().trim()
                val p = patternInput.text.toString().trim()
                if (p.isEmpty()) {
                    patternInput.error = getString(R.string.required)
                } else {
                    Watchlist.add(WatchEntry(if (l.isEmpty()) p else l, p))
                    renderWatchlist()
                }
            }
        }
        watchlistBox.addView(row(
            getString(R.string.restore_defaults),
            getString(R.string.restore_defaults_detail),
            green,
        ) {
            Watchlist.restoreDefaults()
            renderWatchlist()
        })
        watchlistBox.addView(nameInput, spaced())
        watchlistBox.addView(patternInput, spaced())
        watchlistBox.addView(add, spaced().apply { height = dp(46) })
    }

    // ---- category toggles ----

    // ---- phone checkup ----

    /**
     * The checkup card. Off by default. When on, it shows one switch per check, a Run button,
     * and the last results (kept only while this screen is open). Tapping an app opens its
     * Android "App info" page, where the person can look closer, take away access, or uninstall
     * when they're ready. OwlEar never removes anything itself, because a phone that suddenly
     * goes quiet can tip off the person watching. The safety note says this before any results.
     */
    private fun renderCheckup() {
        checkupBox.removeAllViews()
        val on = Store.checkupOn
        checkupBox.addView(row(
            getString(R.string.toggle_row, getString(R.string.checkup_switch), getString(if (on) R.string.state_on else R.string.state_off)),
            getString(if (on) R.string.checkup_on_note else R.string.checkup_off_note),
            if (on) green else dim,
        ) {
            Store.setCheckupOn(!on)
            if (on) checkupResult = null
            renderCheckup()
        })
        if (!on) return

        checkupBox.addView(label(getString(R.string.checkup_parts), 12f, dim).apply { setPadding(0, dp(8), 0, dp(2)) })
        for (c in Checkup.Check.entries) {
            val cOn = Store.checkOn(c)
            val hint = if (c == Checkup.Check.KNOWN_LIST) getString(c.hintRes, Checkup.listSize(this)) else getString(c.hintRes)
            checkupBox.addView(row(
                getString(R.string.toggle_row, getString(c.titleRes), getString(if (cOn) R.string.state_on else R.string.state_off)),
                hint,
                if (cOn) fg else dim,
            ) {
                Store.setCheckOn(c, !cOn)
                renderCheckup()
            })
        }

        checkupBox.addView(Button(this).apply {
            text = getString(if (checkupRunning) R.string.checkup_running else R.string.checkup_run)
            isAllCaps = false
            isEnabled = !checkupRunning
            setTextColor(Color.BLACK)
            background = rounded(green)
            setOnClickListener { runCheckup() }
        }, spaced().apply { height = dp(48) })

        checkupBox.addView(label(getString(R.string.checkup_safety), 12f, amber).apply { setPadding(0, dp(10), 0, dp(4)) })

        val r = checkupResult
        if (r != null) {
            checkupBox.addView(label(
                getString(R.string.checkup_summary, r.appsLooked, r.apps.size + r.settings.size), 12f, dim,
            ).apply { setPadding(0, dp(6), 0, dp(2)) })
            if (r.apps.isEmpty() && r.settings.isEmpty()) checkupBox.addView(empty(getString(R.string.checkup_clean)))
            for (f in r.apps) {
                val color = when (f.level) {
                    Checkup.Level.KNOWN -> red
                    Checkup.Level.CONCERN -> amber
                    Checkup.Level.NOTE -> fg
                }
                checkupBox.addView(row(
                    f.label + "  " + getString(R.string.chevron),
                    L10n.dots(*f.reasons.toTypedArray()),
                    color,
                ) { openAppInfo(f.packageName) })
            }
            for (sText in r.settings) checkupBox.addView(row(getString(R.string.checkup_phone_setting), sText, amber))
        }

        checkupBox.addView(label(getString(R.string.checkup_limits), 12f, dim).apply { setPadding(0, dp(10), 0, dp(2)) })
        checkupBox.addView(row(getString(R.string.checkup_help) + "  " + getString(R.string.chevron), getString(R.string.checkup_help_hint), fg) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HELP_URL)))
            } catch (e: Exception) {
                Toast.makeText(this, HELP_URL, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun runCheckup() {
        if (checkupRunning) return
        checkupRunning = true
        renderCheckup()
        val checks = Checkup.Check.entries.filter { Store.checkOn(it) }.toSet()
        val app = applicationContext
        Thread {
            val result = try {
                Checkup.run(app, checks)
            } catch (e: Exception) {
                null
            }
            handler.post {
                checkupRunning = false
                checkupResult = result
                if (result == null) Toast.makeText(this, R.string.checkup_failed, Toast.LENGTH_SHORT).show()
                if (!isDestroyed) renderCheckup()
            }
        }.start()
    }

    private fun openAppInfo(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
        } catch (e: Exception) {
            Toast.makeText(this, pkg, Toast.LENGTH_LONG).show()
        }
    }

    // ---- device tags ----

    /**
     * Name a device so it's easy to spot later, from its details card. The tag is saved as a
     * one-way fingerprint of the address (never the address), in the encrypted file.
     * Tagged devices still alert as usual unless "Silence its alerts" is ticked.
     * Mechanic version: a paint pen mark on a part you've already checked.
     */
    private fun editTag(s: Sighting) {
        val fp = Repo.fingerprintOf(s)
        if (fp == null) {
            Toast.makeText(this, R.string.tag_failed, Toast.LENGTH_LONG).show()
            return
        }
        val current = Store.tagFor(fp)
        val what = if (s.kind == Kind.OTHER) Detection.describeOther(s) else s.baseLabel
        openTagEditor(current?.name.orEmpty(), current?.silent ?: false, current != null,
            rotating = s.source == "BT" && Detection.isPrivateAddress(s.address)) { name, silent, remove ->
            if (remove) Store.removeTag(fp) else Store.setTag(DeviceTag(fp, name, current?.what ?: what, silent))
            Repo.retag()
            renderTags()
            refresh()
        }
    }

    private fun openTagEditor(
        name: String, silent: Boolean, existing: Boolean, rotating: Boolean,
        onDone: (name: String, silent: Boolean, remove: Boolean) -> Unit,
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val input = EditText(this).apply {
            setText(name)
            hint = getString(R.string.tag_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        box.addView(input)
        val quiet = CheckBox(this).apply {
            text = getString(R.string.tag_silence)
            isChecked = silent
        }
        box.addView(quiet)
        if (rotating) box.addView(label(getString(R.string.tag_rotating), 12f, amber).apply { setPadding(0, dp(8), 0, 0) })
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing) R.string.tag_button_edit else R.string.tag_title)
            .setView(box)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (existing) b.setNeutralButton(R.string.tag_remove) { _, _ -> onDone(name, silent, true) }
        val d = b.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = input.text.toString().trim()
            if (n.isEmpty()) {
                input.error = getString(R.string.tag_need_name)
            } else {
                d.dismiss()
                onDone(n, quiet.isChecked, false)
            }
        }
    }

    private fun renderTags() {
        tagsBox.removeAllViews()
        val list = Store.tagList()
        setCount(tagsBox, list.size)
        if (list.isEmpty()) tagsBox.addView(empty(getString(R.string.empty_tags)))
        for (t in list.sortedBy { it.name.lowercase() }) {
            tagsBox.addView(row(
                t.name + "  " + getString(R.string.chevron),
                L10n.dots(t.what.takeIf { it.isNotBlank() }, getString(if (t.silent) R.string.tag_alerts_silenced else R.string.tag_alerts_on)),
                if (t.silent) dim else fg,
            ) {
                openTagEditor(t.name, t.silent, existing = true, rotating = false) { name, silent, remove ->
                    if (remove) Store.removeTag(t.fp) else Store.setTag(t.copy(name = name, silent = silent))
                    Repo.retag()
                    renderTags()
                    refresh()
                }
            })
        }
    }

    private fun renderToggles() {
        togglesBox.removeAllViews()
        for (c in Category.entries) {
            val m = Store.mode(c)
            val color = when (m) {
                Mode.ALERT -> green
                Mode.SHOW -> fg
                Mode.OFF -> dim
            }
            val note = getString(when {
                c.isTracker && m == Mode.ALERT -> R.string.note_tracker_alert
                c.isTracker && m == Mode.SHOW -> R.string.note_tracker_show
                !c.canAlert && m == Mode.SHOW -> R.string.note_possible_show
                m == Mode.ALERT -> R.string.note_alert
                m == Mode.SHOW -> R.string.note_show
                else -> R.string.note_off
            })
            togglesBox.addView(row(getString(R.string.toggle_row, c.title, m.title), note, color) { cycleMode(c) })
        }
    }

    private fun cycleMode(c: Category) {
        val next = when (Store.mode(c)) {
            Mode.ALERT -> Mode.SHOW
            Mode.SHOW -> Mode.OFF
            Mode.OFF -> if (c.canAlert) Mode.ALERT else Mode.SHOW
        }
        val apply = {
            Store.setMode(c, next)
            renderToggles()
            refresh()
        }
        if (c.isTracker && Store.mode(c) == Mode.ALERT) {
            AlertDialog.Builder(this)
                .setTitle(R.string.stop_tracker_title)
                .setMessage(R.string.stop_tracker_message)
                .setPositiveButton(R.string.stop_tracker_confirm) { _, _ -> apply() }
                .setNegativeButton(R.string.keep_warnings, null)
                .show()
        } else {
            apply()
        }
    }

    // ---- places ----

    private val muteable get() = Category.entries.filter { !it.isTracker && it.canAlert }

    private fun renderPlaces() {
        placesBox.removeAllViews()
        val here = Repo.location?.let { Store.placeAt(it) }
        shownPlaceId = here?.id
        val list = Store.places()
        if (list.isEmpty()) placesBox.addView(empty(getString(R.string.empty_places)))
        for (p in list) {
            val mutes = if (p.muted.isEmpty()) getString(R.string.place_everything_alerts)
            else getString(
                R.string.place_no_buzz_for,
                p.muted.joinToString(getString(R.string.list_separator)) { it.shortTitle },
            )
            val name = L10n.ltr(p.name)
            val title = if (p.id == here?.id) getString(R.string.place_youre_here, name) else name
            placesBox.addView(row(title, L10n.dots(mutes, getString(R.string.tap_to_change)), if (p.id == here?.id) green else fg) {
                editPlace(p)
            })
        }
        placesBox.addView(row(
            getString(R.string.save_this_spot_row),
            getString(R.string.save_spot_detail, Store.PLACE_RADIUS_M.toInt()),
            green,
        ) {
            savePlace()
        })
    }

    private fun savePlace() {
        val loc = Repo.location
        if (loc == null) {
            Repo.status = getString(R.string.status_need_location_for_place)
            refresh()
            return
        }
        val name = input(getString(R.string.place_name_hint))
        AlertDialog.Builder(this)
            .setTitle(R.string.save_spot_title)
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton(R.string.save) { _, _ ->
                val n = name.text.toString().trim().ifEmpty { getString(R.string.default_place_name, Store.places().size + 1) }
                Store.addPlace(n.take(40), loc)
                renderPlaces()
                renderPrivacy()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editPlace(p: Place) {
        val cats = muteable
        val checked = BooleanArray(cats.size) { cats[it] in p.muted }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.no_buzz_at_title, L10n.ltr(p.name)))
            .setMultiChoiceItems(cats.map { it.title }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.save) { _, _ ->
                Store.setMuted(p, cats.filterIndexed { i, _ -> checked[i] }.toSet())
                renderPlaces()
                renderPrivacy()
            }
            .setNeutralButton(R.string.delete_place) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.delete_place_title, L10n.ltr(p.name)))
                    .setMessage(R.string.delete_place_message)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        Store.removePlace(p)
                        renderPlaces()
                        renderPrivacy()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- privacy ----

    private fun applySecureScreen() {
        if (Store.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun renderPrivacy() {
        privacyBox.removeAllViews()
        val secure = Store.secureScreen
        privacyBox.addView(row(
            getString(if (secure) R.string.hide_screen_on else R.string.hide_screen_off),
            getString(if (secure) R.string.hide_screen_on_detail else R.string.hide_screen_off_detail),
            if (secure) green else fg,
        ) {
            Store.setSecureScreen(!secure)
            applySecureScreen()
            renderPrivacy()
        })
        privacyBox.addView(row(
            getString(R.string.whats_saved),
            getString(
                R.string.whats_saved_detail,
                L10n.p(R.plurals.places_count, Store.places().size),
                L10n.p(R.plurals.fingerprints_count, Store.learnedCount()),
                Store.FORGET_AFTER_DAYS.toInt(),
            ),
            dim,
        ))
        privacyBox.addView(row(getString(R.string.erase_everything), getString(R.string.erase_detail), red) {
            AlertDialog.Builder(this)
                .setTitle(R.string.erase_title)
                .setMessage(R.string.erase_message)
                .setPositiveButton(R.string.erase) { _, _ ->
                    Store.eraseAll()
                    Watchlist.resetInMemory()
                    Repo.clear()
                    Repo.status = getString(if (Repo.running) R.string.status_erased_listening else R.string.status_erased)
                    applySecureScreen()
                    renderWatchlist()
                    renderToggles()
                    renderPlaces()
                    renderPrivacy()
                    renderTags()
                    checkupResult = null
                    renderCheckup()
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
    }

    /** e.g. "very close (-58 dBm)", in the phone's language. */
    private fun strength(s: Sighting) =
        getString(R.string.strength, approx(s), s.rssi)

    /** Rough distance like "~10 m" / "~30 ft". */
    private fun approx(s: Sighting) = Distance.label(Distance.meters(s.rssi, s.source))

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        textDirection = View.TEXT_DIRECTION_LOCALE
        setHintTextColor(dim)
        setTextColor(fg)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(bg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setSingleLine(true)
    }

    companion object {
        private const val REQ_PERMS = 7
        private const val RELEASES_URL = "https://github.com/OwlEar/owlear/releases/latest"
        // Coalition Against Stalkerware: hotlines and safety planning, by country.
        private const val HELP_URL = "https://stopstalkerware.org/resources/"
        /** Most rows shown per Nearby group; the count in the group title still says how many. */
        private const val GROUP_MAX = 25
        /** Most icons drawn on the rings at once; the list below still shows them all. */
        private const val RADAR_MAX = 40
        private const val KEY_PINNED = "pinned"
        /** Which Nearby groups are folded shut, kept while the app runs. */
        private val groupOpen = HashMap<String, Boolean>()
        /** Which sections are folded open, kept while the app runs (screen rotation etc.). */
        private val opened = HashMap<String, Boolean>()
    }
}
