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

        context.getSystemService(TelecomManager::class.java)
            ?.defaultDialerPackage
            ?.let { add(PackageName(it)) }

        Telephony.Sms.getDefaultSmsPackage(context)
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
    }

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

    private fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    private const val SYSTEM_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

    private val CLOCK_PACKAGES = listOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
    )
}
