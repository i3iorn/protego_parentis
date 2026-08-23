package dev.schrammel.dementor_watch.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.Heartbeat
import java.time.Instant

/**
 * The last thing a parent phone heard from the child device (TR-2).
 *
 * Only the most recent one is kept. A history of heartbeats would be a record of
 * when the phone was switched on and off, which is a movement log by another name
 * and not something this product collects.
 */
class HeartbeatStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var latest: Heartbeat?
        get() {
            val sentAt = prefs.getLong(KEY_SENT_AT, 0L).takeIf { it > 0L } ?: return null
            return Heartbeat(
                sentAt = Instant.ofEpochMilli(sentAt),
                policyVersion = prefs.getLong(KEY_VERSION, 0L),
                batteryPercent = prefs.getInt(KEY_BATTERY, UNKNOWN_BATTERY),
                managed = prefs.getBoolean(KEY_MANAGED, false),
                usageAccessGranted = prefs.getBoolean(KEY_USAGE_ACCESS, false),
                suspendedCount = prefs.getInt(KEY_SUSPENDED, 0),
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit { clear() }
                return
            }
            // Out-of-order arrival is normal: the poll window overlaps, and ntfy
            // does not promise ordering. Keeping the newest is what makes "how long
            // has it been quiet" mean anything.
            val known = latest
            if (known != null && value.sentAt.isBefore(known.sentAt)) return

            prefs.edit {
                putLong(KEY_SENT_AT, value.sentAt.toEpochMilli())
                putLong(KEY_VERSION, value.policyVersion)
                putInt(KEY_BATTERY, value.batteryPercent)
                putBoolean(KEY_MANAGED, value.managed)
                putBoolean(KEY_USAGE_ACCESS, value.usageAccessGranted)
                putInt(KEY_SUSPENDED, value.suspendedCount)
            }
        }

    /**
     * Records that the phone said it was switching off (LOC-4).
     *
     * Stored as a fact about the last heartbeat rather than as a state of its own:
     * the parent's screen already says "we have not heard from this phone", and a
     * shutdown is that with a reason, which is the difference between a parent
     * worrying and a parent shrugging.
     */
    fun recordStopped(at: Instant) {
        prefs.edit { putLong(KEY_STOPPED_AT, at.toEpochMilli()) }
    }

    /** When the phone last said it was switching off, if it did. */
    val stoppedAt: Instant?
        get() = prefs.getLong(KEY_STOPPED_AT, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    /**
     * Whether the last thing heard was a shutdown.
     *
     * A heartbeat after the shutdown means the phone came back, so the goodbye is
     * spent and the silence that follows is ordinary silence again.
     */
    fun stoppedAfterLastHeartbeat(): Boolean {
        val stopped = stoppedAt ?: return false
        val heard = latest?.sentAt ?: return true
        return !stopped.isBefore(heard)
    }

    /**
     * Whether a "this phone has gone quiet" alert has already been raised for the
     * current stretch of silence, so it fires once rather than every fifteen
     * minutes until somebody looks at their phone.
     */
    var silenceAlerted: Boolean
        get() = prefs.getBoolean(KEY_ALERTED, false)
        set(value) = prefs.edit { putBoolean(KEY_ALERTED, value) }

    private companion object {
        const val FILE_NAME = "heartbeat"
        const val UNKNOWN_BATTERY = -1

        const val KEY_SENT_AT = "sentAt"
        const val KEY_VERSION = "policyVersion"
        const val KEY_BATTERY = "battery"
        const val KEY_MANAGED = "managed"
        const val KEY_USAGE_ACCESS = "usageAccess"
        const val KEY_SUSPENDED = "suspended"
        const val KEY_ALERTED = "silenceAlerted"
        const val KEY_STOPPED_AT = "stoppedAt"
    }
}
