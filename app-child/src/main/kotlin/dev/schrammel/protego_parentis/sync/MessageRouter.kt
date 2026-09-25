package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.Admittance
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncMessageType
import dev.schrammel.protego_parentis.sync.core.SyncRole

/**
 * Routes an admitted message to the handler that acts on it.
 *
 * The two dispatch tables ([childRoutes] and [parentRoutes]) replace the two
 * 40-line when-blocks that lived inside [SyncReceiver]. What an arriving
 * message *means* still depends on which side is reading it — a child phone
 * acts on answers; a parent phone collects questions — so there are two tables
 * and the receiver picks one based on [RoleStore.roleOrChild].
 *
 * Adding a new message type is one entry in the relevant table, not an edit to
 * a method body; the type falls through to [unhandled] (returns false) when no
 * entry exists, which is the same "ignore what is not yours" behaviour the old
 * else-branches provided.
 *
 * The four STATE_* types and POLICY_SNAPSHOT delegate to [ReplicaMaintainer],
 * which owns the replicated-state machinery. The three publish-on-receive
 * types (POLICY_REQUESTED, APP_INVENTORY_REQUESTED, DIAGNOSTICS_REQUESTED)
 * stay inline because they are one-line publisher calls with no state.
 */
internal class MessageRouter(
    private val appContext: Context,
    private val roles: RoleStore,
    private val deviceTrust: DeviceTrustHandler,
    private val childCommand: ChildCommandHandler,
    private val requestSync: RequestSyncHandler,
    private val telemetry: TelemetryInboundHandler,
    private val chat: ChatInboundHandler,
    private val publisher: SyncPublisher,
    private val replica: ReplicaMaintainer,
) {
    /**
     * Acts on an admitted message, routed by this device's own role.
     *
     * What the roster says the sender is, never what the message claims —
     * replication decides what an op may touch from this, so it has to be
     * the established role rather than the self-reported one.
     *
     * A malformed or version-skewed field inside an otherwise correctly
     * signed message can throw here (a handler parsing an amount, a
     * package name, a policy snapshot) — content this device does not
     * control, arriving on the shared sync executor. Uncaught, that
     * reaches the process's default handler and kills the app; caught
     * here, it is one refused message and the executor's queue keeps
     * running for whatever is next.
     */
    fun dispatch(message: SyncMessage, admittance: Admittance.Accepted): Boolean {
        val senderRole = admittance.device.role
        val senderId = admittance.device.id

        return runCatching {
            when (roles.roleOrChild) {
                DeviceRole.CHILD -> routeChild(message, senderRole, senderId)
                DeviceRole.PARENT -> routeParent(message, senderRole, senderId)
            }
        }.getOrElse { error ->
            Log.w(TAG, "Refused message ${message.id}: threw while applying", error)
            Diagnostics.record(appContext, DiagnosticEvent.SYNC_DISPATCH_FAILED)
            false
        }
    }

    private fun routeChild(
        message: SyncMessage,
        senderRole: SyncRole?,
        senderId: DeviceId
    ): Boolean =
        childRoutes[message.type]?.invoke(message, senderRole, senderId) ?: false

    private fun routeParent(
        message: SyncMessage,
        senderRole: SyncRole?,
        senderId: DeviceId
    ): Boolean =
        parentRoutes[message.type]?.invoke(message, senderRole, senderId) ?: false

    /** A handler function: takes the message and the sender's roster-established identity. */
    private typealias Handler = (SyncMessage, SyncRole?, DeviceId) -> Boolean

    private val childRoutes: Map<SyncMessageType, Handler> = mapOf(
        SyncMessageType.REQUEST_DECIDED to { m, _, _ -> requestSync.applyDecision(m) },
        SyncMessageType.BONUS_GRANTED to { m, _, _ -> childCommand.applyBonus(m) },
        SyncMessageType.APP_DECIDED to { m, _, _ -> childCommand.applyAppDecision(m) },
        SyncMessageType.LOCK_NOW to { _, _, _ -> childCommand.applyLockNow() },
        SyncMessageType.LOCATE_REQUEST to { _, _, _ -> childCommand.applyLocateRequest() },
        SyncMessageType.DEVICE_VOUCHED to { m, _, _ -> deviceTrust.applyVouch(m) },
        SyncMessageType.DEVICE_REVOKED to { m, _, _ -> deviceTrust.applyRevoke(m) },
        SyncMessageType.FAMILY_DISBANDED to { m, _, _ -> deviceTrust.applyDisband(m) },
        SyncMessageType.CHAT_MESSAGE to { m, _, _ -> chat.rememberChat(m) },
        SyncMessageType.POLICY_REQUESTED to { _, _, _ -> publisher.publishPolicySnapshot(); true },
        SyncMessageType.POLICY_UPDATED to { m, _, _ -> childCommand.applyPolicyUpdate(m) },
        SyncMessageType.APP_INVENTORY_REQUESTED to { _, _, _ -> publisher.publishAppInventory(); true },
        SyncMessageType.PLACE_CREATE_REQUESTED to { m, _, _ ->
            childCommand.applyPlaceCreateRequest(
                m
            )
        },
        SyncMessageType.DIAGNOSTICS_REQUESTED to { _, _, _ -> publisher.publishDiagnosticsReport(); true },
        SyncMessageType.STATE_OPS to { m, r, d -> replica.applyStateOps(m, r, d) },
        SyncMessageType.STATE_DIGEST to { m, _, _ -> replica.compareDigest(m) },
        SyncMessageType.STATE_REPAIR to { m, _, _ -> replica.answerRepair(m) },
        // A child phone is the only source of these; hearing one back means a
        // second child device shares the family topic, which is not supported yet.
        // An announcement has already done its work in the roster by this point.
        SyncMessageType.REQUEST_RAISED to ::unhandled,
        SyncMessageType.HEARTBEAT to ::unhandled,
        SyncMessageType.APP_INSTALLED to ::unhandled,
        SyncMessageType.DAILY_SUMMARY to ::unhandled,
        SyncMessageType.DEVICE_STOPPING to ::unhandled,
        SyncMessageType.DEVICE_ANNOUNCE to ::unhandled,
        SyncMessageType.REQUEST_AUTO_APPROVED to ::unhandled,
        SyncMessageType.LOCATION_REPORT to ::unhandled,
        SyncMessageType.PLACE_TRANSITION to ::unhandled,
        SyncMessageType.SOS_RAISED to ::unhandled,
        SyncMessageType.SOS_CLEARED to ::unhandled,
        SyncMessageType.WEB_SUMMARY to ::unhandled,
        SyncMessageType.POLICY_SNAPSHOT to ::unhandled,
        SyncMessageType.APP_INVENTORY to ::unhandled,
        SyncMessageType.DIAGNOSTICS_REPORT to ::unhandled,
    )

    private val parentRoutes: Map<SyncMessageType, Handler> = mapOf(
        SyncMessageType.REQUEST_RAISED to { m, _, _ -> requestSync.rememberRequest(m) },
        // Another parent answered first. Recording that here is what stops two
        // parents answering the same request twice.
        SyncMessageType.REQUEST_DECIDED to { m, _, _ -> requestSync.settleRequest(m) },
        SyncMessageType.HEARTBEAT to { m, _, _ -> telemetry.rememberHeartbeat(m) },
        SyncMessageType.APP_INSTALLED to { m, _, d -> telemetry.rememberNewApp(m, d) },
        SyncMessageType.DEVICE_VOUCHED to { m, _, _ -> deviceTrust.applyVouch(m) },
        SyncMessageType.DEVICE_REVOKED to { m, _, _ -> deviceTrust.applyRevoke(m) },
        SyncMessageType.FAMILY_DISBANDED to { m, _, _ -> deviceTrust.applyDisband(m) },
        SyncMessageType.DAILY_SUMMARY to { m, _, _ -> telemetry.rememberSummary(m) },
        SyncMessageType.DEVICE_STOPPING to { m, _, _ -> telemetry.rememberStopping(m) },
        SyncMessageType.REQUEST_AUTO_APPROVED to { m, _, _ -> requestSync.settleAutoApproved(m) },
        SyncMessageType.LOCATION_REPORT to { m, _, _ -> telemetry.rememberLocation(m) },
        SyncMessageType.PLACE_TRANSITION to { m, _, _ -> telemetry.rememberTransition(m) },
        SyncMessageType.SOS_RAISED to { m, _, _ -> telemetry.rememberSos(m, raised = true) },
        SyncMessageType.SOS_CLEARED to { m, _, _ -> telemetry.rememberSos(m, raised = false) },
        SyncMessageType.WEB_SUMMARY to { m, _, _ -> telemetry.rememberWebSummary(m) },
        SyncMessageType.CHAT_MESSAGE to { m, _, _ -> chat.rememberChat(m) },
        SyncMessageType.POLICY_SNAPSHOT to { m, _, _ -> replica.rememberPolicySnapshot(m) },
        SyncMessageType.APP_INVENTORY to { m, _, d -> telemetry.rememberAppInventory(m, d) },
        SyncMessageType.DIAGNOSTICS_REPORT to { m, _, d ->
            telemetry.rememberDiagnosticsReport(
                m,
                d
            )
        },
        SyncMessageType.STATE_OPS to { m, r, d -> replica.applyStateOps(m, r, d) },
        SyncMessageType.STATE_DIGEST to { m, _, _ -> replica.compareDigest(m) },
        SyncMessageType.STATE_REPAIR to { m, _, _ -> replica.answerRepair(m) },
        // Sent by a parent, including possibly this one. Nothing to do with it.
        // An announcement has already done its work in the roster by this point.
        SyncMessageType.BONUS_GRANTED to ::unhandled,
        SyncMessageType.APP_DECIDED to ::unhandled,
        SyncMessageType.DEVICE_ANNOUNCE to ::unhandled,
        SyncMessageType.LOCK_NOW to ::unhandled,
        SyncMessageType.LOCATE_REQUEST to ::unhandled,
        SyncMessageType.POLICY_REQUESTED to ::unhandled,
        SyncMessageType.POLICY_UPDATED to ::unhandled,
        SyncMessageType.APP_INVENTORY_REQUESTED to ::unhandled,
        SyncMessageType.PLACE_CREATE_REQUESTED to ::unhandled,
        SyncMessageType.DIAGNOSTICS_REQUESTED to ::unhandled,
    )

    /** The message is not this role's to act on. */
    private fun unhandled(
        message: SyncMessage,
        senderRole: SyncRole?,
        senderId: DeviceId
    ): Boolean = false

    companion object {
        private const val TAG = "DementorSync"
    }
}
