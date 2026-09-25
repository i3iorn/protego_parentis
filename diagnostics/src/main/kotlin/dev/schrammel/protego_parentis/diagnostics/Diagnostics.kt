package dev.schrammel.protego_parentis.diagnostics

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.schrammel.protego_parentis.diagnostics.Diagnostics.asText
import dev.schrammel.protego_parentis.diagnostics.Diagnostics.clear
import dev.schrammel.protego_parentis.diagnostics.Diagnostics.lock
import dev.schrammel.protego_parentis.diagnostics.Diagnostics.prefs
import dev.schrammel.protego_parentis.diagnostics.Diagnostics.record
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

    /** A managed-configuration push to an app failed — never which one. */
    MANAGED_CONFIG_FAILED,

    PIN_FAILED,
    PIN_CHANGED,

    SYNC_PUBLISHED,
    SYNC_PUBLISH_FAILED,
    SYNC_POLLED,
    SYNC_POLL_FAILED,

    /** The realtime stream dropped or could not be opened; it will retry. */
    SYNC_STREAM_FAILED,

    /**
     * The family key could not be derived from the stored passphrase and topic.
     * Distinct from [SYNC_PUBLISH_FAILED] / [SYNC_POLL_FAILED] on purpose: those
     * mean the network was reached and refused or was unreachable, this means the
     * attempt never left the phone at all — almost always a corrupted or
     * unreadable passphrase rather than a connectivity problem.
     */
    SYNC_KEY_UNAVAILABLE,

    /**
     * A parent's rule push named a rules version different from this phone's
     * current one — something changed here since the parent's copy was taken,
     * so the push was refused rather than silently discarding it.
     */
    POLICY_UPDATE_CONFLICT,

    /**
     * A device tried to write somewhere its role does not permit — most
     * importantly, the managed phone attempting to change its own rules. Counted
     * rather than described: which path was refused would say what the family
     * has set up, and this log is meant to be safe to hand to a stranger.
     */
    STATE_OPS_REFUSED,

    /** This replica found itself behind and asked for the difference. */
    STATE_REPAIR_REQUESTED,

    /** A message decrypted but was refused — stale, duplicated, or a bad clock. */
    SYNC_MESSAGE_REFUSED,

    /** Signed by a device that is not enrolled, or not permitted to say that. */
    SYNC_SENDER_REFUSED,

    /** A correctly signed stranger asked to join the family. */
    SYNC_DEVICE_PENDING,

    /**
     * The seen-set hit its ceiling and dropped ids that were still replayable.
     *
     * Not an ordinary event. It means more messages arrived inside one freshness
     * window than the store will hold, which real family traffic does not reach —
     * so it is either a fault or somebody deliberately filling the set to push a
     * captured message back out of it.
     */
    REPLAY_MEMORY_OVERFLOWED,

    /** A screen asked for something in the background and did not get it. */
    SCREEN_LOAD_FAILED,

    BOOT_COMPLETED,

    /**
     * Something escaped every narrower `runCatching`, all the way to the
     * process's default uncaught-exception handler. Recorded before the app
     * restarts itself — see [dev.schrammel.protego_parentis.diagnostics.CrashRecovery].
     */
    APP_CRASHED,

    /**
     * Something threw while applying a received sync message — malformed or
     * version-skewed content this device does not control, not a bug in the
     * message itself reaching this phone. Unlike [APP_CRASHED], this is
     * recoverable: caught before the process's crash handler, one message
     * refused rather than the app going down.
     */
    SYNC_DISPATCH_FAILED,

    /** Fetching `update.json` failed — offline, or the release feed unreachable. */
    UPDATE_CHECK_FAILED,

    /** A newer release's APK failed to download, or failed checksum verification. */
    UPDATE_DOWNLOAD_FAILED,

    /** A verified update APK was handed to the platform installer, but it refused it. */
    UPDATE_INSTALL_FAILED,

    /** A verified update finished installing. */
    UPDATE_INSTALLED,
}

/**
 * One line of the log.
 *
 * Internal: [Diagnostics.asText] already turns these into the one thing a
 * caller outside this module needs — a shareable [String] — so nothing
 * external currently needs the structured line itself.
 */
internal data class DiagnosticEntry(
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

    /**
     * The log, decoded once and kept in memory.
     *
     * [record] fires on genuinely hot paths — every publish, every poll, every
     * tick — and appending used to mean decoding the whole stored log and
     * re-encoding all of it for one line. The decoded list is cached under
     * [lock], keyed on the stored string it was decoded from, so anything that
     * changes the file (a [clear], a fresh preference file in a Robolectric
     * test) is seen on the next read rather than served stale. The file write
     * itself stays as it was (an [android.content.SharedPreferences] edit of
     * the full encoded log), because that is the cheap half.
     */
    private val lock = Any()
    private var cached: Pair<String?, List<DiagnosticEntry>>? = null

    fun record(
        context: Context,
        event: DiagnosticEvent,
        counts: Map<String, Long> = emptyMap(),
        at: Instant = Instant.now(),
    ) {
        val prefs = prefs(context)
        synchronized(lock) {
            val entries = (load(prefs) + DiagnosticEntry(at, event, counts))
                .takeLast(DiagnosticLogFormat.MAX_ENTRIES)
            val encoded = DiagnosticLogFormat.encodeAll(entries)
            prefs.edit { putString(KEY_LOG, encoded) }
            cached = encoded to entries
        }
    }

    internal fun entries(context: Context): List<DiagnosticEntry> {
        val prefs = prefs(context)
        synchronized(lock) { return load(prefs) }
    }

    /** Callers hold [lock]. */
    private fun load(prefs: SharedPreferences): List<DiagnosticEntry> {
        val raw = prefs.getString(KEY_LOG, null)
        // Identity first: SharedPreferences hands back the instance it was
        // given, so the common case never compares the whole log.
        cached?.let { (from, entries) -> if (from === raw || from == raw) return entries }
        return DiagnosticLogFormat.decodeAll(raw).also { cached = raw to it }
    }

    fun clear(context: Context) {
        val prefs = prefs(context)
        synchronized(lock) {
            prefs.edit { remove(KEY_LOG) }
        }
    }

    /**
     * The log as text, for reading on screen or sharing.
     *
     * Prefixed with a statement of what it does and does not contain, because
     * anyone being asked to share a log deserves to know that before they do.
     */
    fun asText(context: Context, header: String, empty: String): String =
        DiagnosticLogFormat.render(entries(context), header, empty)

    /**
     * This phone's own log, in its wire encoding — for carrying a copy
     * elsewhere (a parent's phone, over sync) rather than reading it here.
     * Exactly [prefs]' own stored value: nothing is re-derived, so a copy
     * taken this way and one read back with [asText] can never disagree.
     */
    fun raw(context: Context): String = prefs(context).getString(KEY_LOG, null).orEmpty()

    /**
     * Renders a copy of the log carried in from elsewhere — see [raw] — the
     * same way [asText] renders this phone's own. A malformed or truncated
     * [raw] decodes whatever lines it can rather than failing outright, the
     * same fail-soft [DiagnosticLogFormat.decodeAll] already gives this
     * phone's own storage.
     */
    fun asText(raw: String, header: String, empty: String): String =
        DiagnosticLogFormat.render(DiagnosticLogFormat.decodeAll(raw), header, empty)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
