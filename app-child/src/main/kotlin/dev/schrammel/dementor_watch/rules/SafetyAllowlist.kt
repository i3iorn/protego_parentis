package dev.schrammel.dementor_watch.rules

import android.content.Context
import android.provider.Telephony
import android.telecom.TelecomManager
import dev.schrammel.dementor_watch.model.PackageName

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
        // missed school because bedtime blocked their alarm".
        addAll(CLOCK_PACKAGES.filter { isInstalled(context, it) }.map(::PackageName))
    }

    private fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    private val CLOCK_PACKAGES = listOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
    )
}
