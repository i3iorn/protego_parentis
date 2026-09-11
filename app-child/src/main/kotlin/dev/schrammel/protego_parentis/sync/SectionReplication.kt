package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.ChatText
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import java.time.Instant

/**
 * Messages people typed, as replicated state.
 *
 * Two registers per message — who said it and what they said — under an id that
 * carries the second it was sent. Nothing else: no read receipts, no delivery
 * state, no edit history. A conversation is the one part of this app where the
 * content is the whole point, so everything around it stays as thin as it can.
 *
 * Both roles may write here, which is the point of a conversation, and the
 * ordinary path rules still apply — see [ReplicationRules.mayWrite].
 */
class ChatReplication(context: Context) {

    private val appContext = context.applicationContext
    private val stream = StreamReplication(appContext)

    /**
     * Records a message. Returns its id, or null if the text was not usable.
     *
     * [ChatText] does the refusing: length, emptiness and everything else it
     * already decides for the local store. Replication does not get its own
     * opinion about what a message may contain, because two opinions would
     * eventually disagree.
     */
    fun send(raw: String, senderName: String): String? {
        val text = ChatText.sanitise(raw) ?: return null
        return stream.append(
            section = ReplicationRules.CHAT,
            fields = mapOf(FIELD_TEXT to text, FIELD_FROM to senderName),
        )
    }

    /** The conversation, newest first. */
    fun messages(): List<ChatLine> = stream.entries(ReplicationRules.CHAT).mapNotNull { entry ->
        ChatLine(
            id = entry.id,
            at = Instant.ofEpochSecond(entry.createdAt),
            from = entry.text(FIELD_FROM).orEmpty(),
            by = entry.by,
            text = entry.text(FIELD_TEXT) ?: return@mapNotNull null,
        )
    }

    private companion object {
        const val FIELD_TEXT = "text"
        const val FIELD_FROM = "from"
    }
}

/** One line of the family conversation. */
data class ChatLine(
    val id: String,
    val at: Instant,
    /** The name the sender's phone went by, copied at the time it was sent. */
    val from: String,
    /** The sending device's id, so a reader can tell its own messages apart. */
    val by: String,
    val text: String,
)

/**
 * Asks for more time, and the answers to them.
 *
 * The one stream where the write rules go finer than a section. A request
 * carries both what was asked and what was decided, and the managed phone may
 * write only the first half — if it could write `state` it could approve itself,
 * which is the exact thing the whole negotiation exists to prevent. That is
 * enforced in [ReplicationRules.mayWriteRequestField], on the receiving side, so
 * it holds regardless of what a modified app chose to send.
 *
 * Replacing REQUEST_RAISED and REQUEST_DECIDED with registers also removes a
 * bug the old messages had: two parents answering at once each published a
 * decision, and whichever arrived last won on each phone independently, so the
 * family could disagree about the answer. Now both write the same register and
 * every replica resolves it the same way.
 */
class RequestReplication(context: Context) {

    private val appContext = context.applicationContext
    private val stream = StreamReplication(appContext)

    /** Raises a request. Returns its id. */
    fun raise(
        minutes: Int,
        reason: String,
        target: String,
        targetLabel: String,
        expiresAt: Instant,
        /**
         * The id the raising phone knows this request by.
         *
         * Carried so that phone can match an answer back to its own record: the
         * replicated entry has an id of its own, and without this the child
         * would receive a decision it could not attach to anything.
         */
        localId: String? = null,
    ): String? = stream.append(
        section = ReplicationRules.REQUESTS,
        fields = buildMap {
            localId?.let { put(FIELD_LOCAL_ID, it) }
            putAll(
                mapOf(
                    FIELD_MINUTES to minutes.toString(),
            FIELD_REASON to reason,
            FIELD_TARGET to target,
            FIELD_TARGET_LABEL to targetLabel,
                    FIELD_STATE to RequestState.PENDING.name,
                    FIELD_EXPIRES_AT to expiresAt.epochSecond.toString(),
                ),
            )
        },
    )

    /**
     * Answers one. Refused on the managed phone by every other replica, so this
     * is a parent-side call in practice even though nothing here says so.
     */
    fun decide(id: String, state: RequestState, note: String?, decidedBy: String): Boolean =
        stream.amend(
            section = ReplicationRules.REQUESTS,
            id = id,
            fields = buildMap {
                put(FIELD_STATE, state.name)
                put(FIELD_DECIDED_BY, decidedBy)
                put(FIELD_DECIDED_AT, Instant.now().epochSecond.toString())
                note?.takeIf(String::isNotBlank)?.let { put(FIELD_NOTE, it) }
            },
        )

    fun all(): List<ReplicatedRequest> =
        stream.entries(ReplicationRules.REQUESTS).mapNotNull { entry ->
            ReplicatedRequest(
                id = entry.id,
                at = Instant.ofEpochSecond(entry.createdAt),
                minutes = entry.number(FIELD_MINUTES)?.toInt() ?: return@mapNotNull null,
                reason = entry.text(FIELD_REASON).orEmpty(),
                target = entry.text(FIELD_TARGET).orEmpty(),
                targetLabel = entry.text(FIELD_TARGET_LABEL).orEmpty(),
                state = entry.text(FIELD_STATE)
                    ?.let { name -> RequestState.entries.firstOrNull { it.name == name } }
                    ?: RequestState.PENDING,
                note = entry.text(FIELD_NOTE),
                decidedBy = entry.text(FIELD_DECIDED_BY),
                decidedAt = entry.number(FIELD_DECIDED_AT)?.let(Instant::ofEpochSecond),
                // An older entry with no expiry gets one derived from when it was
                // raised, so it can still lapse rather than sitting open forever.
                expiresAt = entry.number(FIELD_EXPIRES_AT)?.let(Instant::ofEpochSecond)
                    ?: Instant.ofEpochSecond(entry.createdAt).plusSeconds(DEFAULT_LIFETIME_SECONDS),
                by = entry.by,
                localId = entry.text(FIELD_LOCAL_ID),
            )
        }

    fun pending(): List<ReplicatedRequest> = all().filter { it.state == RequestState.PENDING }

    private companion object {
        const val FIELD_MINUTES = "minutes"
        const val FIELD_REASON = "reason"
        const val FIELD_TARGET = "target"
        const val FIELD_TARGET_LABEL = "targetLabel"
        const val FIELD_STATE = "state"
        const val FIELD_NOTE = "note"
        const val FIELD_DECIDED_BY = "decidedBy"
        const val FIELD_DECIDED_AT = "decidedAt"
        const val FIELD_EXPIRES_AT = "expiresAt"
        const val FIELD_LOCAL_ID = "localId"

        /** Only for entries written before the expiry travelled with them. */
        const val DEFAULT_LIFETIME_SECONDS = 2L * 60 * 60
    }
}

/** One request, as every device in the family sees it. */
data class ReplicatedRequest(
    val id: String,
    val at: Instant,
    val minutes: Int,
    val reason: String,
    val target: String,
    val targetLabel: String,
    val state: RequestState,
    val note: String?,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val expiresAt: Instant,
    /** The device that raised it. */
    val by: String,
    /** What the raising phone calls it, so it can match an answer back. */
    val localId: String?,
) {
    fun isOpenAt(now: Instant): Boolean = state.isOpen && now.isBefore(expiresAt)
}

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
     * fields [dev.schrammel.protego_parentis.audit.Audit]'s own local chain
     * already computed for this entry before calling here. A replica cannot
     * recompute these after the fact without the *actual* previous entry to
     * hash against, so carrying them is what lets another phone verify this
     * device's own history rather than merely display it. Blank rather than
     * omitted is a caller's choice to record without them — kept absent from
     * the wire the same way [subject]/[before]/[after] are, so a receiving
     * phone on an older build sees exactly what it would have seen before
     * this existed.
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
     * chained log, which already holds them — see [dev.schrammel.protego_parentis.audit.Audit.entries].
     */
    val by: String,
    /**
     * This entry's place in the *recording* device's own chain (#84) — null on
     * an entry replicated before this field existed. See
     * [dev.schrammel.protego_parentis.audit.Audit.checkFamily].
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

/**
 * Who is in the family, as replicated state.
 *
 * Replaces the vouch-and-revoke messages with the thing they were emulating: a
 * shared list every device holds a copy of. A parent approving a phone writes
 * one entry; every other replica converges on it without a separate vouch being
 * timed correctly, which is what the old design needed a repeated re-vouch on
 * every screen open to paper over.
 *
 * Only parents may write here, unchanged from before — see
 * [ReplicationRules.mayWrite] and [Enrolment]. The managed phone still cannot
 * enrol anybody, and still cannot un-enrol itself remotely.
 *
 * The local [RosterStore] remains the authority for *this* device's trust
 * decisions, because it is what the message-admission path reads and it must
 * keep working with no network at all. This mirrors into it rather than
 * replacing it.
 */
class RosterReplication(context: Context) {

    private val appContext = context.applicationContext
    private val state = ReplicatedStateStore(appContext)
    private val roster = RosterStore(appContext)

    /** Records every device this phone trusts, so the rest of the family agrees. */
    fun push(): List<StateOp> {
        val origin = LocalDeviceId.current() ?: return emptyList()
        val known = roster.devices

        val values = buildMap<String, String?> {
            known.forEach { device ->
                val root = "${ReplicationRules.ROSTER}${ReplicationRules.SEPARATOR}${device.id.value}"
                put("$root/name", device.name)
                put("$root/role", device.role.name)
                put("$root/key", device.publicKey)
            }
            // A device this phone has revoked is tombstoned rather than dropped,
            // so the removal actually travels — see RosterStore.revoke and the
            // rule that a revoke may never name the managed phone.
            val live = known.map { it.id.value }.toSet()
            state.idsUnder(ReplicationRules.ROSTER)
                .filterNot { it in live }
                .forEach { gone ->
                    state.entries.keys
                        .filter { it.startsWith("${ReplicationRules.ROSTER}${ReplicationRules.SEPARATOR}$gone/") }
                        .forEach { put(it, null) }
                }
        }
        return state.writeAll(values, origin, RoleStore(appContext).roleOrChild.toSyncRole())
    }

    /**
     * Enrols anything the family has agreed on that this phone does not have.
     *
     * Deliberately additive. Removing a device from the local roster because the
     * replica lacks it would let an empty or lagging replica un-trust the whole
     * family, and trust is the one thing that must fail closed — a revoke is an
     * explicit local action, not the absence of a record.
     */
    fun pullIntoRoster() {
        val self = LocalDeviceId.current()
        val held = roster.devices.map { it.id.value }.toSet()

        state.idsUnder(ReplicationRules.ROSTER)
            .filterNot { it == self || it in held }
            .forEach { id ->
                val fields = state.children("${ReplicationRules.ROSTER}${ReplicationRules.SEPARATOR}$id")
                val vouch = Vouch(
                    id = DeviceId(id),
                    name = fields["name"].orEmpty(),
                    role = SyncRole.parse(fields["role"]) ?: return@forEach,
                    publicKey = fields["key"] ?: return@forEach,
                )
                // The same check a vouch message went through: a key that does
                // not match its id, or an entry naming the managed phone, is
                // refused here exactly as it was there.
                if (self != null && Enrolment.shouldAccept(vouch, DeviceId(self))) {
                    roster.enrol(vouch)
                }
            }
    }
}
