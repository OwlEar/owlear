package org.owlear.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult as BleScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Foreground service that keeps listening while the screen is off.
 *
 * Tech: a filtered BLE scan (an empty filter, which Android treats as "filtered" so it
 * keeps running with the screen off), restarted every 10 minutes because Android quietly
 * downgrades scans that run longer than 30 minutes. Wi-Fi results are read every 30 s.
 * Mechanic version: the engine idles in the background and we bump the starter every
 * so often so the phone doesn't decide to shut it off.
 */
class ScanService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var locationManager: LocationManager? = null
    private var lastWifiScanRequest = 0L

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) {
            deliver(Repo.record(result, System.currentTimeMillis()))
        }

        override fun onBatchScanResults(results: MutableList<BleScanResult>) {
            val now = System.currentTimeMillis()
            for (r in results) deliver(Repo.record(r, now))
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Repo.status = getString(R.string.status_bt_failed, errorCode)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Repo.location = location
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val restarter = object : Runnable {
        override fun run() {
            restartScan()
            handler.postDelayed(this, if (scanning) RESTART_MS else RETRY_MS)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            pollWifi(now)
            checkCell(now)
            Repo.prune(now)
            Store.maintain(now)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        L10n.init(this)
        Watchlist.init(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SWEEP && Repo.running) {
            // Listen as fast as possible while the person turns, then go back to normal.
            restartScan(lowLatency = true)
            handler.postDelayed({ restartScan() }, SWEEP_MS + 1_000L)
            return START_NOT_STICKY
        }
        if (!Repo.running) {
            Repo.running = true
            startLocation()
            handler.post(restarter)
            handler.postDelayed(ticker, 5_000L)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: Exception) {
            // ignore
        }
        Store.maintain(System.currentTimeMillis())
        Repo.running = false
        Repo.status = getString(R.string.status_stopped)
        super.onDestroy()
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun goForeground(): Boolean = try {
        val n = statusNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocation()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(STATUS_ID, n, type)
        } else {
            startForeground(STATUS_ID, n)
        }
        true
    } catch (e: Exception) {
        Repo.status = getString(R.string.status_couldnt_start, e.message.orEmpty())
        false
    }

    @SuppressLint("MissingPermission")
    private fun restartScan(lowLatency: Boolean = false) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            stopScan()
            Repo.status = getString(R.string.status_bt_off)
            return
        }
        stopScan()
        val s = adapter.bluetoothLeScanner
        if (s == null) {
            Repo.status = getString(R.string.status_bt_unavailable)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .build()
        try {
            s.startScan(listOf(ScanFilter.Builder().build()), settings, callback)
            scanner = s
            scanning = true
            Repo.status = getString(
                if (hasLocation()) R.string.status_scanning_both else R.string.status_scanning_bt_only
            )
        } catch (e: Exception) {
            Repo.status = getString(R.string.status_scan_error, e.message.orEmpty())
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) {
            try {
                scanner?.stopScan(callback)
            } catch (e: Exception) {
                // ignore
            }
        }
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun pollWifi(now: Long) {
        if (!hasLocation()) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        try {
            // Android limits how often apps may trigger a Wi-Fi scan, so we ask at most every
            // 2 minutes and otherwise read whatever the phone's own scans already found.
            if (now - lastWifiScanRequest > WIFI_SCAN_MS) {
                lastWifiScanRequest = now
                @Suppress("DEPRECATION")
                wifi.startScan()
            }
            val nowMicros = SystemClock.elapsedRealtime() * 1000L
            for (r in wifi.scanResults) {
                // Skip cached results older than 3 min so a camera you drove past
                // doesn't keep showing as "just heard".
                if (nowMicros - r.timestamp > 180_000_000L) continue
                @Suppress("DEPRECATION")
                val ssid = r.SSID?.trim('"')
                val bssid = r.BSSID ?: continue
                deliver(Repo.recordWifi(bssid, ssid, r.level, wifiRemoteId(r), now))
            }
        } catch (e: Exception) {
            // Wi-Fi off or results unavailable; Bluetooth keeps going.
        }
    }

    /** Drones can also send Remote ID inside Wi-Fi beacons (vendor element 221). Android 11+. */
    private fun wifiRemoteId(r: ScanResult): Spotted? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            r.informationElements.firstNotNullOfOrNull { ie ->
                if (ie.id != 221) null
                else {
                    val buf = ie.bytes.duplicate()
                    val arr = ByteArray(buf.remaining())
                    buf.get(arr)
                    Detection.classifyWifiRemoteId(arr)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkCell(now: Long) {
        val (reading, alert) = CellWatch.check(this, now)
        Repo.cell = reading
        if (!alert) return
        val n = Notification.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_cell_title))
            .setContentText(reading.summary)
            .setStyle(Notification.BigTextStyle().bigText(reading.summary))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(CELL_ID, n)
        } catch (e: SecurityException) {
            // notifications not allowed; the app screen still shows it
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocation()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        val all = lm.allProviders
        val providers = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 && all.contains(LocationManager.FUSED_PROVIDER)) {
            providers.add(LocationManager.FUSED_PROVIDER)
        } else {
            if (all.contains(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
            if (all.contains(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
        }
        for (p in providers) {
            try {
                if (Repo.location == null) lm.getLastKnownLocation(p)?.let { Repo.location = it }
                lm.requestLocationUpdates(p, 60_000L, 50f, locationListener, Looper.getMainLooper())
            } catch (e: Exception) {
                // provider unavailable on this phone
            }
        }
    }

    // ---- notifications ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, getString(R.string.ch_status), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        // Silent on purpose: the app plays the sound you picked in Settings (see Sounds).
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, getString(R.string.ch_watch), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun statusNotification(): Notification =
        Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_listening_title))
            .setContentText(getString(R.string.notif_listening_text))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private fun deliver(alerts: List<Alert>) {
        if (alerts.isEmpty()) return
        val nm = getSystemService(NotificationManager::class.java)
        if (alerts.any { it is Alert.WatchHit }) Sounds.play(this)
        for (a in alerts) {
            val (title, text, s) = when (a) {
                is Alert.Following -> Triple(
                    getString(R.string.notif_following_title),
                    getString(
                        R.string.notif_following_text, a.sighting.label, a.sighting.minutesTracked.toInt(),
                        resources.getQuantityString(R.plurals.places_count, a.sighting.places.size, a.sighting.places.size),
                    ),
                    a.sighting,
                )
                is Alert.WatchHit -> Triple(
                    getString(R.string.notif_detected, a.sighting.label),
                    getString(
                        R.string.notif_hit_text, Detection.proximity(a.sighting.rssi),
                        L10n.ltr(a.sighting.source), a.sighting.rssi,
                    ),
                    a.sighting,
                )
            }
            val channel = if (a is Alert.WatchHit) CH_WATCH else CH_ALERTS
            val n = Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            try {
                nm.notify((s.source + s.address).hashCode(), n)
            } catch (e: SecurityException) {
                // notifications not allowed; the app screen still shows everything
            }
        }
    }

    companion object {
        const val ACTION_SWEEP = "org.owlear.app.SWEEP"
        const val SWEEP_MS = 12_000L
        private const val STATUS_ID = 1
        private const val CELL_ID = 2
        private const val CH_STATUS = "status"
        private const val CH_ALERTS = "alerts"
        private const val CH_WATCH = "watch_alerts"
        private const val RESTART_MS = 10 * 60_000L
        private const val RETRY_MS = 30_000L
        private const val TICK_MS = 30_000L
        private const val WIFI_SCAN_MS = 2 * 60_000L
    }
}
