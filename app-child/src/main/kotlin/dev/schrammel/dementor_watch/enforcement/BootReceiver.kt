package dev.schrammel.dementor_watch.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms enforcement after a reboot.
 *
 * Rebooting is the most obvious thing to try when a phone stops letting you into
 * a game, so this path matters more than it looks. Geofences and exact alarms are
 * also cleared by a reboot and will be re-registered from here once M8 lands.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        EnforcementService.start(context)
    }
}
