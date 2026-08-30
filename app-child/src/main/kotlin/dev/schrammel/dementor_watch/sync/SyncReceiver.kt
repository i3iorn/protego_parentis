package dev.schrammel.dementor_watch.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.diagnostics.DiagnosticEvent
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.enforcement.RequestNotifier
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.ChatText
import dev.schrammel.dementor_watch.model.Heartbeat
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.RequestId
import dev.schrammel.dementor_watch.model.RequestState
import dev.schrammel.dementor_watch.context.LocationSharing
import dev.schrammel.dementor_watch.policy.IntegrityRules
import dev.schrammel.dementor_watch.policy.request.RequestRules
import dev.schrammel.dementor_watch.rules.AppReviewStore
import dev.schrammel.dementor_watch.rules.Feature
import dev.schrammel.dementor_watch.rules.FeatureFlags
import dev.schrammel.dementor_watch.rules.GrantStore
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.rules.RequestCoordinator
import dev.schrammel.dementor_watch.rules.TargetLabels
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.KeystoreSigner
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.ui.HomeActivity
import dev.schrammel.dementor_watch.ui.JoinFamilyActivity
import dev.schrammel.dementor_watch.usage.InstalledApps
import dev.schrammel.dementor_watch.sync.SyncWireFields.APP_SEPARATOR
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_ACCURACY
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_ALLOWED
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_APP_PREFIX
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_ARRIVED
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_BATTERY
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_BLOCKED
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_BLOCKED_COUNT
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_BONUS
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_DOMAIN_PREFIX
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_EMERGENCY
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_EXPIRES_AT
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_HISTORY_COUNT
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_HISTORY_HEAD
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_LATITUDE
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_LEVEL
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_LONGITUDE
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_MANAGED
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_MINUTES
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_NOTE
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_PACKAGE
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_PLACE
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_POLICY_VERSION
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_REASON
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_REMAINING
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_REQUEST_ID
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_REVOKED_ID
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_RULE_NAME
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_STATE
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_SUSPENDED
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_TARGET
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_TARGET_LABEL
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_TEXT
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_USAGE_ACCESS
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_USED
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_VOUCHED_ID
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_VOUCHED_KEY
import dev.schrammel.dementor_watch.sync.SyncWireFields.FIELD_VOUCHED_ROLE
import dev.schrammel.dementor_watch.sync.SyncWireFields.MAX_SUMMARY_APPS
import dev.schrammel.dementor_watch.sync.SyncWireFields.MAX_SUMMARY_DOMAINS
import dev.schrammel.dementor_watch.sync.SyncWireFields.MAX_WIRE_TEXT
import java.time.Instant

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
                    Diagnostics.record(appContext, DiagnosticEvent.SYNC_POLL_FAILED)
                    onFinished(result.message)
                }

                is TransportResult.Ok -> {
                    val applied = result.value.count { envelope -> apply(key, envelope) }
                    config.recordPolledAt(Instant.now())
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
        val seen = config.seenMessageIds()
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
        when (val admittance = RosterRules.admit(message, payload, roster.devices, now)) {
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
            )
        }

        config.rememberSeen(ReplayGuard.remember(seen, message.id))

        return when (roles.roleOrChild) {
            DeviceRole.CHILD -> applyAsChild(message)
            DeviceRole.PARENT -> applyAsParent(message)
        }
    }

    private fun syncRole(): SyncRole = roles.roleOrChild.toSyncRole()

    private fun applyAsChild(message: SyncMessage): Boolean = when (message.type) {
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
        -> false
    }

    private fun applyAsParent(message: SyncMessage): Boolean = when (message.type) {
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
        // Sent by a parent, including possibly this one. Nothing to do with it.
        // An announcement has already done its work in the roster by this point.
        SyncMessageType.BONUS_GRANTED,
        SyncMessageType.APP_DECIDED,
        SyncMessageType.DEVICE_ANNOUNCE,
        SyncMessageType.LOCK_NOW,
        SyncMessageType.SET_DAILY_LIMIT,
        SyncMessageType.LOCATE_REQUEST,
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
        if (!FeatureFlags(appContext).isEnabled(Feature.LOCATION)) {
            Log.i(TAG, "Asked for a position while locating is switched off")
            return true
        }

        val position = LocationSharing(appContext).locate()
        if (position == null) {
            Log.i(TAG, "Asked for a position, could not get one")
            return true
        }
        Audit.record(appContext, AuditActor.CHILD, AuditAction.LOCATION_SHARED)
        publisher.publishLocation(position, forEmergency = false)
        return true
    }

    // ---------- what a parent does with what arrives ----------

    private fun settleAutoApproved(message: SyncMessage): Boolean {
        val id = message.field(FIELD_REQUEST_ID) ?: return false
        val rule = message.field(FIELD_RULE_NAME)?.clip()

        // A request answered by a standing rule never sat pending, so it was
        // never published as a request and this phone has never heard of it.
        // Recorded here as already-answered, or a parent's history of what was
        // granted would have a hole exactly where the automation was.
        if (!inbox.settle(id, RequestState.APPROVED, rule)) {
            val minutes = message.longField(FIELD_MINUTES)?.toInt()?.takeIf { it > 0 }
                ?: return false
            inbox.remember(
                IncomingRequest(
                    id = id,
                    label = message.field(FIELD_TARGET_LABEL).orEmpty().clip(),
                    minutes = minutes,
                    reason = rule.orEmpty(),
                    raisedAt = message.sentAt,
                    // Settled the moment it was asked, so it has no window in
                    // which anybody could have answered it differently.
                    expiresAt = message.sentAt.plusMillis(1L),
                    state = RequestState.APPROVED,
                    decidedAt = message.sentAt,
                    decisionNote = rule,
                ),
            )
        }
        notifier.notifyAutoApproved(rule.orEmpty())
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

        val added = chat.add(
            ChatMessage(
                id = message.id,
                text = text,
                mine = false,
                sender = sender,
                at = message.sentAt,
            ),
        )
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
        EnforcementService.start(appContext)
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

    private fun rememberSummary(message: SyncMessage): Boolean {
        summaries.latest = DailySummary(
            receivedAt = message.sentAt,
            usedMinutes = message.longField(FIELD_USED)?.toInt() ?: 0,
            allowedMinutes = message.longField(FIELD_ALLOWED)?.toInt() ?: 0,
            bonusMinutes = message.longField(FIELD_BONUS)?.toInt() ?: 0,
            remainingMinutes = message.longField(FIELD_REMAINING)?.toInt()
                // Older senders didn't send this field; fall back to the old
                // (slightly lossy) computation rather than dropping the message.
                ?: ((message.longField(FIELD_ALLOWED)?.toInt() ?: 0) +
                    (message.longField(FIELD_BONUS)?.toInt() ?: 0) -
                    (message.longField(FIELD_USED)?.toInt() ?: 0)).coerceAtLeast(0),
            level = message.longField(FIELD_LEVEL)?.toInt() ?: 0,
            topApps = message.fields
                .filterKeys { it.startsWith(FIELD_APP_PREFIX) }
                // Numeric order, or "app10" would sort between "app1" and "app2".
                .toSortedMap(compareBy { it.removePrefix(FIELD_APP_PREFIX).toIntOrNull() ?: 0 })
                .values
                .mapNotNull(::parseAppTime)
                // The sender publishes at most ten. A phone that sends five
                // thousand is either broken or trying it on, and either way this
                // screen is not the place to find out.
                .take(MAX_SUMMARY_APPS),
        )
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
        if (IntegrityRules.historyRewritten(heartbeats.latest, arrived)) {
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
        EnforcementService.start(appContext)
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
        EnforcementService.start(appContext)
        return true
    }

    private fun rememberRequest(message: SyncMessage): Boolean {
        val minutes = message.longField(FIELD_MINUTES)?.toInt()?.takeIf { it > 0 } ?: return false
        val id = message.field(FIELD_REQUEST_ID) ?: return false

        // An older child build sends no expiry. Deriving one from the default
        // lifetime stops the inbox holding a request open forever.
        val expiresAt = message.longField(FIELD_EXPIRES_AT)
            ?.let(Instant::ofEpochMilli)
            ?: message.sentAt.plusMillis(RequestRules.DEFAULT_LIFETIME.inWholeMilliseconds)

        val request = IncomingRequest(
            id = id,
            label = message.field(FIELD_TARGET_LABEL).orEmpty().clip(),
            minutes = minutes,
            reason = message.field(FIELD_REASON).orEmpty().clip(),
            raisedAt = message.sentAt,
            expiresAt = expiresAt,
            state = RequestState.PENDING,
        )
        inbox.remember(request)
        notifier.notifyIncomingRequest(request)
        return true
    }

    private fun settleRequest(message: SyncMessage): Boolean {
        val id = message.field(FIELD_REQUEST_ID) ?: return false
        val state = RequestState.entries.firstOrNull { it.name == message.field(FIELD_STATE) }
            ?: return false
        if (!state.isDecided) return false

        return inbox.settle(id, state, message.field(FIELD_NOTE))
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
    }
}
