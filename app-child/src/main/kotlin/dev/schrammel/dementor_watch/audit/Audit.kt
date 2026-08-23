package dev.schrammel.dementor_watch.audit

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.AuditChain
import dev.schrammel.dementor_watch.model.AuditEntry
import dev.schrammel.dementor_watch.model.ChainCheck
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
     * Serialises the read-modify-write below.
     *
     * Entries are recorded from the enforcement worker, the sync executor and the
     * UI thread. Appending is read-then-write, so without this two entries written
     * at the same moment lose one of themselves — and a history that quietly drops
     * rows is worse than no history, because it is still believed.
     */
    private val lock = Any()

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

        val entry = AuditEntry(
            id = UUID.randomUUID().toString(),
            at = at,
            actor = actor,
            action = action,
            subject = subject,
            before = before,
            after = after,
        )
        synchronized(lock) {
            val prefs = prefs(context)
            val appended = AuditLogFormat.append(
                raw = prefs.getString(KEY_LOG, null),
                anchor = prefs.getString(KEY_ANCHOR, null) ?: AuditChain.GENESIS,
                entry = entry,
            )
            // commit, not apply: a crash between recording an action and the write
            // landing would leave the history disagreeing with what the app did.
            prefs.edit(commit = true) {
                putString(KEY_LOG, appended.log)
                putString(KEY_ANCHOR, appended.anchor)
            }
        }
    }

    /** Newest first, the way the screen reads it. */
    fun entries(context: Context): List<AuditEntry> =
        AuditLogFormat.decodeAll(prefs(context).getString(KEY_LOG, null)).asReversed()

    /**
     * Whether the stored history still adds up (TR-3).
     *
     * Each entry is chained to the one before it, so an edit made outside this app
     * — a text editor on a rooted phone, a restored backup, a corrupted write —
     * breaks every link after it. The screen shows the result, because a history
     * whose integrity is never checked is one nobody has any reason to believe.
     */
    fun check(context: Context): ChainCheck {
        val prefs = prefs(context)
        return AuditChain.verify(
            entries = AuditLogFormat.decodeAll(prefs.getString(KEY_LOG, null)),
            anchor = prefs.getString(KEY_ANCHOR, null) ?: AuditChain.GENESIS,
        )
    }

    fun minutes(value: Int): String = value.toString()

    fun flag(value: Boolean): String = value.toString()

    fun timeRange(start: LocalTime, end: LocalTime): String = "$start-$end"

    private const val FILE_NAME = "audit"
    private const val KEY_LOG = "log"

    /**
     * The hash the chain continues from.
     *
     * Not always the genesis value: the log is capped, so once old entries age out
     * the chain resumes from the hash of the last one dropped. Keeping it is what
     * stops an ordinary trim looking exactly like a deleted first page.
     */
    private const val KEY_ANCHOR = "anchor"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
