package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.context.PlaceTransition
import dev.schrammel.protego_parentis.context.Position
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.StoredSchedule
import dev.schrammel.protego_parentis.security.DeviceRole

/** Which sync identity a device's local role corresponds to. */
internal fun DeviceRole.toSyncRole(): SyncRole = when (this) {
    DeviceRole.CHILD -> SyncRole.CHILD
    DeviceRole.PARENT -> SyncRole.PARENT
}

/**
 * Carries requests and decisions between the child's phone and any number of
 * parent phones.
 *
 * Every parent phone subscribes to the same topic with the same passphrase, so
 * adding another is a matter of typing two values — there is no account, no
 * server-side notion of who is in the family, and nothing for an operator to
 * hand over. The server sees only ciphertext.
 *
 * What an arriving message *means* depends on which side is reading it, and that
 * is the one branch in this class's design — a child phone acts on answers, a
 * parent phone collects questions. Each ignores the messages meant for the other
 * rather than trying to be useful with them. See [SyncReceiver] for that branch.
 *
 * Encryption alone was not enough to decide *who* sent something: the
 * passphrase is shared, so a child who obtained it could publish an approval
 * of their own request. Every message now also carries a per-device signature,
 * and the sender's role is read from an enrolled roster rather than from the
 * message — see [RosterRules].
 *
 * This class itself is a thin façade over two collaborators that split the two
 * directions traffic flows: [SyncPublisher] (everything a phone *sends*) and
 * [SyncReceiver] (everything a phone *receives* and decides what to do about).
 * The split exists because the two had grown into one 1200-line file with no
 * boundary between "what we say" and "what we believe when told" — two
 * questions worth being able to read, and to reason about the security of,
 * independently. Every public method here is unchanged from before the split;
 * this file only decides which collaborator answers it.
 *
 * All network work happens on a background executor shared by both
 * collaborators (see [SyncKeying]). The methods here return immediately;
 * nothing blocks the caller.
 */
class SyncCoordinator(context: Context) {

    private val context = context.applicationContext
    private val publisher = SyncPublisher(context)
    private val receiver = SyncReceiver(context, publisher)

    fun settings(): SyncSettings = publisher.settings()

    /** Joins the family described by [token]. */
    fun join(token: FamilyToken, passphrase: String) = publisher.join(token, passphrase)

    /** Leaves, keeping nothing behind that could reconnect or decrypt on its own. */
    fun leave() {
        RealtimeSyncService.stop(context)
        publisher.leave()
    }

    fun suggestTopic(): String = publisher.suggestTopic()

    /** This phone's fingerprint, for reading aloud when enrolling it elsewhere. */
    fun deviceId(): DeviceId? = publisher.deviceId()

    /** Introduces this phone to the family (DEVICE_ANNOUNCE). */
    fun announceSelf(force: Boolean = false) = publisher.announceSelf(force)

    /** Announces a new request to every parent phone. */
    fun publishRequestRaised(request: Request, label: String) =
        publisher.publishRequestRaised(request, label)

    /** Confirms back to the other parents that a request has been answered. */
    fun publishDecision(request: Request) = publisher.publishDecision(request)

    fun publishDecision(id: String, state: RequestState, note: String?) =
        publisher.publishDecision(id, state, note)

    /** Sends bonus time to the child's phone. */
    fun publishBonus(minutes: Int) = publisher.publishBonus(minutes)

    /** Changes the child device's daily allowance from a parent's own phone. */
    fun publishSetDailyLimit(minutes: Int) = publisher.publishSetDailyLimit(minutes)

    /** Says the child device is still alive and still working (TR-2). */
    fun publishHeartbeat(heartbeat: Heartbeat, force: Boolean = false) =
        publisher.publishHeartbeat(heartbeat, force)

    /** Tells the parents an app they have not seen appeared on the phone (AC-3). */
    fun publishAppInstalled(packageName: String, label: String) =
        publisher.publishAppInstalled(packageName, label)

    /** Sends today's totals to the parents (RPT-1). */
    fun publishDailySummary(
        usedMinutes: Int,
        allowedMinutes: Int,
        bonusMinutes: Int,
        remainingMinutes: Int,
        level: Int,
        topApps: List<AppTime>,
    ) = publisher.publishDailySummary(usedMinutes, allowedMinutes, bonusMinutes, remainingMinutes, level, topApps)

    /** Asks the child's phone to lock its screen now. */
    fun publishLockNow() = publisher.publishLockNow()

    /** Asks where the phone is (LOC-2). */
    fun publishLocateRequest() = publisher.publishLocateRequest()

    /** One position, in answer to one request. Never sent unasked, except in an SOS. */
    fun publishLocation(position: Position, forEmergency: Boolean) =
        publisher.publishLocation(position, forEmergency)

    /** Arrived at, or left, a named place. Carries the name and no coordinates. */
    fun publishPlaceTransition(transition: PlaceTransition) =
        publisher.publishPlaceTransition(transition)

    fun publishSosRaised() = publisher.publishSosRaised()

    fun publishSosCleared() = publisher.publishSosCleared()

    /** A standing rule answered a request, so the parents' inboxes can settle it. */
    fun publishAutoApproved(request: Request, label: String, ruleName: String) =
        publisher.publishAutoApproved(request, label, ruleName)

    /** Today's domains, counted (WF-3). */
    fun publishWebSummary(domains: List<Pair<String, Int>>, blocked: Int) =
        publisher.publishWebSummary(domains, blocked)

    /** Sends a message somebody typed. Returns the stored message, or null. */
    fun publishChat(raw: String): ChatMessage? = publisher.publishChat(raw)

    /** Says the phone is going off, so silence can be told apart from shutdown. */
    fun publishStopping() = publisher.publishStopping()

    /** Tells the family that this phone has let a device in. */
    fun publishVouch(device: KnownDevice) = publisher.publishVouch(device)

    /** Tells the family that this phone has removed a device's trust. */
    fun publishRevoke(device: KnownDevice) = publisher.publishRevoke(device)

    /** Tells the family that this phone has ended it. */
    fun publishDisband() = publisher.publishDisband()

    /**
     * Records an edit to [childId]'s rules and sends what changed — the
     * managed phone's own edits when [childId] is null, or a parent's edit to
     * a specific child's ruleset (#43) when it is given.
     *
     * Replaces the old whole-policy push: only the registers that actually
     * differ become ops, and they merge with concurrent edits from other
     * devices rather than overwriting them.
     */
    fun applyPolicyEdit(snapshot: PolicySnapshot, childId: DeviceId? = null) {
        val replication = PolicyReplication(context)
        if (childId != null) replication.write(snapshot, childId) else replication.write(snapshot)
        publisher.publishPendingOps()
    }

    /** As [applyPolicyEdit], for a schedule edit. */
    fun applyScheduleEdit(schedules: List<StoredSchedule>, childId: DeviceId? = null) {
        val replication = PolicyReplication(context)
        if (childId != null) {
            replication.writeSchedules(schedules, childId)
        } else {
            replication.writeSchedules(schedules)
        }
        publisher.publishPendingOps()
    }

    /** As [applyPolicyEdit], for a contextual-rule edit (#43 — no editor used this before). */
    fun applyContextRuleEdit(rules: List<ContextRule>, childId: DeviceId? = null) {
        val replication = PolicyReplication(context)
        if (childId != null) {
            replication.writeContextRules(rules, childId)
        } else {
            replication.writeContextRules(rules)
        }
        publisher.publishPendingOps()
    }

    /** As [applyPolicyEdit], for a place rename/resize/delete (#43). */
    fun applyPlaceRefEdit(refs: List<PlaceRef>, childId: DeviceId? = null) {
        val replication = PolicyReplication(context)
        if (childId != null) replication.writePlaceRefs(refs, childId) else replication.writePlaceRefs(refs)
        publisher.publishPendingOps()
    }

    /** As [applyPolicyEdit], for a standing auto-approval edit (#89 — no editor used this before). */
    fun applyAutoApprovalEdit(rules: List<AutoApproval>, childId: DeviceId? = null) {
        val replication = PolicyReplication(context)
        if (childId != null) {
            replication.writeAutoApprovals(rules, childId)
        } else {
            replication.writeAutoApprovals(rules)
        }
        publisher.publishPendingOps()
    }

    /** Sends anything edited locally and not yet published. */
    fun publishPendingOps() = publisher.publishPendingOps()

    /** Says what this device holds, so a replica behind on ops can notice. */
    fun publishDigest() = publisher.publishDigest()

    /** Parent: asks the child phone for the apps it has installed. */
    fun publishAppInventoryRequest() = publisher.publishAppInventoryRequest()

    /** A parent's answer about a new app. */
    fun publishAppDecision(packageName: String, blocked: Boolean) =
        publisher.publishAppDecision(packageName, blocked)

    /** Tries an address without moving to it. */
    fun testReachable(token: FamilyToken, onFinished: (String) -> Unit) =
        publisher.testReachable(token, onFinished)

    /** Fetches and applies anything the other phones have sent. */
    fun pollNow(onFinished: (String) -> Unit = {}) = receiver.pollNow(onFinished)

    companion object {
        const val RESULT_NOT_CONFIGURED = SyncReceiver.RESULT_NOT_CONFIGURED
        const val RESULT_BAD_PASSPHRASE = SyncReceiver.RESULT_BAD_PASSPHRASE
    }
}
