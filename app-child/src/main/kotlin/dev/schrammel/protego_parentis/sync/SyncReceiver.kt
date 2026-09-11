package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.ChatText
import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RequestId
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.context.LocationSharing
import dev.schrammel.protego_parentis.policy.IntegrityRules
import dev.schrammel.protego_parentis.policy.request.RequestRules
import dev.schrammel.protego_parentis.rules.AppReviewStore
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.FeatureFlags
import dev.schrammel.protego_parentis.rules.GrantStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.rules.applySnapshot
import dev.schrammel.protego_parentis.rules.TargetLabels
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.KeystoreSigner
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.ui.HomeActivity
import dev.schrammel.protego_parentis.ui.JoinFamilyActivity
import dev.schrammel.protego_parentis.usage.InstalledApps
import dev.schrammel.protego_parentis.sync.SyncWireFields.APP_SEPARATOR
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APP_VERSION
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ACCURACY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ALLOWED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APPS_JSON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APP_PREFIX
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ARRIVED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BATTERY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BUCKETS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_DIGEST
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BLOCKED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BLOCKED_COUNT
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BONUS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_DOMAIN_PREFIX
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_EMERGENCY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_EXPIRES_AT
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_HISTORY_COUNT
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_HISTORY_HEAD
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LATITUDE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LEVEL
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_LONGITUDE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_MANAGED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_MINUTES
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_NOTE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_OPS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_PACKAGE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_PLACE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_POLICY_JSON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_POLICY_VERSION
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REASON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REMAINING
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REQUEST_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REVOKED_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_RULE_NAME
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_STATE
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_SUSPENDED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET_LABEL
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TEXT
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_USAGE_ACCESS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_USED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_KEY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_ROLE
import dev.schrammel.protego_parentis.sync.SyncWireFields.MAX_SUMMARY_APPS
import dev.schrammel.protego_parentis.sync.SyncWireFields.MAX_SUMMARY_DOMAINS
import dev.schrammel.protego_parentis.sync.SyncWireFields.MAX_WIRE_TEXT
import java.time.Instant
import dev.schrammel.protego_parentis.sync.core.Admission
import dev.schrammel.protego_parentis.sync.core.Admittance
import dev.schrammel.protego_parentis.sync.core.DecryptResult
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Enrolment
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
import dev.schrammel.protego_parentis.sync.core.Vouch

/**
 * Everything a phone *receives*: opening an envelope, deciding whether to
 * believe it, and — the one branch in the whole sync layer — acting on it
 * differently depending on which side is reading it. A child phone acts on
 * answers; a parent phone collects questions. Each ignores the messages meant
 * for the other rather than trying to be useful with them.
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
    private val signer = KeystoreSigner()

    // Only one side of a phone ever touches most of these, so neither store is
    // built on a phone that has no business holding it.
    private val requests by lazy { RequestCoordinator(appContext) }
    private val inbox by lazy { InboxStore(appContext) }
    private val newApps by lazy { NewAppStore(appContext) }
    private val heartbeats by lazy { HeartbeatStore(appContext) }
    private val summaries by lazy { DailySummaryStore(appContext) }
    private val family by lazy { FamilyEventStore(appContext) }
    private val chat by lazy { ChatStore(appContext) }
    private val notifier by lazy { RequestNotifier(appContext) }
    private val state by lazy { ReplicatedStateStore(appContext) }

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
        val plaintext = when (val opened = MessageCrypto.open(key, envelope)) {
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
        SyncMessageType.REQUEST_DECIDED -> applyDecision(message)
        SyncMessageType.BONUS_GRANTED -> applyBonus(message)
        SyncMessageType.APP_DECIDED -> applyAppDecision(message)
        SyncMessageType.LOCK_NOW -> applyLockNow()
        SyncMessageType.SET_DAILY_LIMIT -> applyDailyLimit(message)
        SyncMessageType.LOCATE_REQUEST -> applyLocateRequest()
        SyncMessageType.DEVICE_VOUCHED -> applyVouch(message)
        SyncMessageType.DEVICE_REVOKED -> applyRevoke(message)
        SyncMessageType.FAMILY_DISBANDED -> applyDisband(message)
        SyncMessageType.CHAT_MESSAGE -> rememberChat(message)
        SyncMessageType.POLICY_REQUESTED -> { publisher.publishPolicySnapshot(); true }
        SyncMessageType.POLICY_UPDATED -> applyPolicyUpdate(message)
        SyncMessageType.APP_INVENTORY_REQUESTED -> { publisher.publishAppInventory(); true }
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
        SyncMessageType.REQUEST_RAISED -> rememberRequest(message)
        // Another parent answered first. Recording that here is what stops two
        // parents answering the same request twice.
        SyncMessageType.REQUEST_DECIDED -> settleRequest(message)
        SyncMessageType.HEARTBEAT -> rememberHeartbeat(message)
        SyncMessageType.APP_INSTALLED -> rememberNewApp(message)
        SyncMessageType.DEVICE_VOUCHED -> applyVouch(message)
        SyncMessageType.DEVICE_REVOKED -> applyRevoke(message)
        SyncMessageType.FAMILY_DISBANDED -> applyDisband(message)
        SyncMessageType.DAILY_SUMMARY -> rememberSummary(message)
        SyncMessageType.DEVICE_STOPPING -> rememberStopping(message)
        SyncMessageType.REQUEST_AUTO_APPROVED -> settleAutoApproved(message)
        SyncMessageType.LOCATION_REPORT -> rememberLocation(message)
        SyncMessageType.PLACE_TRANSITION -> rememberTransition(message)
        SyncMessageType.SOS_RAISED -> rememberSos(message, raised = true)
        SyncMessageType.SOS_CLEARED -> rememberSos(message, raised = false)
        SyncMessageType.WEB_SUMMARY -> rememberWebSummary(message)
        SyncMessageType.CHAT_MESSAGE -> rememberChat(message)
        SyncMessageType.POLICY_SNAPSHOT -> rememberPolicySnapshot(message)
        SyncMessageType.APP_INVENTORY -> rememberAppInventory(message, senderId)
        SyncMessageType.STATE_OPS -> applyStateOps(message, senderRole, senderId)
        SyncMessageType.STATE_DIGEST -> compareDigest(message)
        SyncMessageType.STATE_REPAIR -> answerRepair(message)
        // Sent by a parent, including possibly this one. Nothing to do with it.
        // An announcement has already done its work in the roster by this point.
        SyncMessageType.BONUS_GRANTED,
        SyncMessageType.APP_DECIDED,
        SyncMessageType.DEVICE_ANNOUNCE,
        SyncMessageType.LOCK_NOW,
        SyncMessageType.SET_DAILY_LIMIT,
        SyncMessageType.LOCATE_REQUEST,
        SyncMessageType.POLICY_REQUESTED,
        SyncMessageType.POLICY_UPDATED,
        SyncMessageType.APP_INVENTORY_REQUESTED,
        -> false
    }

    // ---------- what the child does when asked ----------

    /**
     * Locks the screen, and says who did it.
     *
     * The notification is posted first so it lands on the lock screen the child is
     * about to see. A phone that locks itself with no explanation is a phone that
     * looks broken, and the answer to "why did that happen" should not require
     * unlocking it again to find out.
     */
    private fun applyLockNow(): Boolean {
        notifier.notifyLocked()
        Audit.record(appContext, AuditActor.PARENT, AuditAction.DEVICE_LOCKED)
        // Handed to the service rather than done here, and not through a callback
        // either: any screen can be the one that happens to be polling when this
        // arrives, and a lock that only worked when the service polled would fail
        // exactly when a parent was watching the app on their own phone.
        EnforcementService.lockNow(appContext)
        return true
    }

    /**
     * Answers a locate, and tells the child it was asked.
     *
     * The telling is not conditional on the answering. A locate that failed
     * because the phone was indoors still happened, and a child who is told only
     * about the successful ones has been told something misleading about how often
     * they are looked for.
     */
    private fun applyLocateRequest(): Boolean {
        // Told either way. A locate that was refused still happened, and a child
        // who hears only about the answered ones has been told something
        // misleading about how often they are looked for.
        notifier.notifyLocated()
        Audit.record(appContext, AuditActor.PARENT, AuditAction.LOCATE_REQUESTED)

        // Turned off means turned off, not hidden. A parent who put this away and
        // found their old phone still answering would be right to feel misled.
        // Every branch below answers. A locate that produced nothing used to
        // write a line to logcat and stop there, so the parent saw "Sent" and
        // then waited on something that was never coming — indistinguishable
        // from an answer still in flight.
        val telemetry = TelemetryReplication(appContext)

        if (!FeatureFlags(appContext).isEnabled(Feature.LOCATION)) {
            Log.i(TAG, "Asked for a position while locating is switched off")
            telemetry.reportLocate(LocateOutcome.SWITCHED_OFF)
            publisher.publishPendingOps()
            return true
        }

        val position = LocationSharing(appContext).locate()
        if (position == null) {
            Log.i(TAG, "Asked for a position, could not get one")
            telemetry.reportLocate(LocateOutcome.UNAVAILABLE)
            publisher.publishPendingOps()
            return true
        }

        Audit.record(appContext, AuditActor.CHILD, AuditAction.LOCATION_SHARED)
        publisher.publishLocation(position, forEmergency = false)
        telemetry.reportLocate(LocateOutcome.ANSWERED)
        publisher.publishPendingOps()
        return true
    }

    // ---------- what a parent does with what arrives ----------

    private fun settleAutoApproved(message: SyncMessage): Boolean {
        val id = message.field(FIELD_REQUEST_ID) ?: return false
        val rule = message.field(FIELD_RULE_NAME)?.clip()

        // A request answered by a standing rule never sat pending, so no parent
        // phone has heard of it. Settling it works when this phone did see the
        // request; when it did not, there is nothing to attach the answer to and
        // the auto-approval is simply noted in the history instead. The old code
        // invented a synthetic request here, which under replication would be a
        // second copy of one the child had already published.
        if (!inbox.settle(id, RequestState.APPROVED, rule, decidedBy = AUTOMATIC)) {
            Audit.record(
                context = appContext,
                actor = AuditActor.PARENT,
                action = AuditAction.REQUEST_APPROVED,
                subject = message.field(FIELD_TARGET_LABEL)?.clip(),
                after = rule,
            )
        }
        return true
    }

    private fun rememberLocation(message: SyncMessage): Boolean {
        val latitude = message.field(FIELD_LATITUDE)?.toDoubleOrNull() ?: return false
        val longitude = message.field(FIELD_LONGITUDE)?.toDoubleOrNull() ?: return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false

        family.recordLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMetres = message.longField(FIELD_ACCURACY)?.toInt() ?: 0,
            at = message.sentAt,
        )
        if (message.field(FIELD_EMERGENCY).toBoolean()) notifier.notifySos(moved = true)
        return true
    }

    private fun rememberTransition(message: SyncMessage): Boolean {
        val place = message.field(FIELD_PLACE)?.clip() ?: return false
        val arrived = message.field(FIELD_ARRIVED).toBoolean()

        family.recordTransition(place, arrived, message.sentAt)
        notifier.notifyPlaceTransition(place, arrived)
        return true
    }

    private fun rememberSos(message: SyncMessage, raised: Boolean): Boolean {
        family.recordSos(raised, message.sentAt)
        if (raised) notifier.notifySos(moved = false) else notifier.notifySosCleared()
        return true
    }

    /**
     * Files a message that arrived, and says so once.
     *
     * Three things happen in order and the order matters. The text is sanitised
     * again — the sender did it too, but the sender is another phone and that is
     * precisely the thing this device cannot take on trust. The store then refuses
     * a message it already holds, which is what stops the same message being
     * announced on every poll for as long as the relay keeps it. Only a message
     * that was genuinely new gets a notification.
     *
     * The sender's name goes through the sanitiser as well. It is chosen on the
     * sending device, so it is attacker-controlled text in exactly the way the
     * message body is, and it is shown right next to it — a name carrying a
     * direction override could make one person's words appear to be another's.
     */
    private fun rememberChat(message: SyncMessage): Boolean {
        if (!FeatureFlags(appContext).isEnabled(Feature.CHAT)) return false

        val text = ChatText.sanitise(message.field(FIELD_TEXT).orEmpty()) ?: return false
        // The roster's name, not the one the message carries. A device chooses
        // its own name and may send a different one with every message; the roster
        // holds what the family agreed to when it was let in.
        val claimed = roster.devices.firstOrNull { it.id.value == message.sender }?.name
        val sender = claimed?.let(ChatText::sanitise)
            ?: appContext.getString(R.string.chat_unknown_sender)

        // From a phone still on the older protocol. Folded into the replica so a
        // part-updated family still shares one conversation rather than two.
        val added = ChatReplication(appContext).send(text, sender) != null
        if (added) notifier.notifyChat(sender, text)
        return added
    }

    private fun rememberWebSummary(message: SyncMessage): Boolean {
        val domains = message.fields
            .filterKeys { it.startsWith(FIELD_DOMAIN_PREFIX) }
            .toSortedMap(compareBy { it.removePrefix(FIELD_DOMAIN_PREFIX).toIntOrNull() ?: 0 })
            .values
            .mapNotNull(::parseDomain)
            .take(MAX_SUMMARY_DOMAINS)

        family.recordWebSummary(
            domains = domains,
            blocked = message.longField(FIELD_BLOCKED_COUNT)?.toInt() ?: 0,
            at = message.sentAt,
        )
        return true
    }

    private fun parseDomain(raw: String): Pair<String, Int>? {
        val at = raw.indexOf(APP_SEPARATOR)
        if (at <= 0 || at == raw.lastIndex) return null
        val count = raw.substring(0, at).toIntOrNull() ?: return null
        return raw.substring(at + 1).clip() to count
    }

    /**
     * Applies a parent's answer about a new app.
     *
     * Marking it reviewed either way is the point: an allowed app has to stop
     * showing up as new, or the parent is asked the same question forever.
     */
    private fun applyAppDecision(message: SyncMessage): Boolean {
        val packageName = message.field(FIELD_PACKAGE) ?: return false
        val blocked = message.field(FIELD_BLOCKED)?.toBoolean() ?: return false

        LocalPolicyStore(appContext).setBlocked(packageName, blocked)
        AppReviewStore(appContext).markReviewed(packageName)
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.APP_BLOCK_CHANGED,
            subject = InstalledApps(appContext).labelFor(PackageName(packageName)),
            after = Audit.flag(blocked),
        )
        AppRegistry.ruleMutationSink.rulesChanged()
        return true
    }

    /**
     * Enrols a device an already-trusted parent has vouched for.
     *
     * Reaching here means the sender was accepted by the roster, which for this
     * message type means an enrolled parent. What is left to check is the vouch
     * itself — see [Enrolment.shouldAccept]: nothing human looks at one, so the
     * fingerprint check a person would have made happens in code instead.
     */
    private fun applyVouch(message: SyncMessage): Boolean {
        val vouch = Vouch(
            id = DeviceId(message.field(FIELD_VOUCHED_ID) ?: return false),
            name = message.field(FIELD_TARGET_LABEL).orEmpty().clip(),
            role = SyncRole.parse(message.field(FIELD_VOUCHED_ROLE)) ?: return false,
            publicKey = message.field(FIELD_VOUCHED_KEY) ?: return false,
        )

        val self = runCatching { signer.deviceId }.getOrNull() ?: return false
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
    private fun applyRevoke(message: SyncMessage): Boolean {
        val revokedId = DeviceId(message.field(FIELD_REVOKED_ID) ?: return false)
        val self = runCatching { signer.deviceId }.getOrNull() ?: return false

        if (revokedId == self) {
            if (!Enrolment.shouldAcceptRevoke(syncRole())) return false
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
    private fun applyDisband(message: SyncMessage): Boolean {
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.FAMILY_DISBANDED,
        )
        return when (roles.roleOrChild) {
            DeviceRole.CHILD -> {
                notifier.notifyFamilyDisbanded(Intent(appContext, HomeActivity::class.java))
                true
            }

            DeviceRole.PARENT -> {
                notifier.notifyFamilyDisbanded(JoinFamilyActivity.intent(appContext))
                publisher.leave()
                true
            }
        }
    }

    /**
     * A summary from a phone still on the older protocol.
     *
     * Folded into replicated telemetry, so a parent's dashboard has one source
     * whichever build the child is on. A newer child writes the same registers
     * itself and this path is never reached for it.
     */
    private fun rememberSummary(message: SyncMessage): Boolean {
        val allowed = message.longField(FIELD_ALLOWED)?.toInt() ?: 0
        val bonus = message.longField(FIELD_BONUS)?.toInt() ?: 0
        val used = message.longField(FIELD_USED)?.toInt() ?: 0

        TelemetryReplication(appContext).report(
            usedMinutes = used,
            allowedMinutes = allowed,
            bonusMinutes = bonus,
            // Older senders didn't send this; fall back to the old (slightly
            // lossy) computation rather than dropping the message.
            remainingMinutes = message.longField(FIELD_REMAINING)?.toInt()
                ?: (allowed + bonus - used).coerceAtLeast(0),
            level = message.longField(FIELD_LEVEL)?.toInt() ?: 0,
            topApps = message.fields
                .filterKeys { it.startsWith(FIELD_APP_PREFIX) }
                .toSortedMap(compareBy { it.removePrefix(FIELD_APP_PREFIX).toIntOrNull() ?: 0 })
                .values
                .mapNotNull(::parseAppTime)
                .take(MAX_SUMMARY_APPS),
        )
        summaries.markHeard(message.sentAt)
        return true
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
        }
        // Additive only, and never for the managed phone: see the doc comment
        // on pullIntoRoster for why trust must fail closed here.
        if (ReplicationRules.ROSTER in sections) {
            RosterReplication(appContext).pullIntoRoster()
        }
        if (ReplicationRules.CHAT in sections) {
            announceNewChat(applied)
        }
        if (ReplicationRules.REQUESTS in sections && roles.roleOrChild == DeviceRole.CHILD) {
            applyAnsweredRequests()
        }
        // "As of" on a parent's dashboard means "when I last heard", and this is
        // that moment. Recorded here rather than derived from the ops, which
        // carry a logical clock and no wall-clock time at all.
        if (ReplicationRules.TELEMETRY in sections && roles.roleOrChild == DeviceRole.PARENT) {
            summaries.markHeard()
        }
    }

    /**
     * Grants time for any of this phone's requests that a parent has answered.
     *
     * Matched by the local id the request was raised with, because the replica
     * knows it by an id of its own. Answers are applied through the ordinary
     * [RequestCoordinator], which is what actually issues the grant and refuses
     * a second answer — so an approval seen again on the next poll is harmless.
     */
    private fun applyAnsweredRequests() {
        RequestReplication(appContext).all().forEach { replicated ->
            val local = replicated.localId?.let(::RequestId) ?: return@forEach
            when (replicated.state) {
                RequestState.APPROVED -> requests.approve(local, replicated.note)
                RequestState.DENIED -> requests.deny(local, replicated.note)
                else -> Unit
            }
        }
    }

    /**
     * Buzzes once for a conversation that moved, not once per register.
     *
     * A message is several ops — text, sender, author — so notifying per op
     * would buzz three times for one line. Only the text register is counted,
     * and only for messages this phone did not itself write.
     */
    private fun announceNewChat(applied: List<StateOp>) {
        if (!FeatureFlags(appContext).isEnabled(Feature.CHAT)) return
        val self = runCatching { signer.deviceId.value }.getOrNull()

        val arrived = applied.filter {
            it.origin != self &&
                it.value != null &&
                it.path.startsWith("${ReplicationRules.CHAT}${ReplicationRules.SEPARATOR}") &&
                it.path.endsWith("${ReplicationRules.SEPARATOR}text")
        }
        if (arrived.isEmpty()) return

        val newest = arrived.maxByOrNull { it.lamport } ?: return
        val id = newest.path
            .removePrefix("${ReplicationRules.CHAT}${ReplicationRules.SEPARATOR}")
            .substringBefore(ReplicationRules.SEPARATOR)

        // The roster's name for the sender, not one carried in the message: a
        // device chooses its own name and could send a different one every
        // time, and it is shown right beside the words.
        val sender = roster.devices.firstOrNull { it.id.value == newest.origin }?.name
            ?.let(ChatText::sanitise)
            ?: appContext.getString(R.string.chat_unknown_sender)

        ChatReplication(appContext).messages().firstOrNull { it.id == id }?.let { line ->
            notifier.notifyChat(sender, line.text)
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

    /** Caches which apps [senderId]'s phone can launch, for a rule editor to offer. */
    private fun rememberAppInventory(message: SyncMessage, senderId: DeviceId): Boolean {
        AppInventoryStore(appContext).setApps(senderId, AppInventoryCodec.decode(message.field(FIELD_APPS_JSON)))
        return true
    }

    private fun parseAppTime(raw: String): AppTime? {
        val at = raw.indexOf(APP_SEPARATOR)
        if (at <= 0 || at == raw.lastIndex) return null
        val minutes = raw.substring(0, at).toIntOrNull() ?: return null
        return AppTime(label = raw.substring(at + 1).take(MAX_WIRE_TEXT), minutes = minutes)
    }

    /**
     * The child's phone said it was switching off.
     *
     * Recorded as a very old heartbeat rather than as its own state: the parent's
     * screen already knows how to say "we have not heard from this phone", and a
     * shutdown is exactly that with a reason attached.
     */
    private fun rememberStopping(message: SyncMessage): Boolean {
        heartbeats.recordStopped(message.sentAt)
        return true
    }

    private fun rememberHeartbeat(message: SyncMessage): Boolean {
        val arrived = Heartbeat(
            sentAt = message.sentAt,
            policyVersion = message.longField(FIELD_POLICY_VERSION) ?: 0L,
            batteryPercent = message.longField(FIELD_BATTERY)?.toInt() ?: -1,
            managed = message.field(FIELD_MANAGED).toBoolean(),
            usageAccessGranted = message.field(FIELD_USAGE_ACCESS).toBoolean(),
            suspendedCount = message.longField(FIELD_SUSPENDED)?.toInt() ?: 0,
            historyHead = message.field(FIELD_HISTORY_HEAD).orEmpty().take(MAX_WIRE_TEXT),
            historyCount = message.longField(FIELD_HISTORY_COUNT) ?: 0L,
        )

        // Checked before the new heartbeat replaces the old, because the judgement
        // is about the pair. Once noticed it stays noticed: a rewritten history is
        // not undone by the next honest entry, and clearing it silently would
        // hand back exactly what the check was for.
        // The third argument is evidence this phone holds rather than something
        // the reporting phone said: entries it wrote that arrived here through
        // the append-only audit section, which it cannot revise from over there.
        val heldFromDevice = runCatching {
            AuditReplication(appContext).entries().count { it.by == message.sender }.toLong()
        }.getOrDefault(0L)

        if (IntegrityRules.historyRewritten(heartbeats.latest, arrived, heldFromDevice)) {
            heartbeats.historySuspect = true
        }

        heartbeats.latest = arrived
        // The device has spoken, so the next stretch of silence gets its own alert.
        heartbeats.silenceAlerted = false
        return true
    }

    private fun rememberNewApp(message: SyncMessage): Boolean {
        val packageName = message.field(FIELD_PACKAGE) ?: return false
        val app = NewApp(
            packageName = packageName.clip(),
            label = (message.field(FIELD_TARGET_LABEL) ?: packageName).clip(),
            noticedAt = message.sentAt,
        )
        newApps.remember(app)
        notifier.notifyNewApp(app)
        return true
    }

    private fun applyDecision(message: SyncMessage): Boolean {
        val id = RequestId(message.field(FIELD_REQUEST_ID) ?: return false)
        val note = message.field(FIELD_NOTE)

        return when (message.field(FIELD_STATE)) {
            RequestState.APPROVED.name -> requests.approve(id, note)
            RequestState.DENIED.name -> requests.deny(id, note)
            else -> false
        }
    }

    /**
     * Applies bonus time sent by a parent phone.
     *
     * Granting and re-evaluating at once is the point: a parent who taps
     * "+15 minutes" and sees nothing change on the child's phone stops trusting
     * the feature within a day.
     */
    private fun applyBonus(message: SyncMessage): Boolean {
        val minutes = message.longField(FIELD_MINUTES)?.toInt()?.takeIf { it > 0 } ?: return false
        val target = SyncTargets.decode(message.field(FIELD_TARGET)) ?: return false

        val policy = LocalPolicyStore(appContext).load()
        GrantStore(appContext).add(
            target = target,
            minutes = minutes,
            now = Instant.now(),
            zone = policy.timeZone,
            dayBoundaryHour = policy.dayBoundaryHour,
        )
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.BONUS_GRANTED,
            subject = TargetLabels.describe(appContext, target),
            after = Audit.minutes(minutes),
        )
        AppRegistry.ruleMutationSink.rulesChanged()
        return true
    }

    /**
     * Applies a daily limit set by a parent phone.
     *
     * The same `LocalPolicyStore` setter a parent standing at this phone would
     * use — this message is simply another writer of it, arriving on the sync
     * executor instead of the UI thread. Re-evaluating at once, same as
     * [applyBonus], is what makes the change visible on the child's screen
     * without waiting for the next scheduled tick.
     */
    private fun applyDailyLimit(message: SyncMessage): Boolean {
        val minutes = message.longField(FIELD_MINUTES)?.toInt()?.takeIf { it >= 0 } ?: return false

        val store = LocalPolicyStore(appContext)
        val before = store.dailyLimitMinutes
        store.dailyLimitMinutes = minutes
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.DAILY_LIMIT_CHANGED,
            before = Audit.minutes(before),
            after = Audit.minutes(store.dailyLimitMinutes),
        )
        AppRegistry.ruleMutationSink.rulesChanged()
        return true
    }

    /**
     * Replaces every remotely-editable rule at once.
     *
     * Refused if [PolicySnapshot.version] does not match this phone's current
     * one: the parent's copy was taken at that version, and applying a push
     * based on stale rules would silently discard whatever changed here since
     * — a schedule added, a limit lifted, anything at all — without either side
     * knowing it happened.
     */
    private fun applyPolicyUpdate(message: SyncMessage): Boolean {
        val incoming = PolicySnapshot.decode(message.field(FIELD_POLICY_JSON)) ?: return false
        val store = LocalPolicyStore(appContext)

        if (store.load().version != incoming.version) {
            Diagnostics.record(appContext, DiagnosticEvent.POLICY_UPDATE_CONFLICT)
            return false
        }

        store.applySnapshot(incoming)
        // Also recorded as ops, so the change reaches every device on the newer
        // protocol rather than only the two ends of this legacy exchange.
        PolicyReplication(appContext).pushFromLocalPolicy()
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.RULES_REPLACED,
        )
        AppRegistry.ruleMutationSink.rulesChanged()
        return true
    }

    /**
     * A request from a phone still on the older protocol.
     *
     * Folded into the replica rather than a separate inbox, so a part-updated
     * family sees one list. A newer child publishes the same request as ops and
     * this path is never reached for it.
     */
    private fun rememberRequest(message: SyncMessage): Boolean {
        val minutes = message.longField(FIELD_MINUTES)?.toInt()?.takeIf { it > 0 } ?: return false
        val id = message.field(FIELD_REQUEST_ID) ?: return false
        if (inbox.all().any { it.id == id }) return false

        val expiresAt = message.longField(FIELD_EXPIRES_AT)
            ?.let(Instant::ofEpochMilli)
            ?: message.sentAt.plusMillis(RequestRules.DEFAULT_LIFETIME.inWholeMilliseconds)

        RequestReplication(appContext).raise(
            minutes = minutes,
            reason = message.field(FIELD_REASON).orEmpty().clip(),
            target = message.field(FIELD_TARGET).orEmpty(),
            targetLabel = message.field(FIELD_TARGET_LABEL).orEmpty().clip(),
            expiresAt = expiresAt,
        ) ?: return false

        notifier.notifyIncomingRequest(
            IncomingRequest(
                id = id,
                label = message.field(FIELD_TARGET_LABEL).orEmpty().clip(),
                minutes = minutes,
                reason = message.field(FIELD_REASON).orEmpty().clip(),
                raisedAt = message.sentAt,
                expiresAt = expiresAt,
                state = RequestState.PENDING,
            ),
        )
        return true
    }

    private fun settleRequest(message: SyncMessage): Boolean {
        val id = message.field(FIELD_REQUEST_ID) ?: return false
        val state = RequestState.entries.firstOrNull { it.name == message.field(FIELD_STATE) }
            ?: return false
        if (!state.isDecided) return false

        return inbox.settle(id, state, message.field(FIELD_NOTE), decidedBy = message.sender)
    }

    /**
     * Bounds a string that arrived over the wire and is about to be shown.
     *
     * The sender holds the family key, so this is not a defence against a stranger
     * — it is a defence against a buggy or malicious phone inside the family
     * pushing a megabyte of text into a notification and a card that then cannot
     * be dismissed past.
     */
    private fun String.clip(): String = take(MAX_WIRE_TEXT)

    companion object {
        const val RESULT_NOT_CONFIGURED = "not configured"
        const val RESULT_BAD_PASSPHRASE = "passphrase too short"

        private const val TAG = "DementorSync"
        private const val POLL_OVERLAP_SECONDS = 5L

        /** Recorded as the decider when a standing rule answered, not a person. */
        private const val AUTOMATIC = "rule"
    }
}
