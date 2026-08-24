package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit
import java.time.Instant

/**
 * Whether the child currently has an emergency raised (LOC-3).
 *
 * Persisted, not held in memory. An emergency that a process restart quietly
 * cancels is worse than no emergency button at all: the phone would go back to
 * enforcing bedtime in the middle of whatever made them press it, and the repeat
 * to the parents would stop with nobody told it had.
 *
 * Only the child's own device writes this. A parent cannot raise one on their
 * behalf, and — deliberately — cannot cancel one either. Whoever is in the
 * situation decides when it is over.
 */
class SosStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val isActive: Boolean get() = raisedAt != null

    val raisedAt: Instant?
        get() = prefs.getLong(KEY_RAISED_AT, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    /** When the parents were last told. Drives the repeat, so it survives a restart. */
    var lastSentAt: Instant?
        get() = prefs.getLong(KEY_LAST_SENT, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_SENT) else putLong(KEY_LAST_SENT, value.toEpochMilli())
        }

    /** Returns false if one was already raised, so pressing twice is not two emergencies. */
    fun raise(now: Instant = Instant.now()): Boolean {
        if (isActive) return false
        prefs.edit(commit = true) {
            putLong(KEY_RAISED_AT, now.toEpochMilli())
            remove(KEY_LAST_SENT)
        }
        return true
    }

    fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    private companion object {
        const val FILE_NAME = "sos"
        const val KEY_RAISED_AT = "raisedAt"
        const val KEY_LAST_SENT = "lastSentAt"
    }
}
