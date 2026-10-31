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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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
    private lateinit var cellText: TextView
    private lateinit var nearbyText: TextView
    private lateinit var watchlistBox: LinearLayout
    private lateinit var togglesBox: LinearLayout
    private lateinit var placesBox: LinearLayout
    private lateinit var privacyBox: LinearLayout
    private var shownPlaceId: String? = "unset"
    private lateinit var radar: RadarView
    private lateinit var sweepButton: Button
    private var sweeping = false
    private var sensorManager: SensorManager? = null

    private val sensorListener = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            val deg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
            Repo.heading = deg
            radar.heading = deg
            if (!radar.animating) radar.invalidate()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

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
        handler.post(refresher)
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = sm
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
        sensorManager?.unregisterListener(sensorListener)
    }

    // ---- direction sweep ----

    private fun onSweep() {
        if (!Repo.running) {
            Repo.status = getString(R.string.status_start_first)
            refresh()
            return
        }
        if (sweeping) return
        if (sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) {
            Repo.status = getString(R.string.status_no_compass)
            refresh()
            return
        }
        sweeping = true
        Repo.startSweep(System.currentTimeMillis(), ScanService.SWEEP_MS)
        try {
            startService(Intent(this, ScanService::class.java).setAction(ScanService.ACTION_SWEEP))
        } catch (e: Exception) {
            // scanning still works at normal speed
        }
        val endAt = System.currentTimeMillis() + ScanService.SWEEP_MS
        val tick = object : Runnable {
            override fun run() {
                val left = (endAt - System.currentTimeMillis()) / 1000L
                if (left > 0) {
                    sweepButton.text = getString(R.string.sweep_turning, left.toInt())
                    handler.postDelayed(this, 500L)
                } else {
                    val found = Repo.finishSweep(System.currentTimeMillis())
                    sweeping = false
                    sweepButton.text = getString(R.string.find_direction)
                    Repo.status = if (found > 0) L10n.p(R.plurals.direction_found, found)
                    else getString(R.string.status_direction_failed)
                    refresh()
                }
            }
        }
        handler.post(tick)
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

    private fun section(title: String, hint: String?): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label(title, 16f, fg, bold = true))
            if (hint != null) addView(label(hint, 12f, dim).apply { setPadding(0, dp(2), 0, dp(6)) })
            addView(box)
        }
        root.addView(wrap, spaced())
        return box
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

        val radarBox = section(getString(R.string.section_radar), getString(R.string.hint_radar))
        radar = RadarView(this)
        radarBox.addView(radar)
        sweepButton = Button(this).apply {
            text = getString(R.string.find_direction)
            isAllCaps = false
            setTextColor(fg)
            background = rounded(Color.parseColor("#24392E"))
            setOnClickListener { onSweep() }
        }
        radarBox.addView(sweepButton, spaced().apply { height = dp(46) })

        recentBox = section(getString(R.string.section_recent), getString(R.string.hint_recent))

        followingBox = section(
            getString(R.string.section_following),
            getString(R.string.hint_following, (Repo.FOLLOW_MIN_MS / 60_000).toInt(), Repo.FOLLOW_MIN_PLACES),
        )
        hitsBox = section(getString(R.string.section_hits), getString(R.string.hint_hits))
        spottedBox = section(getString(R.string.section_spotted), getString(R.string.hint_spotted))
        val cellBox = section(getString(R.string.section_cell), getString(R.string.hint_cell))
        cellText = label(getString(R.string.cell_waiting), 13f, dim)
        cellBox.addView(cellText)
        trackersBox = section(getString(R.string.section_trackers), getString(R.string.hint_trackers))
        val nearbyBox = section(getString(R.string.section_others), getString(R.string.hint_others))
        nearbyText = label("", 13f, dim)
        nearbyBox.addView(nearbyText)
        (nearbyBox.parent as View).setOnClickListener { showOthers() }

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

        watchlistBox = section(getString(R.string.section_watchlist), getString(R.string.hint_watchlist))

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
        val m = ms / 60_000L
        return if (m < 1) getString(R.string.just_now) else getString(R.string.minutes_ago, m.toInt())
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

        // Only what's being heard right now goes on the radar; the rest drops to "Recently off radar".
        val onRadar = all.filter {
            it.kind != Kind.OTHER && now - it.lastSeen < ON_RADAR_MS && !Repo.isIgnored(it.address)
        }
        radar.items = onRadar

        recentBox.removeAllViews()
        val recent = all.filter {
            it.kind != Kind.OTHER && it !in onRadar && now - it.lastSeen < 60 * 60_000L &&
                !Repo.isIgnored(it.address)
        }.sortedByDescending { it.lastSeen }
        if (recent.isEmpty()) recentBox.addView(empty(getString(R.string.empty_recent)))
        for (s in recent.take(30)) {
            val possible = s.confidence == Confidence.POSSIBLE
            recentBox.addView(row(
                s.label,
                L10n.dots(
                    getString(R.string.last_heard, ago(now - s.lastSeen)), L10n.ltr(s.source),
                    getString(R.string.was_proximity, Detection.proximity(s.rssi)),
                ),
                if (s.kind == Kind.TRACKER || possible) dim else amber,
            ))
        }
        if (radar.animating != Repo.running) {
            radar.animating = Repo.running
        }
        radar.invalidate()

        // Following
        followingBox.removeAllViews()
        val following = all.filter {
            it.kind == Kind.TRACKER && it.separated && Repo.isFollowing(it) && !Repo.isIgnored(it.address)
        }
        if (following.isEmpty()) followingBox.addView(empty(getString(R.string.empty_following)))
        for (s in following.sortedByDescending { it.lastSeen }) {
            followingBox.addView(row(
                s.label,
                L10n.dots(
                    getString(R.string.with_you, s.minutesTracked.toInt()),
                    L10n.p(R.plurals.places_count, s.places.size),
                    Detection.proximity(s.rssi),
                    getString(R.string.last_heard, ago(now - s.lastSeen)),
                ),
                red,
            ) { confirmIgnore(s) })
        }

        // Watchlist hits
        hitsBox.removeAllViews()
        val hits = all.filter { it.kind == Kind.WATCHLIST && now - it.lastSeen < 60 * 60_000L }
            .sortedByDescending { it.lastSeen }
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
            ))
        }

        // Drones & camera glasses
        spottedBox.removeAllViews()
        val spotted = all.filter { it.kind == Kind.SPOTTED && now - it.lastSeen < 30 * 60_000L }
            .sortedByDescending { it.lastSeen }
        if (spotted.isEmpty()) spottedBox.addView(empty(getString(R.string.empty_none_yet)))
        for (s in spotted.take(30)) {
            val possible = s.confidence == Confidence.POSSIBLE
            val quiet = s.category?.let { Store.mode(it) != Mode.ALERT } == true || s.mutedAt != null
            spottedBox.addView(row(
                s.label,
                L10n.dots(
                    strength(s), L10n.ltr(s.source),
                    s.detail?.let { getString(R.string.drone_id, L10n.ltr(it)) },
                    ago(now - s.lastSeen),
                    s.mutedAt?.let { getString(R.string.muted_at, L10n.ltr(it)) },
                ),
                if (possible || quiet) dim else amber,
            ))
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
        }.sortedByDescending { it.rssi }
        if (trackers.isEmpty()) trackersBox.addView(empty(getString(R.string.empty_trackers)))
        for (s in trackers.take(30)) {
            val away = getString(if (s.separated) R.string.tracker_away else R.string.tracker_near)
            trackersBox.addView(row(
                s.label,
                L10n.dots(
                    away, Detection.proximity(s.rssi),
                    getString(R.string.tracker_heard, s.minutesTracked.toInt(), L10n.p(R.plurals.places_count, s.places.size)),
                ),
                fg,
            ) { confirmIgnore(s) })
        }

        val others = all.count { it.kind == Kind.OTHER && now - it.lastSeen < 2 * 60_000L }
        nearbyText.text = L10n.p(R.plurals.others_count, others) + " " + getString(R.string.tap_to_view)
    }

    // ---- settings ----

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
            ) { offerWatch(s) })
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
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
    }

    /** e.g. "very close (-58 dBm)", in the phone's language. */
    private fun strength(s: Sighting) =
        getString(R.string.strength, Detection.proximity(s.rssi), s.rssi)

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
        /** Bluetooth repeats several times a second and Wi-Fi is re-read every 30 s. */
        private const val ON_RADAR_MS = 45_000L
    }
}
