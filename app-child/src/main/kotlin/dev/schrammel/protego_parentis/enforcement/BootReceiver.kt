package dev.schrammel.protego_parentis.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import java.time.Duration
import java.time.Instant

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

        // android:exported="true" is required for the real system broadcast to
        // reach this receiver, which also means any co-installed app can send
        // the same action with no way for this to tell the two apart (#251).
        // EnforcementService.start() and AppRegistry.realtimeSync.connectRealtime()
        // both do real work — service (re)creation, tearing down and reopening
        // the SSE socket — on every call, so a spoofed broadcast repeated
        // rapidly costs battery and connection churn each time, not just once.
        // See BootTriggerCooldown's own doc comment for why this can only
        // close the *repeated* cost, not the ability to trigger it once.
        if (!BootTriggerCooldown(context).tryAcquire(Instant.now())) return

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
        AppRegistry.realtimeSync.connectRealtime()
    }
}

/**
 * Whether [BootReceiver] should actually act on a BOOT_COMPLETED broadcast
 * right now, or ignore it as a repeat within [window] of the last one (#251).
 *
 * Persisted rather than an in-memory timestamp: an in-memory cooldown only
 * holds for as long as this process stays alive, and a spoofed broadcast is
 * itself a way to relaunch the process, which would reset it right back to
 * unthrottled. A real reboot fires this receiver once, so a family never
 * reboots often enough for a one-minute cooldown to be noticeable; a
 * co-installed app spamming the same broadcast is the only thing it
 * actually throttles.
 */
internal class BootTriggerCooldown(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * True, and records [now], the first time this is called in [window] —
     * false, leaving the recorded time untouched, every time after that
     * until [window] has passed again.
     */
    fun tryAcquire(now: Instant, window: Duration = DEFAULT_WINDOW): Boolean {
        val last = prefs.getLong(KEY_LAST_RUN_MILLIS, 0L)
            .takeIf { it > 0L }
            ?.let(Instant::ofEpochMilli)
        if (last != null && Duration.between(last, now) < window) return false

        prefs.edit { putLong(KEY_LAST_RUN_MILLIS, now.toEpochMilli()) }
        return true
    }

    private companion object {
        const val FILE_NAME = "boot_trigger_cooldown"
        const val KEY_LAST_RUN_MILLIS = "last_run_millis"
        val DEFAULT_WINDOW: Duration = Duration.ofMinutes(1)
    }
}
