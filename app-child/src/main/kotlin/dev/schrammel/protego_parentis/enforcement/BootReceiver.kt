package dev.schrammel.protego_parentis.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.RealtimeSyncService

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
        Diagnostics.record(context, DiagnosticEvent.BOOT_COMPLETED)

        // Only the managed phone has anything to enforce. Starting the service on
        // a parent's own handset gave it a permanent monitoring notification and,
        // worse, made it publish heartbeats that other parents read as the child's.
        if (RoleStore(context).roleOrChild == DeviceRole.CHILD) {
            EnforcementService.start(context)
        }

        // Both roles run the realtime stream so messages land as they are
        // published rather than on the next poll. The socket manager checks
        // settings.isUsable before opening a connection, so a half-configured
        // phone harmlessly does nothing.
        RealtimeSyncService.start(context)
    }
}
