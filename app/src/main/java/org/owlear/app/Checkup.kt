package org.owlear.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Phone checkup: looks for signs of stalkerware on THIS phone and shows them to its owner.
 *
 * Tech: runs only when the person taps "Run checkup". It reads the phone's own app list and
 * settings, compares app IDs and signing certificates against the bundled known-stalkerware
 * list (assets/stalkerware.tsv), and notes apps that hold the kinds of access stalkerware
 * needs. Results live in memory until the screen closes. Nothing is saved, nothing is sent
 * (OwlEar has no internet permission), and nothing is removed or changed: the person decides.
 *
 * Apps that came with the phone are checked too, since spyware has shipped preinstalled and
 * factory apps that can't be uninstalled have been used to push it later. Most of them hold
 * access for ordinary reasons, so they're kept out of the main list unless something is
 * unusual (a screen reader switched on, or an update from outside a store or the phone's own
 * updater). Everything else they can do is listed separately, grouped by kind of access.
 *
 * Wording rule: a finding says what an app CAN do ("can read your screen"), never "this is
 * spyware", except for an exact match against the known list. It will not catch
 * government-grade spyware such as Pegasus; the screen says so and points elsewhere for that.
 */
object Checkup {

    /** Each part of the checkup, individually switchable in the checkup's settings. */
    enum class Check(val titleRes: Int, val hintRes: Int) {
        KNOWN_LIST(R.string.check_known, R.string.check_known_hint),
        SCREEN_READERS(R.string.check_screen, R.string.check_screen_hint),
        NOTIFICATION_READERS(R.string.check_notif, R.string.check_notif_hint),
        DEVICE_ADMIN(R.string.check_admin, R.string.check_admin_hint),
        HIDDEN_APPS(R.string.check_hidden, R.string.check_hidden_hint),
        OUTSIDE_STORE(R.string.check_outside, R.string.check_outside_hint),
        CAM_MIC_LOCATION(R.string.check_sensors, R.string.check_sensors_hint),
        PHONE_SETTINGS(R.string.check_settings, R.string.check_settings_hint),
    }

    enum class Level { KNOWN, CONCERN, NOTE }

    /** Kinds of access shown for apps that came with the phone, most serious first. */
    enum class Access(val titleRes: Int) {
        SCREEN(R.string.check_screen),
        NOTIFICATIONS(R.string.check_notif),
        ADMIN(R.string.check_admin),
        SENSORS(R.string.check_sensors),
    }

    /** An app that came with the phone and holds at least one kind of access. */
    data class FactoryApp(
        val packageName: String,
        val label: String,
        val access: Set<Access>,
    )

    /** One app worth a look, and every reason it showed up. */
    data class AppFinding(
        val packageName: String,
        val label: String,
        val level: Level,
        val reasons: List<String>,
    )

    data class Result(
        val apps: List<AppFinding>,
        val settings: List<String>,
        val appsLooked: Int,
        val ranChecks: Set<Check>,
        val factory: List<FactoryApp>,
    )

    // App stores whose installs we don't flag. Everything else, including a browser download
    // or a file manager, counts as "from outside an app store".
    private val STORES = setOf(
        "com.android.vending", "com.amazon.venezia", "com.sec.android.app.samsungapps",
        "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks", "com.heytap.market",
        "com.oppo.market", "com.bbk.appstore", "com.vivo.appstore", "com.motorola.ccc.ota",
        "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store", "dev.imranr.obtainium",
        "com.google.android.feedback",
    )

    // Besides the stores above, these routinely update apps that came with the phone.
    private val FACTORY_UPDATERS = setOf(
        "android", "com.google.android.gms", "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    private const val OWLEAR_ID = "org.owlear.app"

    private var knownPackages: Map<String, String>? = null
    private var knownCerts: Map<String, String>? = null

    /** Reads the bundled list once. Lines: kind<TAB>value<TAB>family; kind p = app ID, c = cert SHA-1. */
    @Synchronized
    private fun loadList(context: Context) {
        if (knownPackages != null) return
        val pk = HashMap<String, String>()
        val ct = HashMap<String, String>()
        try {
            context.assets.open("stalkerware.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split('\t')
                    if (parts.size < 3) continue
                    when (parts[0]) {
                        "p" -> pk[parts[1]] = parts[2]
                        "c" -> ct[parts[1].uppercase()] = parts[2]
                    }
                }
            }
        } catch (e: Exception) {
            // list missing or damaged: the other checks still run
        }
        knownPackages = pk
        knownCerts = ct
    }

    fun listSize(context: Context): Int {
        loadList(context)
        return knownPackages?.size ?: 0
    }

    /** Runs the switched-on checks. Slow-ish (reads every app), so call it off the main thread. */
    fun run(context: Context, checks: Set<Check>): Result {
        loadList(context)
        val pm = context.packageManager
        val me = context.packageName

        val screen = if (Check.SCREEN_READERS in checks) secureListPackages(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) else emptySet()
        val notif = if (Check.NOTIFICATION_READERS in checks) secureListPackages(context, "enabled_notification_listeners") else emptySet()
        val admins = if (Check.DEVICE_ADMIN in checks) deviceAdmins(context) else emptySet()

        @Suppress("DEPRECATION")
        val certFlag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val flags = PackageManager.GET_PERMISSIONS or certFlag
        val packages: List<PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(flags)
        } catch (e: Exception) {
            emptyList()
        }

        val found = ArrayList<AppFinding>()
        val factory = ArrayList<FactoryApp>()
        for (p in packages) {
            // Skip OwlEar itself, both the real app and the "OwlEar Test" build beside it.
            if (p.packageName == me || p.packageName.startsWith(OWLEAR_ID)) continue
            val ai = p.applicationInfo ?: continue
            val preinstalled = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val reasons = ArrayList<String>()
            var level = Level.NOTE

            if (Check.KNOWN_LIST in checks) {
                val family = knownPackages?.get(p.packageName)
                    ?: certSha1s(p).firstNotNullOfOrNull { knownCerts?.get(it) }
                if (family != null) {
                    reasons.add(L10n.s(R.string.reason_known, L10n.ltr(family)))
                    level = Level.KNOWN
                }
            }

            if (preinstalled) {
                val access = LinkedHashSet<Access>()
                if (p.packageName in screen) access.add(Access.SCREEN)
                if (p.packageName in notif) access.add(Access.NOTIFICATIONS)
                if (p.packageName in admins) access.add(Access.ADMIN)
                if (Check.CAM_MIC_LOCATION in checks && holdsCamMicLocation(p)) access.add(Access.SENSORS)

                // Two things are unusual for a built-in app, so they go in the main list:
                // a screen reader switched on (phones ship with them off), and an update that
                // came from somewhere other than a store or the phone's own updater.
                val unusual = ArrayList<String>()
                if (Access.SCREEN in access) unusual.add(L10n.s(R.string.reason_screen))
                if (Check.OUTSIDE_STORE in checks && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                    updatedOutside(pm, p.packageName)
                ) unusual.add(L10n.s(R.string.reason_factory_updated))
                if (unusual.isNotEmpty()) {
                    reasons.addAll(unusual)
                    if (level != Level.KNOWN) level = Level.CONCERN
                }
                if (reasons.isNotEmpty()) reasons.add(L10n.s(R.string.reason_factory))

                if (access.isNotEmpty()) factory.add(FactoryApp(p.packageName, appLabel(pm, ai, p.packageName), access))
            } else {
                val powerful = ArrayList<String>()
                if (p.packageName in screen) powerful.add(L10n.s(R.string.reason_screen))
                if (p.packageName in notif) powerful.add(L10n.s(R.string.reason_notif))
                if (p.packageName in admins) powerful.add(L10n.s(R.string.reason_admin))
                reasons.addAll(powerful)

                val sensors = Check.CAM_MIC_LOCATION in checks && holdsCamMicLocation(p)
                if (sensors) reasons.add(L10n.s(R.string.reason_sensors))

                if (Check.HIDDEN_APPS in checks && (powerful.isNotEmpty() || sensors) && !hasLauncherIcon(pm, p.packageName)) {
                    reasons.add(L10n.s(R.string.reason_hidden))
                }
                // Installed by hand is normal on its own (web apps, F-Droid, test builds), so it
                // only counts as extra weight next to a real reason.
                if (Check.OUTSIDE_STORE in checks && reasons.isNotEmpty() && !fromStore(pm, p.packageName)) {
                    reasons.add(L10n.s(R.string.reason_outside))
                }
                if (level != Level.KNOWN && (powerful.size >= 2 || (powerful.isNotEmpty() && reasons.size >= 3))) {
                    level = Level.CONCERN
                }
                // A store app that only uses camera/mic/location is normal (maps, video calls).
                // Show it only alongside another reason, to avoid a wall of ordinary apps.
                if (level == Level.NOTE && reasons.size == 1 && sensors) reasons.clear()
            }

            if (reasons.isNotEmpty()) {
                found.add(AppFinding(p.packageName, appLabel(pm, ai, p.packageName), level, reasons))
            }
        }
        found.sortWith(compareBy<AppFinding> { it.level.ordinal }.thenBy { it.label.lowercase() })
        factory.sortBy { it.label.lowercase() }

        val settings = if (Check.PHONE_SETTINGS in checks) phoneSettings(context) else emptyList()
        return Result(found, settings, packages.size, checks, factory)
    }

    private fun appLabel(pm: PackageManager, ai: ApplicationInfo, pkg: String): String = try {
        pm.getApplicationLabel(ai).toString()
    } catch (e: Exception) {
        pkg
    }

    // ---- individual checks ----

    /** Packages named in a colon-separated list of components kept in Settings.Secure. */
    private fun secureListPackages(context: Context, key: String): Set<String> {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, key)
        } catch (e: Exception) {
            null
        } ?: return emptySet()
        return raw.split(':').mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
    }

    private fun deviceAdmins(context: Context): Set<String> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return emptySet()
        return try {
            dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun certSha1s(p: PackageInfo): List<String> {
        val sigs: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return emptyList()
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory) ?: return emptyList()
        } else {
            @Suppress("DEPRECATION")
            p.signatures ?: return emptyList()
        }
        val md = MessageDigest.getInstance("SHA-1")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02X".format(it) } }
    }

    private fun holdsCamMicLocation(p: PackageInfo): Boolean {
        val req = p.requestedPermissions ?: return false
        val got = p.requestedPermissionsFlags ?: return false
        val granted = HashSet<String>()
        for (i in req.indices) {
            if (i < got.size && got[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) granted.add(req[i])
        }
        val loc = "android.permission.ACCESS_FINE_LOCATION" in granted || "android.permission.ACCESS_COARSE_LOCATION" in granted
        return loc && "android.permission.CAMERA" in granted && "android.permission.RECORD_AUDIO" in granted
    }

    private fun hasLauncherIcon(pm: PackageManager, pkg: String): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return try {
            pm.queryIntentActivities(i, 0).isNotEmpty()
        } catch (e: Exception) {
            true
        }
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    } catch (e: Exception) {
        null
    }

    private fun fromStore(pm: PackageManager, pkg: String): Boolean {
        val installer = installerOf(pm, pkg)
        return installer != null && installer in STORES
    }

    /**
     * For a built-in app that has an update on top of it: true when the update came from a
     * named installer that isn't a store or the phone's own updater (for example a computer
     * over USB, or another app). No installer recorded means we can't tell, so it isn't flagged.
     */
    private fun updatedOutside(pm: PackageManager, pkg: String): Boolean {
        val installer = installerOf(pm, pkg) ?: return false
        return installer != pkg && installer !in STORES && installer !in FACTORY_UPDATERS
    }

    /** Phone-wide settings that make spying easier. Plain sentences, ready to show. */
    private fun phoneSettings(context: Context): List<String> {
        val out = ArrayList<String>()
        val cr = context.contentResolver
        fun global(key: String) = try {
            Settings.Global.getInt(cr, key, 0) == 1
        } catch (e: Exception) {
            false
        }
        if (global(Settings.Global.ADB_ENABLED)) out.add(L10n.s(R.string.setting_found_usb))
        else if (global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)) out.add(L10n.s(R.string.setting_found_dev))

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            try {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) out.add(L10n.s(R.string.setting_found_vpn))
                if (cm.defaultProxy != null) out.add(L10n.s(R.string.setting_found_proxy))
            } catch (e: Exception) {
                // network state not readable on this phone
            }
        }
        return out
    }
}
