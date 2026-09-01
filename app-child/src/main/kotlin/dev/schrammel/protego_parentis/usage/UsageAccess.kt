package dev.schrammel.protego_parentis.usage

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Usage access is an "app op", not a normal runtime permission, so there is no
 * dialog we can show — the user has to toggle it in Settings themselves.
 *
 * Even a device owner cannot grant this to itself, which is why it is a step in
 * the setup flow rather than something handled silently at provisioning time.
 */
object UsageAccess {

    // The app-op check is the only reliable way to read this state. The platform
    // keeps deprecating and renaming the accessor without removing it; there is no
    // non-deprecated equivalent that works across our supported range.
    @Suppress("DEPRECATION")
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Opens the system screen where the user turns usage access on. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
