package dev.schrammel.protego_parentis.audit

import android.content.Context
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.StreamReplication
import dev.schrammel.protego_parentis.sync.SyncConfigStore
import java.time.Instant

/**
 * The shared history, replicated so that both sides genuinely see the same one.
 *
 * This is the section that most needed replicating and was most awkward before:
 * the promise has always been that a child can read every rule change and every
 * answer, but each phone only ever held the entries it happened to witness. Two
 * devices could show different histories of the same family and neither was
 * wrong.
 *
 * Write-once, enforced on receipt by [ReplicationRules.isAppendOnly]. A log a
 * parent could quietly revise is not a log the child has any reason to believe,
 * and under replication that has to be a rule rather than an assumption — an op
 * is an op, and nothing else would stop a second write to the same path.
 */
class AuditReplication(context: Context) {

    private val appContext = context.applicationContext
    private val stream = StreamReplication(appContext)

    /**
     * Records one entry.
     *
     * [subject] carries app labels, category names and rule names — this log is
     * for the family, not for a support ticket, and it is deliberately not the
     * content-free diagnostic log. It is sealed like everything else before it
     * leaves the phone.
     *
     * Carries [previousHash] and [hash] alongside it (#84) — the same two
     * fields [Audit]'s own local chain already computed for this entry before
     * calling here. A replica cannot recompute these after the fact without
     * the *actual* previous entry to hash against, so carrying them is what
     * lets another phone verify this device's own history rather than merely
     * display it. Blank rather than omitted is a caller's choice to record
     * without them — kept absent from the wire the same way
     * [subject]/[before]/[after] are, so a receiving phone on an older build
     * sees exactly what it would have seen before this existed.
     *
     * [entryId] and [atMillis] travel for the same reason, and were the first
     * attempt's undoing: `AuditChain.link` hashes the recording device's own
     * entry id and its timestamp *in milliseconds*, while this stream has its
     * own append id and stamps entries in whole seconds. Rebuilding the entry
     * from those two on the far side produced a different hash every time, so
     * a perfectly honest chain never verified. The receiver needs the exact
     * values that were hashed, not the stream's approximations of them.
     */
    fun record(
        actor: String,
        action: String,
        subject: String?,
        before: String?,
        after: String?,
        previousHash: String,
        hash: String,
        entryId: String,
        atMillis: Long,
    ): String? = stream.append(
        section = ReplicationRules.AUDIT,
        fields = buildMap {
            put(FIELD_ACTOR, actor)
            put(FIELD_ACTION, action)
            // Absent rather than empty, so an entry with nothing to say about a
            // value costs no register at all.
            subject?.takeIf(String::isNotBlank)?.let { put(FIELD_SUBJECT, it) }
            before?.takeIf(String::isNotBlank)?.let { put(FIELD_BEFORE, it) }
            after?.takeIf(String::isNotBlank)?.let { put(FIELD_AFTER, it) }
            put(FIELD_DEVICE, deviceName())
            previousHash.takeIf(String::isNotBlank)?.let { put(FIELD_PREVIOUS_HASH, it) }
            hash.takeIf(String::isNotBlank)?.let { put(FIELD_HASH, it) }
            entryId.takeIf(String::isNotBlank)?.let { put(FIELD_ENTRY_ID, it) }
            atMillis.takeIf { it > 0L }?.let { put(FIELD_AT_MILLIS, it.toString()) }
        },
    )

    fun entries(): List<ReplicatedAuditEntry> =
        stream.entries(ReplicationRules.AUDIT).mapNotNull { entry ->
            ReplicatedAuditEntry(
                id = entry.id,
                at = Instant.ofEpochSecond(entry.createdAt),
                actor = entry.text(FIELD_ACTOR).orEmpty(),
                action = entry.text(FIELD_ACTION) ?: return@mapNotNull null,
                subject = entry.text(FIELD_SUBJECT),
                before = entry.text(FIELD_BEFORE),
                after = entry.text(FIELD_AFTER),
                device = entry.text(FIELD_DEVICE).orEmpty(),
                by = entry.by,
                // Absent on an entry replicated before #84 existed — never
                // treated as broken, only as not (yet) checkable; see
                // Audit.checkFamily.
                previousHash = entry.text(FIELD_PREVIOUS_HASH),
                hash = entry.text(FIELD_HASH),
                entryId = entry.text(FIELD_ENTRY_ID),
                atMillis = entry.text(FIELD_AT_MILLIS)?.toLongOrNull(),
            )
        }

    private fun deviceName(): String = SyncConfigStore(appContext).load().deviceName

    private companion object {
        const val FIELD_ACTOR = "actor"
        const val FIELD_ACTION = "action"
        const val FIELD_SUBJECT = "subject"
        const val FIELD_BEFORE = "before"
        const val FIELD_AFTER = "after"
        const val FIELD_DEVICE = "device"
        const val FIELD_PREVIOUS_HASH = "prev"
        const val FIELD_HASH = "hash"
        const val FIELD_ENTRY_ID = "eid"
        const val FIELD_AT_MILLIS = "atms"
    }
}

/** One line of the family's shared history, as replicated. */
data class ReplicatedAuditEntry(
    val id: String,
    val at: Instant,
    val actor: String,
    val action: String,
    val subject: String?,
    val before: String?,
    val after: String?,
    /** Which phone recorded it — the thing the old per-device logs could not say. */
    val device: String,
    /**
     * The recording device's id.
     *
     * Used to filter out this phone's own entries when merging with its local
     * chained log, which already holds them — see [Audit.entries].
     */
    val by: String,
    /**
     * This entry's place in the *recording* device's own chain (#84) — null on
     * an entry replicated before this field existed. See [Audit.checkFamily].
     */
    val previousHash: String? = null,
    val hash: String? = null,
    /**
     * The two fields the recording device's hash covers that this stream does
     * not otherwise preserve (#84): its own entry id — [id] above is the
     * stream's append id, a different value — and its timestamp in
     * milliseconds, where [at] has been rounded to whole seconds. Verifying a
     * replicated chain means recomputing the hash over exactly what was
     * hashed, so both travel verbatim; null on an entry replicated before
     * this existed.
     */
    val entryId: String? = null,
    val atMillis: Long? = null,
)
