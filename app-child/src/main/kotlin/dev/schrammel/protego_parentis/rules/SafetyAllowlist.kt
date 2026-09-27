package dev.schrammel.protego_parentis.rules

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Telephony
import android.telecom.TelecomManager
import dev.schrammel.protego_parentis.model.PackageName

/**
 * The apps that must never be restricted, resolved from the device rather than
 * hard-coded — the dialer and messaging app differ by OEM and by user choice, and
 * a hard-coded list would quietly fail to protect the one that is actually in use.
 *
 * This is the last line of the "the phone must still be a phone" invariant.
 */
object SafetyAllowlist {

    fun of(context: Context): Set<PackageName> = buildSet {
        // Never restrict ourselves: the teen needs the dashboard and the request
        // screen precisely when everything else is blocked.
        add(PackageName(context.packageName))

        // The default dialer and SMS app, but only when they are system apps
        // (#293). Whoever holds the phone chooses the defaults, and this list
        // is exempt from every rule, so a third-party app made the default SMS
        // app would otherwise escape bedtime and every budget. Lockdown also
        // stops the defaults being changed on API 34+.
        context.getSystemService(TelecomManager::class.java)
            ?.defaultDialerPackage
            ?.takeIf { isSystemApp(context, it) }
            ?.let { add(PackageName(it)) }

        Telephony.Sms.getDefaultSmsPackage(context)
            ?.takeIf { isSystemApp(context, it) }
            ?.let { add(PackageName(it)) }

        // The alarm clock. Without this, the first support ticket is "my kid
        // missed school because bedtime blocked their alarm". Asked of the
        // device, like the dialer: whichever app answers "show my alarms" is
        // the one in use, on any manufacturer's phone. System apps only: this
        // list is exempt from every rule, and any installed app can declare it
        // shows alarms. The named packages stay as a fallback for a clock that
        // registers no such activity.
        addAll(alarmApps(context).map(::PackageName))
        addAll(CLOCK_PACKAGES.filter { isInstalled(context, it) }.map(::PackageName))

        // Diabetes apps: a CGM reader or a pump controller blocked by bedtime
        // is a medical risk, not a screen-time decision. Matched by vendor
        // namespace because each ships many regional builds
        // (com.freestylelibre.app.gb, com.dexcom.g6.region3.mgdl, ...). Not
        // limited to Play installs: AndroidAPS and xDrip+ are sideloaded by
        // design. A sideloaded look-alike under one of these names is still
        // reported to the parents as a new app, like any other install.
        addAll(diabetesApps(context).map(::PackageName))
    }

    private fun diabetesApps(context: Context): List<String> = runCatching {
        context.packageManager
            .getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            .map { it.packageName }
            .filter(::isDiabetesApp)
    }.getOrDefault(emptyList())

    /** Whether [pkg] belongs to one of [DIABETES_PACKAGES], exactly or as a regional build of it. */
    internal fun isDiabetesApp(pkg: String): Boolean =
        DIABETES_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }

    private fun alarmApps(context: Context): Set<String> =
        listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM).flatMapTo(mutableSetOf()) { action ->
            runCatching {
                context.packageManager
                    .queryIntentActivities(Intent(action), PackageManager.ResolveInfoFlags.of(0L))
                    .mapNotNull { it.activityInfo }
                    .filter { (it.applicationInfo?.flags ?: 0) and SYSTEM_FLAGS != 0 }
                    .map { it.packageName }
            }.getOrDefault(emptyList())
        }

    private fun isSystemApp(context: Context, pkg: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
        info.flags and SYSTEM_FLAGS != 0
    }.getOrDefault(false)

    private fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    private const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

    /**
     * Vendor namespaces and apps, each confirmed against a Play Store listing
     * or the project's own documentation:
     *  - com.dexcom: Dexcom G6/G7, Follow and Clarity.
     *  - com.freestylelibre.app / com.freestylelibre3.app: FreeStyle
     *    LibreLink and Libre 3, one build per country.
     *  - com.eveningoutpost.dexdrip: xDrip+.
     *  - tk.glucodata: Juggluco.
     *  - info.nightscout.androidaps: AndroidAPS, which controls a pump.
     * Apps not listed here (CamAPS FX, Medtronic, Omnipod, the newer "Libre"
     * app) can still be exempted by a parent with a safety-exempt rule.
     */
    private val DIABETES_PACKAGES = listOf(
        "com.dexcom",
        "com.freestylelibre.app",
        "com.freestylelibre3.app",
        "com.eveningoutpost.dexdrip",
        "tk.glucodata",
        "info.nightscout.androidaps",
    )

    private val CLOCK_PACKAGES = listOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
    )
}
