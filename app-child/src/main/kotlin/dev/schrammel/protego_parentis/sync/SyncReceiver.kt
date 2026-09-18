package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.KeystoreSigner
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APP_VERSION
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BUCKETS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_DIGEST
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_OPS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_POLICY_JSON
import java.time.Instant
import dev.schrammel.protego_parentis.sync.core.Admission
import dev.schrammel.protego_parentis.sync.core.Admittance
import dev.schrammel.protego_parentis.sync.core.DecryptResult
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.FragmentAssembler
import dev.schrammel.protego_parentis.sync.core.MessageCrypto
import dev.schrammel.protego_parentis.sync.core.MessageSignatures
import dev.schrammel.protego_parentis.sync.core.ReplayGuard
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.RosterRules
import dev.schrammel.protego_parentis.sync.core.StateCodec
import dev.schrammel.protego_parentis.sync.core.StateDigest
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.SyncCodec
import dev.schrammel.protego_parentis.sync.core.SyncKey
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncMessageType
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.TransportResult

/**
 * Everything a phone *receives*: opening an envelope, deciding whether to
 * believe it, and — the one branch in the whole sync layer — acting on it
 * differently depending on which side is reading it. A child phone acts on
 * answers; a parent phone collects questions. Each ignores the messages meant
 * for the other rather than trying to be useful with them.
 *
 * What happens once a message is believed is not this class's job beyond
 * choosing which of five collaborators does it — [DeviceTrustHandler],
 * [ChildCommandHandler], [RequestSyncHandler], [TelemetryInboundHandler], and
 * [ChatInboundHandler], one per message family. This class keeps only what
 * every message shares regardless of what it is: envelope reassembly,
 * decryption, replay admission, roster admission, dispatch, and the
 * replicated-state machinery ([applyStateOps] through [onStateChanged]) that
 * runs whether or not anything arrived at all.
 *
 * [publisher] exists here only because a small number of inbound messages
 * produce an outbound one in response — a locate request answered with a
 * position, most notably. Nothing here signs or seals anything itself; every
 * byte that changes hands to leave the phone still goes out through
 * [SyncPublisher]'s own signing and sealing, unchanged.
 */
class SyncReceiver(context: Context, private val publisher: SyncPublisher) {

    private val appContext = context.applicationContext
    private val config = SyncConfigStore(appContext)
    private val roles = RoleStore(appContext)
    private val roster = RosterStore(appContext)

    // Only one side of a phone ever touches most of these, so neither store is
    // built on a phone that has no business holding it.
    private val requests by lazy { RequestCoordinator(appContext) }
    private val inbox by lazy { InboxStore(appContext) }
    private val newApps by lazy { NewAppStore(appContext) }
    private val heartbeats by lazy { HeartbeatStore(appContext) }
    private val summaries by lazy { DailySummaryStore(appContext) }
    private val family by lazy { FamilyEventStore(appContext) }
    private val notifier by lazy { RequestNotifier(appContext) }
    private val state by lazy { ReplicatedStateStore(appContext) }
    private val fragments = FragmentAssembler()

    // One collaborator per message family — see the class doc comment. Lazy
    // for the same reason the stores above are: a phone that never needs a
    // family's handler never builds the stores behind it either.
    private val deviceTrust by lazy { DeviceTrustHandler(appContext, roster, roles, config, publisher, notifier) }
    private val childCommand by lazy { ChildCommandHandler(appContext, publisher, notifier) }
    private val requestSync by lazy { RequestSyncHandler(appContext, requests, inbox, notifier) }
    private val telemetry by lazy { TelemetryInboundHandler(appContext, heartbeats, newApps, summaries, family, notifier) }
    private val chat by lazy { ChatInboundHandler(appContext, roster, notifier, KeystoreSigner()) }

    /**
     * Fetches and applies anything the other phones have sent.
     *
     * Runs on the executor and reports back through [onFinished] so a screen can
     * show a result. Called from the enforcement tick on the child device, and on
     * resume on a parent's.
     */
    fun pollNow(onFinished: (String) -> Unit = {}) {
        val settings = config.load()
        if (!settings.isUsable) {
            onFinished(RESULT_NOT_CONFIGURED)
            return
        }

        SyncKeying.execute {
            val key = SyncKeying.keyFor(settings)
            if (key == null) {
                // The callback only reaches a screen that is open right now; the
                // background worker and the enforcement tick poll with nobody
                // watching, and without this a bad key failed with no trace at all.
                Diagnostics.record(appContext, DiagnosticEvent.SYNC_KEY_UNAVAILABLE)
                onFinished(RESULT_BAD_PASSPHRASE)
                return@execute
            }

            val client = Transports.of(settings)
            // A little overlap on the window, because ntfy filters by whole
            // seconds and a message published in the same second as the last poll
            // would otherwise be missed. The replay guard removes the duplicates.
            val since = config.lastPolledAt().minusSeconds(POLL_OVERLAP_SECONDS)

            when (val result = client.poll(since)) {
                is TransportResult.Failed -> {
                    // The message is a transport error, never message content.
                    Diagnostics.record(
                        appContext,
                        DiagnosticEvent.SYNC_POLL_FAILED,
                        buildMap {
                            put("kind", result.kind.ordinal.toLong())
                            result.httpStatus?.let { put("httpStatus", it.toLong()) }
                        },
                    )
                    onFinished(result.message)
                }

                is TransportResult.Ok -> {
                    val applied = result.value.count { envelope -> apply(key, envelope) }
                    config.recordPolledAt(Instant.now())
                    keepReplicaCurrent()
                    Diagnostics.record(
                        appContext,
                        DiagnosticEvent.SYNC_POLLED,
                        mapOf(
                            "received" to result.value.size.toLong(),
                            "applied" to applied.toLong(),
                        ),
                    )
                    onFinished("$applied applied")
                }
            }
        }
    }

    /**
     * Applies one envelope that arrived on the realtime stream.
     *
     * The realtime socket feeds envelopes here as they arrive, so a parent hears
     * about a request and a child hears about a decision as soon as they are
     * published rather than waiting for the next poll. Runs on the shared sync
     * executor so the replay-guard write cannot interleave with a publish, exactly
     * as with [pollNow].
     *
     * Returns true if the envelope was applied, false otherwise (ignored own
     * traffic, undecryptable, refused, a stranger offered for enrolment). Unlike
     * [pollNow] this does not re-run the whole replica-sync pass on every message
     * — that happens on the slow reconciliation poll to avoid redundant pushes.
     */
    fun receiveEnvelope(key: SyncKey, envelope: String): Boolean {
        val applied = apply(key, envelope)
        config.recordPolledAt(Instant.now())
        return applied
    }

    /**
     * Opens one envelope and acts on it.
     *
     * Anything that fails to decrypt is someone else's traffic on a public topic,
     * which is expected and silent. Anything that decrypts but fails the replay
     * guard is logged, because that is either a clock problem or an attack.
     */
    private fun apply(key: SyncKey, envelope: String): Boolean {
        // A whole envelope, or one piece of one MessageFragments split apart —
        // either way, fragments.accept hands back an opaque sealed string once
        // (and only once) there is a complete one to open, and both a poll
        // batch and the realtime stream feed every wire message through here,
        // so this is the one place reassembly needs to happen.
        val whole = fragments.accept(envelope) ?: return false

        val plaintext = when (val opened = MessageCrypto.open(key, whole)) {
            is DecryptResult.Opened -> opened.plaintext
            is DecryptResult.Failed -> return false
        }

        val payload = MessageSignatures.split(plaintext) ?: return false
        val message = SyncCodec.decode(payload.body) ?: return false

        // Our own messages come back from the topic; ignore them.
        if (message.sender == config.load().deviceId) return false

        val now = Instant.now()
        val seen = config.seenMessages()
        val admission = ReplayGuard.admit(message, now, seen)
        if (admission is Admission.Reject) {
            Log.w(TAG, "Refused message ${message.id}: ${admission.reason}")
            Diagnostics.record(
                appContext,
                DiagnosticEvent.SYNC_MESSAGE_REFUSED,
                mapOf("reason" to admission.reason.ordinal.toLong()),
            )
            return false
        }

        // Who is speaking, decided from the roster rather than from the message.
        val admittance = RosterRules.admit(message, payload, roster.devices, now)
        when (admittance) {
            is Admittance.Refused -> {
                Log.w(TAG, "Refused message ${message.id}: ${admittance.reason}")
                Diagnostics.record(
                    appContext,
                    DiagnosticEvent.SYNC_SENDER_REFUSED,
                    mapOf("reason" to admittance.reason.ordinal.toLong()),
                )
                return false
            }

            is Admittance.Unknown -> {
                // Correctly signed by a stranger. Offered for enrolment, acted on
                // by nobody: a device nobody has vouched for changes nothing.
                //
                // Deliberately not marked as seen. The message that prompts an
                // approval is usually the request the family is waiting on, and
                // marking it here would mean approving the phone and then finding
                // nothing to answer.
                val noticed = roster.notice(admittance.device)
                Diagnostics.record(appContext, DiagnosticEvent.SYNC_DEVICE_PENDING)
                // Only when this phone is the one that can do something about it,
                // and only the first time. Every poll sees the same stranger again
                // for as long as they keep announcing themselves, and a phone that
                // buzzed once a minute about it would be turned off by the evening.
                if (noticed && Enrolment.mayApprove(syncRole(), roster.parents().size)) {
                    notifier.notifyDevicePending()
                }
                return false
            }

            is Admittance.Accepted -> roster.observed(
                id = admittance.device.id,
                name = message.senderName,
                at = now,
                appVersion = message.field(FIELD_APP_VERSION),
            )
        }

        val remembered = ReplayGuard.remember(message, now, seen)
        config.rememberSeen(remembered.seen)
        if (remembered.droppedWhileFresh > 0) {
            // The ceiling was reached, which real traffic does not do. Ids were
            // discarded while the messages they name could still be replayed, so
            // this is replay protection weakening — and it used to happen on
            // every busy afternoon without anybody being able to tell.
            Diagnostics.record(
                appContext,
                DiagnosticEvent.REPLAY_MEMORY_OVERFLOWED,
                mapOf("dropped" to remembered.droppedWhileFresh.toLong()),
            )
        }

        // What the roster says this sender is, never what the message claims.
        // Replication decides what an op may touch from this, so it has to be
        // the established role rather than the self-reported one.
        // No cast: every other branch of the `when` above returns, so this is
        // only reached for an accepted sender and the compiler knows it.
        val senderRole = admittance.device.role
        val senderId = admittance.device.id

        return when (roles.roleOrChild) {
            DeviceRole.CHILD -> applyAsChild(message, senderRole, senderId)
            DeviceRole.PARENT -> applyAsParent(message, senderRole, senderId)
        }
    }

    private fun syncRole(): SyncRole = roles.roleOrChild.toSyncRole()

    private fun applyAsChild(message: SyncMessage, senderRole: SyncRole?, senderId: DeviceId): Boolean = when (message.type) {
        SyncMessageType.REQUEST_DECIDED -> requestSync.applyDecision(message)
        SyncMessageType.BONUS_GRANTED -> childCommand.applyBonus(message)
        SyncMessageType.APP_DECIDED -> childCommand.applyAppDecision(message)
        SyncMessageType.LOCK_NOW -> childCommand.applyLockNow()
        SyncMessageType.LOCATE_REQUEST -> childCommand.applyLocateRequest()
        SyncMessageType.DEVICE_VOUCHED -> deviceTrust.applyVouch(message)
        SyncMessageType.DEVICE_REVOKED -> deviceTrust.applyRevoke(message)
        SyncMessageType.FAMILY_DISBANDED -> deviceTrust.applyDisband(message)
        SyncMessageType.CHAT_MESSAGE -> chat.rememberChat(message)
        SyncMessageType.POLICY_REQUESTED -> { publisher.publishPolicySnapshot(); true }
        SyncMessageType.POLICY_UPDATED -> childCommand.applyPolicyUpdate(message)
        SyncMessageType.APP_INVENTORY_REQUESTED -> { publisher.publishAppInventory(); true }
        SyncMessageType.PLACE_CREATE_REQUESTED -> childCommand.applyPlaceCreateRequest(message)
        SyncMessageType.STATE_OPS -> applyStateOps(message, senderRole, senderId)
        SyncMessageType.STATE_DIGEST -> compareDigest(message)
        SyncMessageType.STATE_REPAIR -> answerRepair(message)
        // A child phone is the only source of these; hearing one back means a
        // second child device shares the family topic, which is not supported yet.
        // An announcement has already done its work in the roster by this point.
        SyncMessageType.REQUEST_RAISED,
        SyncMessageType.HEARTBEAT,
        SyncMessageType.APP_INSTALLED,
        SyncMessageType.DAILY_SUMMARY,
        SyncMessageType.DEVICE_STOPPING,
        SyncMessageType.DEVICE_ANNOUNCE,
        SyncMessageType.REQUEST_AUTO_APPROVED,
        SyncMessageType.LOCATION_REPORT,
        SyncMessageType.PLACE_TRANSITION,
        SyncMessageType.SOS_RAISED,
        SyncMessageType.SOS_CLEARED,
        SyncMessageType.WEB_SUMMARY,
        SyncMessageType.POLICY_SNAPSHOT,
        SyncMessageType.APP_INVENTORY,
        -> false
    }

    private fun applyAsParent(message: SyncMessage, senderRole: SyncRole?, senderId: DeviceId): Boolean = when (message.type) {
        SyncMessageType.REQUEST_RAISED -> requestSync.rememberRequest(message)
        // Another parent answered first. Recording that here is what stops two
        // parents answering the same request twice.
        SyncMessageType.REQUEST_DECIDED -> requestSync.settleRequest(message)
        SyncMessageType.HEARTBEAT -> telemetry.rememberHeartbeat(message)
        SyncMessageType.APP_INSTALLED -> telemetry.rememberNewApp(message, senderId)
        SyncMessageType.DEVICE_VOUCHED -> deviceTrust.applyVouch(message)
        SyncMessageType.DEVICE_REVOKED -> deviceTrust.applyRevoke(message)
        SyncMessageType.FAMILY_DISBANDED -> deviceTrust.applyDisband(message)
        SyncMessageType.DAILY_SUMMARY -> telemetry.rememberSummary(message)
        SyncMessageType.DEVICE_STOPPING -> telemetry.rememberStopping(message)
        SyncMessageType.REQUEST_AUTO_APPROVED -> requestSync.settleAutoApproved(message)
        SyncMessageType.LOCATION_REPORT -> telemetry.rememberLocation(message)
        SyncMessageType.PLACE_TRANSITION -> telemetry.rememberTransition(message)
        SyncMessageType.SOS_RAISED -> telemetry.rememberSos(message, raised = true)
        SyncMessageType.SOS_CLEARED -> telemetry.rememberSos(message, raised = false)
        SyncMessageType.WEB_SUMMARY -> telemetry.rememberWebSummary(message)
        SyncMessageType.CHAT_MESSAGE -> chat.rememberChat(message)
        SyncMessageType.POLICY_SNAPSHOT -> rememberPolicySnapshot(message)
        SyncMessageType.APP_INVENTORY -> telemetry.rememberAppInventory(message, senderId)
        SyncMessageType.STATE_OPS -> applyStateOps(message, senderRole, senderId)
        SyncMessageType.STATE_DIGEST -> compareDigest(message)
        SyncMessageType.STATE_REPAIR -> answerRepair(message)
        // Sent by a parent, including possibly this one. Nothing to do with it.
        // An announcement has already done its work in the roster by this point.
        SyncMessageType.BONUS_GRANTED,
        SyncMessageType.APP_DECIDED,
        SyncMessageType.DEVICE_ANNOUNCE,
        SyncMessageType.LOCK_NOW,
        SyncMessageType.LOCATE_REQUEST,
        SyncMessageType.POLICY_REQUESTED,
        SyncMessageType.POLICY_UPDATED,
        SyncMessageType.APP_INVENTORY_REQUESTED,
        SyncMessageType.PLACE_CREATE_REQUESTED,
        -> false
    }

    // ---------- replicated state ----------

    /**
     * The half of replication that happens whether or not anything arrived.
     *
     * Runs at the end of every poll, on both roles. Three things, in order:
     * anything edited locally goes out (including edits made while offline,
     * which is why the queue is durable); the managed phone records its own
     * rules, so an edit made standing at it reaches the parents without being
     * asked for; and on a slow timer this device says what it holds, which is
     * how a replica that missed ops entirely ever finds out.
     */
    private fun keepReplicaCurrent() {
        if (roles.roleOrChild == DeviceRole.CHILD) {
            PolicyReplication(appContext).pushFromLocalPolicy()
            PolicyReplication(appContext).pushSchedulesFromLocal()
            PolicyReplication(appContext).pushContextRulesFromLocal()
            PolicyReplication(appContext).pushPlaceRefsFromLocal()
            PolicyReplication(appContext).pushAutoApprovalsFromLocal()
        } else {
            // Only a parent may write the roster, and every parent holds the
            // same one — so each publishes what it trusts and they converge,
            // rather than a vouch having to be timed to land after an approval.
            RosterReplication(appContext).push()
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
    private fun applyStateOps(message: SyncMessage, senderRole: SyncRole?, senderId: DeviceId): Boolean {
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
    private fun compareDigest(message: SyncMessage): Boolean {
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
    private fun answerRepair(message: SyncMessage): Boolean {
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
            PolicyReplication(appContext).pullIntoLocalPolicy()
            PolicyReplication(appContext).pullSchedulesIntoLocal()
            PolicyReplication(appContext).pullContextRulesIntoLocal()
            PolicyReplication(appContext).pullPlaceRefsIntoLocal()
            PolicyReplication(appContext).pullAutoApprovalsIntoLocal()
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
            RosterReplication(appContext).pullIntoRoster()
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
    private fun rememberPolicySnapshot(message: SyncMessage): Boolean {
        val snapshot = PolicySnapshot.decode(message.field(FIELD_POLICY_JSON)) ?: return false
        return PolicyReplication(appContext).write(snapshot).isNotEmpty()
    }

    companion object {
        const val RESULT_NOT_CONFIGURED = "not configured"
        const val RESULT_BAD_PASSPHRASE = "passphrase too short"

        private const val TAG = "DementorSync"
        private const val POLL_OVERLAP_SECONDS = 5L
    }
}
