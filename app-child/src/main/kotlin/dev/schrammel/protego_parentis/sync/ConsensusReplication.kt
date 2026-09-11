package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.SyncRole
import java.time.Instant
import java.util.UUID

/**
 * The fixed set of family decisions that need every parent's agreement, or a
 * timer any parent can stop.
 *
 * A single parent can change most things about a family unilaterally — that
 * asymmetry is the whole point of [ReplicationRules.POLICY] being writable by
 * either role. These four are different: they change who is *in* the family
 * or whether it exists at all, and a single compromised or careless parent
 * phone deciding one of those alone is a bigger risk than the convenience is
 * worth.
 *
 * A proposal is its own record, at `consensus/<id>/…`, not a write to
 * whatever it would eventually change — see [ReplicationRules.CONSENSUS].
 * The target keeps enforcing its old state until a proposal actually
 * commits.
 *
 * Three kinds are held until every current parent has approved
 * ([approve]); the fourth, removing a parent, runs on a timer any current
 * parent may stop ([cancel]) — see [ConsensusKind.REMOVE_PARENT] for why
 * that one is not unanimity at all.
 */
class ConsensusReplication(context: Context) {

    private val appContext = context.applicationContext
    private val state = ReplicatedStateStore(appContext)
    private val roster = RosterStore(appContext)

    private fun role(): SyncRole = RoleStore(appContext).roleOrChild.toSyncRole()

    /**
     * Raises a proposal of [kind] naming [target], returning its id.
     *
     * Refused by the write-time gate, and so returns null, unless this device
     * is a parent — [ConsensusReplication] never checks that itself, the same
     * way [PolicyReplication] and [RosterReplication] leave authorisation to
     * [ReplicatedStateStore.writeAll].
     *
     * [timerSeconds] matters only for [ConsensusKind.REMOVE_PARENT] — how long
     * before the removal executes if nobody cancels it. Ignored for every
     * other kind, which has no timer at all.
     */
    fun propose(
        kind: ConsensusKind,
        target: DeviceId,
        now: Instant = Instant.now(),
        timerSeconds: Long = DEFAULT_REMOVE_PARENT_TIMER_SECONDS,
    ): String? {
        val origin = LocalDeviceId.current() ?: return null
        val id = ReplicationRules.streamId(
            seconds = now.epochSecond,
            unique = UUID.randomUUID().toString().take(ID_TAIL),
        )
        val root = path(id)
        val values = buildMap<String, String?> {
            put("$root/$FIELD_KIND", kind.wireName)
            put("$root/$FIELD_TARGET", target.value)
            put("$root/$FIELD_PROPOSED_BY", origin)
            put("$root/$FIELD_PROPOSED_AT", now.epochSecond.toString())
            if (kind == ConsensusKind.REMOVE_PARENT) {
                put("$root/$FIELD_EXECUTES_AT", (now.epochSecond + timerSeconds).toString())
            }
        }
        val ops = state.writeAll(values, origin, role())
        return id.takeIf { ops.isNotEmpty() }
    }

    /**
     * Approves [proposalId] as this device.
     *
     * Self-attestation only — see [ReplicationRules.mayWriteConsensusField].
     * There is no way to approve on another parent's behalf, by design: this
     * always writes this device's own id, never one passed in by a caller.
     */
    fun approve(proposalId: String): Boolean {
        val origin = LocalDeviceId.current() ?: return false
        val approvalPath = "${path(proposalId)}/$FIELD_APPROVALS/$origin"
        return state.writeAll(mapOf(approvalPath to APPROVED), origin, role()).isNotEmpty()
    }

    /**
     * Stops a [ConsensusKind.REMOVE_PARENT] proposal's timer.
     *
     * Any current parent may call this, including the parent being removed —
     * that is deliberate, see [ConsensusKind.REMOVE_PARENT].
     */
    fun cancel(proposalId: String): Boolean {
        val origin = LocalDeviceId.current() ?: return false
        return state.writeAll(mapOf("${path(proposalId)}/$FIELD_CANCELLED" to APPROVED), origin, role())
            .isNotEmpty()
    }

    /** Every proposal this replica currently holds, resolved or not. */
    fun proposals(): List<ConsensusProposal> =
        state.idsUnder(ReplicationRules.CONSENSUS).mapNotNull(::proposal)

    /** One proposal, or null if this replica has never heard of it, or it is malformed. */
    fun proposal(id: String): ConsensusProposal? {
        val root = path(id)
        val fields = state.children(root)
        val kind = ConsensusKind.parse(fields[FIELD_KIND]) ?: return null
        val target = fields[FIELD_TARGET]?.let(::DeviceId) ?: return null
        val proposedBy = fields[FIELD_PROPOSED_BY]?.let(::DeviceId) ?: return null
        val proposedAt = fields[FIELD_PROPOSED_AT]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        val executesAt = fields[FIELD_EXECUTES_AT]?.toLongOrNull()?.let(Instant::ofEpochSecond)
        val cancelled = fields[FIELD_CANCELLED] == APPROVED
        val approvals = state.children("$root/$FIELD_APPROVALS").keys.map(::DeviceId).toSet()
        return ConsensusProposal(id, kind, target, proposedBy, proposedAt, executesAt, cancelled, approvals)
    }

    /**
     * Whether [proposal] has every current parent's approval.
     *
     * Checked against the roster's *current* parents rather than a count
     * carried on the proposal itself — a proposal made while the family had
     * three parents must not still read as unanimous with only two of them
     * having ever approved, if the family gained a fourth in the meantime.
     * Not meaningful for [ConsensusKind.REMOVE_PARENT], which has no
     * approvals at all; always false for it.
     */
    fun isApproved(proposal: ConsensusProposal): Boolean {
        if (proposal.kind == ConsensusKind.REMOVE_PARENT) return false
        val currentParents = roster.parents().map { it.id }.toSet()
        if (currentParents.isEmpty()) return false
        return proposal.approvals.containsAll(currentParents)
    }

    /** Whether a [ConsensusKind.REMOVE_PARENT] proposal's timer has elapsed, uncancelled. */
    fun isDue(proposal: ConsensusProposal, now: Instant = Instant.now()): Boolean {
        if (proposal.kind != ConsensusKind.REMOVE_PARENT) return false
        if (proposal.cancelled) return false
        val executesAt = proposal.executesAt ?: return false
        return !now.isBefore(executesAt)
    }

    /**
     * Performs whatever [proposal] decided, if it is ready, and tombstones it.
     *
     * Idempotent, and safe for more than one device to call at once: the
     * underlying actions — [RosterStore.revoke], [RosterStore.approve],
     * leaving the family — are themselves idempotent or already tolerant of
     * running twice, so two devices independently noticing the same threshold
     * and both committing land on the same end state. Whichever device's
     * tombstone write loses the merge changes nothing; the effect it guarded
     * already happened everywhere that mattered.
     *
     * [ConsensusKind.ENROL_CHILD] does not fit that story and is handled
     * separately, below — it is the one kind where "the effect it guarded"
     * is not global. See its own branch.
     */
    fun commitIfReady(proposal: ConsensusProposal, publisher: SyncPublisher, now: Instant = Instant.now()): Boolean {
        val ready = if (proposal.kind == ConsensusKind.REMOVE_PARENT) isDue(proposal, now) else isApproved(proposal)
        if (!ready) return false

        when (proposal.kind) {
            ConsensusKind.DISBAND_FAMILY -> {
                publisher.publishDisband()
                publisher.leave()
            }

            ConsensusKind.REMOVE_PARENT -> {
                val target = roster.devices.firstOrNull { it.id == proposal.target } ?: return tombstone(proposal.id)
                roster.revoke(target.id)
                if (Enrolment.shouldAcceptRevoke(target.role)) publisher.publishRevoke(target)
            }

            ConsensusKind.VOUCH_PARENT -> {
                val pending = roster.pending.firstOrNull { it.id == proposal.target } ?: return tombstone(proposal.id)
                val approved = roster.approve(pending.id, pending.name) ?: return tombstone(proposal.id)
                publisher.publishVouch(approved)
            }

            ConsensusKind.ENROL_CHILD -> return commitEnrolChildIfReady(proposal)
        }
        return tombstone(proposal.id)
    }

    /**
     * [ConsensusKind.ENROL_CHILD]'s own commit, kept apart from the others
     * above because unanimous *approval* is not the same fact as unanimous
     * *enrolment*, and only the second one is safe to tombstone on.
     *
     * There is no broadcast that could tell one parent's device what another
     * parent's device did locally — unlike [ConsensusKind.VOUCH_PARENT],
     * which reaches every device via [SyncPublisher.publishVouch] regardless
     * of local state, a second managed phone can never be vouched in (see
     * [Enrolment.shouldAccept]). So every parent has to independently run
     * [RosterStore.approve] for itself, using its own local [RosterStore.pending]
     * — populated the same way any pending device's is, from the candidate's
     * own `DEVICE_ANNOUNCE` — and record having done so in
     * `enrolled/<thisParent>`, self-attested the same way `approvals` is (see
     * [ReplicationRules.mayWriteConsensusField]).
     *
     * If this device has neither the target already known nor pending yet,
     * there is nothing to do on this pass — but that must never look like
     * "done": the proposal is left exactly as it is, so a slower parent still
     * gets a later pass once its own pending list has caught up. Tombstoning
     * here happens only once every *current* parent's `enrolled` register is
     * set — the same "current, not proposal-time" parent set [isApproved]
     * already uses, for the same reason a family that gained a parent
     * mid-proposal must not read as already unanimous on a shorter, stale
     * list.
     *
     * Checking [RosterStore.devices] first, not only [RosterStore.pending],
     * matters: once this device has enrolled the target on an earlier pass,
     * [RosterStore.approve] has already moved it out of `pending` — this
     * device's own enrolment is still real and still needs recording, it is
     * just no longer visible the same way a first pass would see it.
     */
    private fun commitEnrolChildIfReady(proposal: ConsensusProposal): Boolean {
        val origin = LocalDeviceId.current() ?: return false
        val alreadyKnown = roster.devices.any { it.id == proposal.target }
        if (!alreadyKnown) {
            val pending = roster.pending.firstOrNull { it.id == proposal.target } ?: return false
            roster.approve(pending.id, pending.name)
        }
        state.writeAll(
            mapOf("${path(proposal.id)}/$FIELD_ENROLLED/$origin" to APPROVED),
            origin,
            role(),
        )
        val enrolled = state.children("${path(proposal.id)}/$FIELD_ENROLLED").keys.map(::DeviceId).toSet()
        val currentParents = roster.parents().map { it.id }.toSet()
        if (currentParents.isEmpty() || !enrolled.containsAll(currentParents)) return false
        return tombstone(proposal.id)
    }

    /** Clears a proposal's whole subtree, including every approval on it. */
    private fun tombstone(id: String): Boolean {
        val origin = LocalDeviceId.current() ?: return false
        val root = path(id)
        val toClear = state.entries.keys.filter { it.startsWith("$root/") }
        if (toClear.isEmpty()) return true
        state.writeAll(toClear.associateWith { null }, origin, role())
        return true
    }

    private fun path(id: String) = "${ReplicationRules.CONSENSUS}${ReplicationRules.SEPARATOR}$id"

    companion object {
        /** Three days, as decided on #37 — configurable per family, not baked into provisioning. */
        const val DEFAULT_REMOVE_PARENT_TIMER_SECONDS = 3L * 24 * 60 * 60

        private const val ID_TAIL = 6
        private const val APPROVED = "1"

        const val FIELD_KIND = "kind"
        const val FIELD_TARGET = "target"
        const val FIELD_PROPOSED_BY = "proposedBy"
        const val FIELD_PROPOSED_AT = "proposedAt"
        const val FIELD_EXECUTES_AT = "executesAt"
        const val FIELD_CANCELLED = "cancelled"
        val FIELD_APPROVALS = ReplicationRules.FIELD_APPROVALS
        val FIELD_ENROLLED = ReplicationRules.FIELD_ENROLLED
    }
}

/**
 * The four decisions this family will not make on one parent's say-so alone
 * — or, for [REMOVE_PARENT], not without giving every parent a chance to say
 * no.
 */
enum class ConsensusKind(val wireName: String) {
    /** Ends the family. Held until every current parent has approved. */
    DISBAND_FAMILY("disband_family"),

    /**
     * Removes a parent whose device has gone quiet — lost, discarded, never
     * coming back — with nobody there to approve or contest it.
     *
     * Deliberately not unanimity: a parent who disagrees and is still present
     * can always refuse to approve everything else, and the family's way out
     * of a genuinely adversarial parent is to start a new family, not this.
     * This exists only for the absent-device case, so it runs on a timer any
     * current parent may stop instead — including the parent it names, since
     * until the timer fires they are still a current parent and a "lost"
     * phone that turns out not to be lost should be able to cancel its own
     * removal like anyone else's.
     */
    REMOVE_PARENT("remove_parent"),

    /** Admits a new parent device. Held until every current parent has approved. */
    VOUCH_PARENT("vouch_parent"),

    /**
     * Admits a second managed device into the family.
     *
     * Held/unanimous, on top of the physical-possession requirement
     * [Enrolment.shouldAccept] still enforces, not instead of it: that
     * function refuses every non-parent vouch unconditionally, and nothing
     * about this proposal kind ever routes around it — a second managed
     * phone is never vouched in. What it *does* replace is a single parent
     * unilaterally deciding to trust one, which is what [Enrolment.mayApprove]
     * and [RosterStore.approve] alone would otherwise allow for any pending
     * device regardless of its claimed role.
     *
     * A parent proposes this only once the candidate is in its own local
     * [RosterStore.pending] — i.e. it has physically compared the fingerprint
     * on this phone. Committing needs every current parent to independently
     * do the same on their own device, since there is no broadcast that could
     * carry the result the way [SyncPublisher.publishVouch] does for
     * [VOUCH_PARENT] — see [ConsensusReplication.commitIfReady]'s own
     * `ENROL_CHILD` handling for exactly how that unanimity is tracked
     * separately from unanimous *approval*.
     */
    ENROL_CHILD("enrol_child"),
    ;

    companion object {
        fun parse(value: String?): ConsensusKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** One proposal, as every parent's device sees it. */
data class ConsensusProposal(
    val id: String,
    val kind: ConsensusKind,
    val target: DeviceId,
    val proposedBy: DeviceId,
    val proposedAt: Instant,
    /** Only set for [ConsensusKind.REMOVE_PARENT]. */
    val executesAt: Instant?,
    /** Only meaningful for [ConsensusKind.REMOVE_PARENT]. */
    val cancelled: Boolean,
    /** Only meaningful for the three held kinds. */
    val approvals: Set<DeviceId>,
)
