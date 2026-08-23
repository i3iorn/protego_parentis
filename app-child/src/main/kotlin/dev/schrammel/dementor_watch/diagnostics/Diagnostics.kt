package dev.schrammel.dementor_watch.diagnostics

import android.content.Context
import androidx.core.content.edit
import java.time.Instant

/**
 * The things worth recording.
 *
 * A closed set rather than free-form messages, and that is the whole design. A
 * log built from format strings drifts, and sooner or later someone interpolates
 * a package name, a request reason or a prize into it — at which point the log
 * can no longer be shared with anyone.
 */
enum class DiagnosticEvent {
    SERVICE_STARTED,
    SERVICE_STOPPED,
    EVALUATED,

    /** Something threw during evaluation; the tick recovered and rescheduled. */
    EVALUATION_FAILED,

    USAGE_READ_FAILED,
    USAGE_ACCESS_MISSING,
    HISTORY_RECORDED,
    HISTORY_FAILED,

    DEVICE_OWNER_APPLIED,
    DEVICE_OWNER_MISSING,
    INVARIANT_VIOLATION,

    PIN_FAILED,
    PIN_CHANGED,

    SYNC_PUBLISHED,
    SYNC_PUBLISH_FAILED,
    SYNC_POLLED,
    SYNC_POLL_FAILED,

    /** A message decrypted but was refused — stale, duplicated, or a bad clock. */
    SYNC_MESSAGE_REFUSED,

    BOOT_COMPLETED,
}

/** One line of the log. */
data class DiagnosticEntry(
    val at: Instant,
    val event: DiagnosticEvent,
    /**
     * Numbers only, never text.
     *
     * Counts and durations describe what happened without describing *who* it
     * happened to. "12 apps considered, 3 suspended" is enough to diagnose the
     * class of bug we have actually hit; "suspended com.supercell.clashroyale"
     * would not be.
     */
    val counts: Map<String, Long> = emptyMap(),
)

/**
 * A local, shareable, deliberately content-free record of what the app did.
 *
 * This exists because a real bug — usage silently not counting — took a long time
 * to find with nothing to look at. It is built to a rule that the privacy goal
 * makes non-negotiable: **the log records what the app did, never what the child
 * did.**
 *
 * So it holds no package names, no app labels, no request reasons, no prize text,
 * no topic or passphrase, and no timestamps of individual app sessions. Only
 * event kinds, counts and durations.
 *
 * The consequence is deliberate: this log is safe for a child to read in full
 * before it is shared, and safe to paste to a stranger for help. A diagnostic log
 * that has to be trusted rather than read is one that will eventually leak
 * something it should not.
 */
object Diagnostics {

    private const val FILE_NAME = "diagnostics"
    private const val KEY_LOG = "log"

    fun record(
        context: Context,
        event: DiagnosticEvent,
        counts: Map<String, Long> = emptyMap(),
        at: Instant = Instant.now(),
    ) {
        val prefs = prefs(context)
        val updated = DiagnosticLogFormat.append(
            raw = prefs.getString(KEY_LOG, null),
            entry = DiagnosticEntry(at, event, counts),
        )
        prefs.edit { putString(KEY_LOG, updated) }
    }

    fun entries(context: Context): List<DiagnosticEntry> =
        DiagnosticLogFormat.decodeAll(prefs(context).getString(KEY_LOG, null))

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_LOG) }
    }

    /**
     * The log as text, for reading on screen or sharing.
     *
     * Prefixed with a statement of what it does and does not contain, because
     * anyone being asked to share a log deserves to know that before they do.
     */
    fun asText(context: Context, header: String, empty: String): String =
        DiagnosticLogFormat.render(entries(context), header, empty)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
