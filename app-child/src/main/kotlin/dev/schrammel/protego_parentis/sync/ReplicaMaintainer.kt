package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BUCKETS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_DIGEST
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_OPS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_POLICY_JSON
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateCodec
import dev.schrammel.protego_parentis.sync.core.StateDigest
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncRole
import java.time.Instant

/**
 * The half of replication that runs whether or not anything arrived.
 *
 * Extracted from [SyncReceiver] so the receiver keeps only the envelope pipeline
 * (reassemble → decrypt → admit → dispatch) and this class owns the three things
 * that happen after dispatch decides a state message is trustworthy: folding
 * inbound ops into the replica, reconciling local edits with the replica after
 * every poll, and answering digest comparisons and repair requests.
 *
 * [PolicyReplication] was constructed ten times across the methods that moved
 * here (five pushes in [keepReplicaCurrent], five pulls in [onStateChanged]);
 * one cached instance replaces all ten.
 */
internal class ReplicaMaintainer(
    private val appContext: Context,
    private val roles: RoleStore,
    private val publisher: SyncPublisher,
    private val state: ReplicatedStateStore,
    private val config: SyncConfigStore,
    private val chat: ChatInboundHandler,
    private val requestSync: RequestSyncHandler,
    private val telemetry: TelemetryInboundHandler,
    private val notifier: RequestNotifier,
) {
    // One instance replaces the ten constructions scattered across the methods
    // that moved here. PolicyReplication is stateless beyond its context, so
    // caching is safe.
    private val policy by lazy { PolicyReplication(appContext) }
    private val roster by lazy { RosterReplication(appContext) }

    /**
     * Runs at the end of every poll, on both roles. Three things, in order:
     * anything edited locally goes out (including edits made while offline,
     * which is why the queue is durable); the managed phone records its own
     * rules, so an edit made standing at it reaches the parents without being
     * asked for; and on a slow timer this device says what it holds, which is
     * how a replica that missed ops entirely ever finds out.
     */
    fun keepReplicaCurrent() {
        if (roles.roleOrChild == DeviceRole.CHILD) {
            policy.pushFromLocalPolicy()
            policy.pushSchedulesFromLocal()
            policy.pushContextRulesFromLocal()
            policy.pushPlaceRefsFromLocal()
            policy.pushAutoApprovalsFromLocal()
        } else {
            // Only a parent may write the roster, and every parent holds the
            // same one — so each publishes what it trusts and they converge,
            // rather than a vouch having to be timed to land after an approval.
            roster.push()
            // Every parent's device runs this independently and lands on the
            // same outcome — see ConsensusReplication.commitIfReady. Only a
            // parent has anything to check: the managed phone is not a party
            // to any of the four decisions.
            commitReadyConsensusProposals()
        }
        publisher.publishPendingOps()

        val now = Instant.now()
        if (config.digestDue(now)) {
            // Pruned first, so the digest published reflects what this replica
            // will actually still be holding when somebody compares against it.
            state.prune(now.epochSecond)
            publisher.publishDigest()
            config.recordDigestAt(now)
        }
    }

    /**
     * Commits every consensus proposal this replica can see has reached its
     * threshold — unanimous approval, or an uncancelled timer.
     *
     * Every parent's device does this independently, and that is by design:
     * see [ConsensusReplication.commitIfReady] for why more than one device
     * acting on the same proposal is harmless.
     *
     * Stops the moment a disband commits: it wipes this phone's own family
     * membership, so nothing left in the list is still this replica's to act
     * on.
     */
    private fun commitReadyConsensusProposals() {
        val consensus = ConsensusReplication(appContext)
        for (proposal in consensus.proposals()) {
            val committed = consensus.commitIfReady(proposal, publisher)
            if (committed && proposal.kind == ConsensusKind.DISBAND_FAMILY) return
        }
    }

    /**
     * Folds a batch of writes from another device into this replica.
     *
     * Every op is checked individually against [ReplicationRules] using the role
     * the *roster* established for the sender, never what the message claimed.
     * That check is the whole trust boundary of symmetric replication: a managed
     * phone holding the family passphrase can sign a policy op perfectly well,
     * and it is refused here.
     *
     * A refused op is dropped silently and locally. There is no reply, because
     * there is nothing useful to say to a device that should not have asked —
     * it will see its value disagree at the next digest, and be unable to do
     * anything about it, which is the intended outcome.
     */
    fun applyStateOps(message: SyncMessage, senderRole: SyncRole?, senderId: DeviceId): Boolean {
        val incoming = StateCodec.decodeOps(message.field(FIELD_OPS))
        if (incoming.isEmpty()) return false

        val (authorised, unauthorised) = incoming.partition {
            ReplicationRules.accepts(senderRole, it, senderId)
        }
        val (allowed, overCapacity) = withinCapacity(authorised)
        val refused = unauthorised + overCapacity
        if (refused.isNotEmpty()) {
            Diagnostics.record(
                appContext,
                DiagnosticEvent.STATE_OPS_REFUSED,
                mapOf("count" to refused.size.toLong()),
            )
        }

        val applied = state.receive(allowed)
        if (applied.isNotEmpty()) onStateChanged(applied)
        return applied.isNotEmpty()
    }

    /**
     * Drops writes that would grow a section past what it may hold.
     *
     * Only writes that create a *new* key are capped. A section at its limit
     * still accepts updates to keys it already has, so it goes on converging
     * rather than freezing part-way through a disagreement — which would be a
     * worse failure than the one being defended against.
     *
     * The defence is against a device in the family inventing keys until every
     * other replica runs out of room. Enrolment already keeps strangers out;
     * this bounds the damage a phone that got in can do.
     */
    private fun withinCapacity(ops: List<StateOp>): Pair<List<StateOp>, List<StateOp>> {
        val held = state.entries
        val counts = held.keys
            .groupingBy { it.substringBefore(ReplicationRules.SEPARATOR) }
            .eachCount()
            .toMutableMap()

        val allowed = mutableListOf<StateOp>()
        val refused = mutableListOf<StateOp>()
        ops.forEach { op ->
            if (op.path in held) {
                allowed += op
                return@forEach
            }
            val section = op.path.substringBefore(ReplicationRules.SEPARATOR)
            val count = counts[section] ?: 0
            if (count >= ReplicationRules.capacityOf(op.path)) {
                refused += op
            } else {
                counts[section] = count + 1
                allowed += op
            }
        }
        return allowed to refused
    }

    /**
     * Compares somebody else's summary with ours and asks for what is missing.
     *
     * This is what makes the protocol self-healing rather than dependent on the
     * relay keeping every op forever. Both sides do it, so whichever is behind
     * asks — and the one that is ahead simply finds nothing to ask for.
     */
    fun compareDigest(message: SyncMessage): Boolean {
        val theirs = StateCodec.decodeDigest(message.field(FIELD_DIGEST))
        if (theirs.isEmpty()) return false

        val differing = StateDigest.differing(state.digest(), theirs)
        if (differing.isEmpty()) return false

        // Asking is enough: the answer arrives as ordinary ops, and this device
        // publishes its own side of the difference in the same exchange so the
        // other replica converges too rather than only this one.
        publisher.publishRepairRequest(differing)
        // Batched by size, like every other send. A bucket set holding a busy
        // conversation is far more than one message can carry, and an over-long
        // one is dropped by the receiver rather than truncated — so sending it
        // whole meant the repair silently accomplished nothing.
        StateCodec.batch(state.entriesIn(differing)).forEach(publisher::publishOps)
        Diagnostics.record(
            appContext,
            DiagnosticEvent.STATE_REPAIR_REQUESTED,
            mapOf("buckets" to differing.size.toLong()),
        )
        return true
    }

    /** Answers a repair request with everything held in the buckets asked for. */
    fun answerRepair(message: SyncMessage): Boolean {
        val buckets = StateCodec.decodeBuckets(message.field(FIELD_BUCKETS))
        if (buckets.isEmpty()) return false

        val entries = state.entriesIn(buckets)
        if (entries.isEmpty()) return false
        StateCodec.batch(entries).forEach(publisher::publishOps)
        return true
    }

    /**
     * Re-applies anything downstream of state that just changed.
     *
     * Enforcement is restarted only when the rules themselves moved — a chat
     * message or another device's telemetry arriving should not cost a policy
     * re-evaluation on the managed phone.
     */
    private fun onStateChanged(applied: List<StateOp>) {
        val sections = applied.mapTo(mutableSetOf()) {
            it.path.substringBefore(ReplicationRules.SEPARATOR)
        }

        if (ReplicationRules.POLICY in sections && roles.roleOrChild == DeviceRole.CHILD) {
            policy.pullIntoLocalPolicy()
            policy.pullSchedulesIntoLocal()
            policy.pullContextRulesIntoLocal()
            policy.pullPlaceRefsIntoLocal()
            policy.pullAutoApprovalsIntoLocal()
            AppRegistry.ruleMutationSink.rulesChanged()
            // A child's own local edit never reaches here: LocalPolicyRuleStore
            // (and its schedule/context-rule/place/auto-approval equivalents)
            // write straight to disk with no op pushed, so this branch only
            // ever runs for a change that genuinely came from somewhere else —
            // a parent's phone, editing remotely.
            notifier.notifyRuleChanged()
        }
        // Additive only, and never for the managed phone: see the doc comment
        // on pullIntoRoster for why trust must fail closed here.
        if (ReplicationRules.ROSTER in sections) {
            roster.pullIntoRoster()
        }
        if (ReplicationRules.CHAT in sections) {
            chat.announceNewChat(applied)
        }
        if (ReplicationRules.REQUESTS in sections && roles.roleOrChild == DeviceRole.CHILD) {
            requestSync.applyAnsweredRequests()
        }
        // "As of" on a parent's dashboard means "when I last heard", and this is
        // that moment. Recorded here rather than derived from the ops, which
        // carry a logical clock and no wall-clock time at all.
        if (ReplicationRules.TELEMETRY in sections && roles.roleOrChild == DeviceRole.PARENT) {
            telemetry.markSummaryHeard()
        }
    }

    /**
     * Takes a whole-policy answer from a phone still on the older protocol.
     *
     * Folded into the replica rather than kept beside it, so a family part-way
     * through updating still converges: the older phone speaks whole snapshots,
     * this one turns each into ops, and every newer device in the family sees
     * the rules arrive the way it expects.
     */
    fun rememberPolicySnapshot(message: SyncMessage): Boolean {
        val snapshot = PolicySnapshot.decode(message.field(FIELD_POLICY_JSON)) ?: return false
        return policy.write(snapshot).isNotEmpty()
    }
}
