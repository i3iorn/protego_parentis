package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REVOKED_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET_LABEL
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_KEY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_ROLE
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch

/**
 * Who this device trusts, and who it stops trusting.
 *
 * The one message family [SyncReceiver] dispatches identically for both
 * roles: a vouch, a revoke, or a disband changes the roster the same way
 * whichever side of the family reads it. Split out on its own because that
 * shared-role dispatch is exactly the seam a device-trust bug would hide
 * behind if it stayed mixed in with the (very different) per-role message
 * families around it.
 */
internal class DeviceTrustHandler(
    context: Context,
    private val roster: RosterStore,
    private val roles: RoleStore,
    private val config: SyncConfigStore,
    private val publisher: SyncPublisher,
    private val notifier: RequestNotifier,
) {
    private val appContext = context.applicationContext

    /**
     * Enrols a device an already-trusted parent has vouched for.
     *
     * Reaching here means the sender was accepted by the roster, which for this
     * message type means an enrolled parent. What is left to check is the vouch
     * itself — see [Enrolment.shouldAccept]: nothing human looks at one, so the
     * fingerprint check a person would have made happens in code instead.
     */
    fun applyVouch(message: SyncMessage): Boolean {
        val vouch = Vouch(
            id = DeviceId(message.field(FIELD_VOUCHED_ID) ?: return false),
            name = message.field(FIELD_TARGET_LABEL).orEmpty().clip(),
            role = SyncRole.parse(message.field(FIELD_VOUCHED_ROLE)) ?: return false,
            publicKey = message.field(FIELD_VOUCHED_KEY) ?: return false,
        )

        val self = LocalDeviceId.current()?.let(::DeviceId) ?: return false
        if (!Enrolment.shouldAccept(vouch, self)) {
            Diagnostics.record(appContext, DiagnosticEvent.SYNC_SENDER_REFUSED)
            return false
        }
        if (roster.devices.any { it.id == vouch.id }) return false

        roster.enrol(vouch)
        val name = vouch.name.ifBlank { vouch.id.value }
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.DEVICE_APPROVED,
            subject = name,
            after = vouch.role.name,
        )
        // The enrolment above happens with no screen open and nobody watching —
        // this is the "regardless of how" half of making a new device obvious;
        // an approval a person taps through already has its own confirmation.
        notifier.notifyDeviceAdded(name)
        return true
    }

    /**
     * Drops one device's trust, propagated from whichever parent revoked it.
     *
     * [Enrolment.shouldAcceptRevoke] is the whole security boundary here: a
     * revoke naming the child is refused outright, on every phone including the
     * child's own, because that removal is never something a message may cause
     * — it stays a local, PIN-gated tap on the child's own device.
     */
    fun applyRevoke(message: SyncMessage): Boolean {
        val revokedId = DeviceId(message.field(FIELD_REVOKED_ID) ?: return false)
        val self = LocalDeviceId.current()?.let(::DeviceId) ?: return false

        if (revokedId == self) {
            if (!Enrolment.shouldAcceptRevoke(roles.roleOrChild.toSyncRole())) return false
            Audit.record(
                context = appContext,
                actor = AuditActor.PARENT,
                action = AuditAction.DEVICE_REVOKED,
                subject = config.deviceName,
            )
            publisher.leave()
            return true
        }

        val target = roster.devices.firstOrNull { it.id == revokedId } ?: return false
        if (!Enrolment.shouldAcceptRevoke(target.role)) return false

        roster.revoke(revokedId)
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.DEVICE_REVOKED,
            subject = target.name,
        )
        notifier.notifyDeviceRemoved(target.name)
        return true
    }

    /**
     * Ends the family, for every phone the founding parent's revoke would have
     * reached anyway.
     *
     * A parent's phone leaves outright, the same as tapping "leave" itself. The
     * child's phone only hears about it: wiping a child's family membership by
     * remote message is exactly the thing [Enrolment.shouldAcceptRevoke] exists
     * to rule out, so ending it for real stays a local, PIN-gated action on that
     * phone even though the news travels immediately.
     */
    fun applyDisband(message: SyncMessage): Boolean {
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.FAMILY_DISBANDED,
        )
        return when (roles.roleOrChild) {
            DeviceRole.CHILD -> {
                notifier.notifyFamilyDisbanded()
                true
            }

            DeviceRole.PARENT -> {
                notifier.notifyFamilyDisbanded()
                publisher.leave()
                true
            }
        }
    }
}
