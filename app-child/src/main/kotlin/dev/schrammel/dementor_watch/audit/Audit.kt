package dev.schrammel.dementor_watch.audit

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.AuditEntry
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * The family's shared history (TR-3).
 *
 * Every rule change, every request and every answer, with who made it and when.
 * Both sides read the identical list from their own screen — there is no parent
 * view and child view, there is one log.
 *
 * Append-only by construction: nothing here updates or deletes an entry, and the
 * only way a row leaves is by ageing out past the cap in [AuditLogFormat].
 *
 * A static object taking a [Context], matching `Diagnostics`, because recording
 * happens from twenty scattered call sites and threading a store through all of
 * them would guarantee the one that gets forgotten is the interesting one.
 */
object Audit {

    /**
     * Records an action, unless nothing actually changed.
     *
     * The no-op guard is not a nicety: sliders and switches fire on every touch,
     * and a history where nine rows in ten say "changed from 45 to 45" is one
     * nobody will scroll.
     */
    fun record(
        context: Context,
        actor: AuditActor,
        action: AuditAction,
        subject: String? = null,
        before: String? = null,
        after: String? = null,
        at: Instant = Instant.now(),
    ) {
        if (before != null && before == after) return

        val prefs = prefs(context)
        val updated = AuditLogFormat.append(
            raw = prefs.getString(KEY_LOG, null),
            entry = AuditEntry(
                id = UUID.randomUUID().toString(),
                at = at,
                actor = actor,
                action = action,
                subject = subject,
                before = before,
                after = after,
            ),
        )
        prefs.edit { putString(KEY_LOG, updated) }
    }

    /** Newest first, the way the screen reads it. */
    fun entries(context: Context): List<AuditEntry> =
        AuditLogFormat.decodeAll(prefs(context).getString(KEY_LOG, null)).asReversed()

    fun minutes(value: Int): String = value.toString()

    fun flag(value: Boolean): String = value.toString()

    fun timeRange(start: LocalTime, end: LocalTime): String = "$start-$end"

    private const val FILE_NAME = "audit"
    private const val KEY_LOG = "log"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
