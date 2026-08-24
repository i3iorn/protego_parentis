package dev.schrammel.dementor_watch.sync

import android.content.Context
import android.util.Log
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.diagnostics.DiagnosticEvent
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.enforcement.RequestNotifier
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.ChatText
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.Heartbeat
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestId
import dev.schrammel.dementor_watch.model.RequestState
import dev.schrammel.dementor_watch.policy.IntegrityRules
import dev.schrammel.dementor_watch.policy.request.RequestRules
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.context.LocationSharing
import dev.schrammel.dementor_watch.context.PlaceTransition
import dev.schrammel.dementor_watch.context.Position
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
import dev.schrammel.dementor_watch.usage.InstalledApps
import dev.schrammel.dementor_watch.sync.MessageCrypto
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
 * is the one branch in this class: a child phone acts on answers, a parent phone
 * collects questions. Each ignores the messages meant for the other rather than
 * trying to be useful with them.
 *
 * Encryption alone was not enough to decide *who* sent something: the
 * passphrase is shared, so a child who obtained it could publish an approval
 * of their own request. Every message now also carries a per-device signature,
 * and the sender's role is read from an enrolled roster rather than from the
 * message — see [RosterRules].
 *
 * All network work happens on a background executor. The methods here return
 * immediately; nothing blocks the caller.
 */
class SyncCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val config = SyncConfigStore(appContext)
    private val roles = RoleStore(appContext)
    private val roster = RosterStore(appContext)
    private val signer = KeystoreSigner()

    // Both sides construct one of these and only one side ever uses it, so
    // neither store is touched on a phone that has no business holding it.
    private val requests by lazy { RequestCoordinator(appContext) }
    private val inbox by lazy { InboxStore(appContext) }
    private val newApps by lazy { NewAppStore(appContext) }
    private val heartbeats by lazy { HeartbeatStore(appContext) }
    private val summaries by lazy { DailySummaryStore(appContext) }
    private val family by lazy { FamilyEventStore(appContext) }
    private val chat by lazy { ChatStore(appContext) }
    private val notifier by lazy { RequestNotifier(appContext) }

    fun settings(): SyncSettings = config.load()

    /** Joins the family described by [token]. */
    fun join(token: FamilyToken, passphrase: String) {
        config.join(token, passphrase)
        config.announceSoon()
    }

    /** Leaves, keeping nothing behind that could reconnect or decrypt on its own. */
    fun leave() {
        config.save(
            enabled = false,
            transport = TransportKind.NTFY,
            serverUrl = SyncSettings.DEFAULT_SERVER,
            // Not a usable topic, which is the point: FamilyToken refuses it, so
            // nothing can rebuild a connection out of what is left behind.
            topic = "",
            passphrase = "",
        )
        // save() ignores a blank passphrase on purpose, so leaving has to say so
        // explicitly or the family's key stays on a phone that has left it.
        config.forgetPassphrase()
        // And everything the family told us. Clearing the connection alone left a
        // parent's phone holding the child's last position, their recent arrivals
        // and the sites they had reached — a dossier on a phone that had walked
        // away from the family.
        FamilyData.forget(appContext)
    }

    fun suggestTopic(): String = config.suggestTopic()

    /** This phone's fingerprint, for reading aloud when enrolling it elsewhere. */
    fun deviceId(): DeviceId? = runCatching { signer.deviceId }.getOrNull()

    /**
     * Introduces this phone to the family (DEVICE_ANNOUNCE).
     *
     * Sent on a slow repeat rather than once, because the phone that needs to hear
     * it may not have been configured when the first one went out. Costs one
     * message an hour and removes a whole class of "why can it not see my phone".
     */
    fun announceSelf(force: Boolean = false) {
        val now = Instant.now()
        if (!force && !config.announceDue(now)) return
        config.recordAnnouncedAt(now)
        publish(SyncMessageType.DEVICE_ANNOUNCE, emptyMap())
    }

    /**
     * Announces a new request to every parent phone.
     *
     * [label] travels with it because the parent phone cannot resolve a package
     * name — it holds no copy of the child's installed apps. Sending "Instagram"
     * rather than a bare identifier is the difference between a parent answering
     * from the notification and having to go and ask what was meant.
     */
    fun publishRequestRaised(request: Request, label: String) = publish(
        type = SyncMessageType.REQUEST_RAISED,
        fields = mapOf(
            FIELD_REQUEST_ID to request.id.value,
            FIELD_MINUTES to request.amount.inWholeMinutes.toString(),
            FIELD_REASON to request.reason,
            FIELD_TARGET to SyncTargets.encode(request.target),
            FIELD_TARGET_LABEL to label,
            FIELD_EXPIRES_AT to request.expiresAt.toEpochMilli().toString(),
        ),
    )

    /** Confirms back to the other parents that a request has been answered. */
    fun publishDecision(request: Request) = publishDecision(
        id = request.id.value,
        state = request.state,
        note = request.decisionNote,
    )

    fun publishDecision(id: String, state: RequestState, note: String?) = publish(
        type = SyncMessageType.REQUEST_DECIDED,
        fields = mapOf(
            FIELD_REQUEST_ID to id,
            FIELD_STATE to state.name,
            FIELD_NOTE to note.orEmpty(),
        ),
    )

    /**
     * Sends bonus time to the child's phone.
     *
     * Device-wide only, and not an oversight: a parent phone holds no app list, so
     * offering "20 minutes of Instagram" here would mean asking a parent to pick
     * from a list of package names.
     */
    fun publishBonus(minutes: Int) = publish(
        type = SyncMessageType.BONUS_GRANTED,
        fields = mapOf(
            FIELD_MINUTES to minutes.toString(),
            FIELD_TARGET to SyncTargets.encode(GrantTarget.Device),
        ),
    )

    /**
     * Says the child device is still alive and still working (TR-2).
     *
     * Throttled against the clock rather than left to the caller, because the
     * enforcement tick fires as often as every fifteen seconds when a limit is
     * close, and a heartbeat every fifteen seconds is a battery bug.
     */
    fun publishHeartbeat(heartbeat: Heartbeat, force: Boolean = false) {
        // A heartbeat is the managed phone's statement about itself. Sending one
        // from anywhere else would put a second device's battery and permission
        // state into the parents' view of the child's phone.
        if (roles.roleOrChild != DeviceRole.CHILD) return
        // Checked before the throttle, so connecting a family does not then wait a
        // quarter of an hour for a heartbeat that could have gone at once.
        if (!config.load().isUsable) return
        if (!force && !config.heartbeatDue(heartbeat.sentAt)) return
        config.recordHeartbeatAt(heartbeat.sentAt)
        publish(
            type = SyncMessageType.HEARTBEAT,
            fields = mapOf(
                FIELD_POLICY_VERSION to heartbeat.policyVersion.toString(),
                FIELD_BATTERY to heartbeat.batteryPercent.toString(),
                FIELD_MANAGED to heartbeat.managed.toString(),
                FIELD_USAGE_ACCESS to heartbeat.usageAccessGranted.toString(),
                FIELD_SUSPENDED to heartbeat.suspendedCount.toString(),
                FIELD_HISTORY_HEAD to heartbeat.historyHead,
                FIELD_HISTORY_COUNT to heartbeat.historyCount.toString(),
            ),
        )
    }

    /**
     * Tells the parents an app they have not seen appeared on the phone (AC-3).
     *
     * The label travels for the same reason it does on a request: a parent phone
     * has no package manager to ask, and "one-tap allow or block" is not a decision
     * anyone can make about `com.zhiliaoapp.musically`.
     */
    fun publishAppInstalled(packageName: String, label: String) = publish(
        type = SyncMessageType.APP_INSTALLED,
        fields = mapOf(
            FIELD_PACKAGE to packageName,
            FIELD_TARGET_LABEL to label,
        ),
    )

    /**
     * Sends today's totals to the parents (RPT-1).
     *
     * The one message carrying what the child did rather than what the app did,
     * and kept to a summary for that reason: totals and the few apps that account
     * for most of them, with no times attached. The child sees the identical
     * figures on their own screen.
     *
     * Rides the heartbeat's throttle rather than its own, because the two answer
     * the same question from a parent and there is no sense in one being fresh
     * while the other is a quarter of an hour stale.
     */
    fun publishDailySummary(
        usedMinutes: Int,
        allowedMinutes: Int,
        bonusMinutes: Int,
        level: Int,
        topApps: List<AppTime>,
    ) = publish(
        type = SyncMessageType.DAILY_SUMMARY,
        fields = buildMap {
            put(FIELD_USED, usedMinutes.toString())
            put(FIELD_ALLOWED, allowedMinutes.toString())
            put(FIELD_BONUS, bonusMinutes.toString())
            put(FIELD_LEVEL, level.toString())
            topApps.forEachIndexed { index, app ->
                put("$FIELD_APP_PREFIX$index", "${app.minutes}$APP_SEPARATOR${app.label}")
            }
        },
    )

    /** Asks the child's phone to lock its screen now. */
    fun publishLockNow() = publish(SyncMessageType.LOCK_NOW, emptyMap())

    /**
     * Asks where the phone is (LOC-2).
     *
     * The asking is itself published, so the request is on the record before any
     * answer is. A parent cannot look without the looking being visible.
     */
    fun publishLocateRequest() = publish(SyncMessageType.LOCATE_REQUEST, emptyMap())

    /** One position, in answer to one request. Never sent unasked, except in an SOS. */
    fun publishLocation(position: Position, forEmergency: Boolean) = publish(
        type = SyncMessageType.LOCATION_REPORT,
        fields = mapOf(
            FIELD_LATITUDE to position.latitude.toString(),
            FIELD_LONGITUDE to position.longitude.toString(),
            FIELD_ACCURACY to position.accuracyMetres.toString(),
            FIELD_EMERGENCY to forEmergency.toString(),
        ),
    )

    /** Arrived at, or left, a named place. Carries the name and no coordinates. */
    fun publishPlaceTransition(transition: PlaceTransition) = publish(
        type = SyncMessageType.PLACE_TRANSITION,
        fields = mapOf(
            FIELD_PLACE to transition.place.name,
            FIELD_ARRIVED to transition.arrived.toString(),
        ),
    )

    fun publishSosRaised() = publish(SyncMessageType.SOS_RAISED, emptyMap())

    fun publishSosCleared() = publish(SyncMessageType.SOS_CLEARED, emptyMap())

    /** A standing rule answered a request, so the parents' inboxes can settle it. */
    fun publishAutoApproved(request: Request, label: String, ruleName: String) = publish(
        type = SyncMessageType.REQUEST_AUTO_APPROVED,
        fields = mapOf(
            FIELD_REQUEST_ID to request.id.value,
            FIELD_MINUTES to request.amount.inWholeMinutes.toString(),
            FIELD_TARGET_LABEL to label,
            FIELD_RULE_NAME to ruleName,
        ),
    )

    /** Today's domains, counted (WF-3). */
    fun publishWebSummary(domains: List<Pair<String, Int>>, blocked: Int) = publish(
        type = SyncMessageType.WEB_SUMMARY,
        fields = buildMap {
            put(FIELD_BLOCKED_COUNT, blocked.toString())
            domains.forEachIndexed { index, (domain, count) ->
                put("$FIELD_DOMAIN_PREFIX$index", "$count$APP_SEPARATOR$domain")
            }
        },
    )

    /** Says the phone is going off, so silence can be told apart from shutdown. */
    /**
     * Sends a message somebody typed.
     *
     * Sanitised here rather than only in the screen that collected it. Doing it on
     * the way out means what the sender sees in their own history is exactly what
     * the other phones will show, and there is no path that publishes text this
     * app would refuse to display.
     *
     * Returns the stored message, or null when there was nothing worth sending.
     */
    fun publishChat(raw: String): ChatMessage? {
        if (!FeatureFlags(appContext).isEnabled(Feature.CHAT)) return null
        // Checked before anything is stored. publish() quietly does nothing on an
        // unconfigured phone, which for every other message is right — but here it
        // would leave the sender looking at their own message in a conversation
        // nobody else is ever going to receive.
        val settings = config.load()
        if (!settings.isUsable) return null

        val text = ChatText.sanitise(raw) ?: return null

        val id = UUID.randomUUID().toString()
        val mine = ChatMessage(
            id = id,
            text = text,
            mine = true,
            sender = settings.deviceName,
            at = Instant.now(),
        )
        chat.add(mine)
        publish(
            type = SyncMessageType.CHAT_MESSAGE,
            fields = mapOf(FIELD_TEXT to text),
            id = id,
        )
        return mine
    }

    fun publishStopping() = publish(SyncMessageType.DEVICE_STOPPING, emptyMap())

    /**
     * Tells the family that this phone has let a device in.
     *
     * Sent by a parent as it approves, and acted on by everyone who already
     * trusts the sender. This is how a second parent's phone becomes usable
     * without anyone touching the child's device — and it is the whole reason the
     * child no longer has an approve button.
     */
    fun publishVouch(device: KnownDevice) = publish(
        type = SyncMessageType.DEVICE_VOUCHED,
        fields = mapOf(
            FIELD_VOUCHED_ID to device.id.value,
            FIELD_VOUCHED_KEY to device.publicKey,
            FIELD_VOUCHED_ROLE to device.role.name,
            FIELD_TARGET_LABEL to device.name,
        ),
    )

    /** A parent's answer about a new app. */
    fun publishAppDecision(packageName: String, blocked: Boolean) = publish(
        type = SyncMessageType.APP_DECIDED,
        fields = mapOf(
            FIELD_PACKAGE to packageName,
            FIELD_BLOCKED to blocked.toString(),
        ),
    )

    /**
     * Tries an address without moving to it.
     *
     * Reaching a server proves only that it answers — the family is not there yet
     * and nothing will be readable. That is still worth knowing: the failure this
     * catches is a mistyped host, which otherwise produces a family that looks
     * connected, hears nothing, and is indistinguishable from a quiet week.
     */
    fun testReachable(token: FamilyToken, onFinished: (String) -> Unit) {
        io.execute {
            val transport = Transports.of(
                config.load().copy(
                    transport = token.kind,
                    serverUrl = token.serverUrl,
                    topic = token.topic,
                ),
            )
            when (val result = transport.poll(Instant.now().minusSeconds(POLL_OVERLAP_SECONDS))) {
                is TransportResult.Ok -> onFinished("reachable")
                is TransportResult.Failed -> onFinished(result.message)
            }
        }
    }

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

        io.execute {
            val key = keyFor(settings)
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

    private fun syncRole(): SyncRole = when (roles.roleOrChild) {
        DeviceRole.CHILD -> SyncRole.CHILD
        DeviceRole.PARENT -> SyncRole.PARENT
    }

    private fun applyAsChild(message: SyncMessage): Boolean = when (message.type) {
        SyncMessageType.REQUEST_DECIDED -> applyDecision(message)
        SyncMessageType.BONUS_GRANTED -> applyBonus(message)
        SyncMessageType.APP_DECIDED -> applyAppDecision(message)
        SyncMessageType.LOCK_NOW -> applyLockNow()
        SyncMessageType.LOCATE_REQUEST -> applyLocateRequest()
        SyncMessageType.DEVICE_VOUCHED -> applyVouch(message)
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
        publishLocation(position, forEmergency = false)
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
        Audit.record(
            context = appContext,
            actor = AuditActor.PARENT,
            action = AuditAction.DEVICE_APPROVED,
            subject = vouch.name.ifBlank { vouch.id.value },
            after = vouch.role.name,
        )
        return true
    }

    private fun rememberSummary(message: SyncMessage): Boolean {
        summaries.latest = DailySummary(
            receivedAt = message.sentAt,
            usedMinutes = message.longField(FIELD_USED)?.toInt() ?: 0,
            allowedMinutes = message.longField(FIELD_ALLOWED)?.toInt() ?: 0,
            bonusMinutes = message.longField(FIELD_BONUS)?.toInt() ?: 0,
            level = message.longField(FIELD_LEVEL)?.toInt() ?: 0,
            topApps = message.fields
                .filterKeys { it.startsWith(FIELD_APP_PREFIX) }
                // Numeric order, or "app10" would sort between "app1" and "app2".
                .toSortedMap(compareBy { it.removePrefix(FIELD_APP_PREFIX).toIntOrNull() ?: 0 })
                .values
                .mapNotNull(::parseAppTime)
                // The sender publishes five. A phone that sends five thousand is
                // either broken or trying it on, and either way this screen is
                // not the place to find out.
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

    /**
     * The family key, derived once and kept.
     *
     * Deriving it stretches the passphrase with 210,000 rounds of PBKDF2, which is
     * the right cost to pay against someone guessing at the passphrase and quite
     * the wrong cost to pay every fifteen seconds — which is how often the
     * enforcement tick polls while a limit is close.
     *
     * Cached against the passphrase and topic it was derived from, so changing
     * either produces a new key rather than a stale one. Only ever touched on the
     * sync executor, which is single-threaded.
     */
    private fun keyFor(settings: SyncSettings): SyncKey? {
        val identity = settings.passphrase to settings.topic
        cachedKey?.let { (forIdentity, key) -> if (forIdentity == identity) return key }

        val derived = runCatching {
            MessageCrypto.deriveKey(settings.passphrase, settings.topic)
        }.getOrNull() ?: return null

        cachedKey = identity to derived
        return derived
    }

    /**
     * [id] is a parameter only because of the chat.
     *
     * A chat message is stored on the sending phone the moment it is typed, and
     * then comes back off the relay like everything else. [apply] normally drops a
     * phone’s own messages by comparing the sender to this device’s id — but that
     * id is read from the keystore and is the empty string when the keystore call
     * fails, and an empty id matches nothing. Giving the stored copy and the
     * published message the same id means the conversation stays right even in
     * that case, instead of quietly doubling every line.
     */
    private fun publish(
        type: SyncMessageType,
        fields: Map<String, String>,
        id: String = UUID.randomUUID().toString(),
    ) {
        val settings = config.load()
        if (!settings.isUsable) return

        io.execute {
            val key = keyFor(settings) ?: return@execute

            // Signing can fail on a phone whose keystore has been invalidated.
            // Publishing unsigned would be worse than not publishing: every
            // receiver refuses it anyway, and the sender would think it had spoken.
            val signed = runCatching {
                val message = SyncMessage(
                    id = id,
                    type = type,
                    sender = signer.deviceId.value,
                    sentAt = Instant.now(),
                    fields = fields,
                    senderKey = signer.publicKeyBase64,
                    senderRole = syncRole(),
                    senderName = settings.deviceName,
                )
                MessageSignatures.sign(signer, SyncCodec.encode(message))
            }.getOrElse {
                Log.w(TAG, "Could not sign; nothing published", it)
                Diagnostics.record(appContext, DiagnosticEvent.SYNC_PUBLISH_FAILED)
                return@execute
            }

            val sent = Transports.of(settings).publish(MessageCrypto.seal(key, signed))
            Diagnostics.record(
                appContext,
                if (sent is TransportResult.Ok) {
                    DiagnosticEvent.SYNC_PUBLISHED
                } else {
                    DiagnosticEvent.SYNC_PUBLISH_FAILED
                },
                mapOf("type" to type.ordinal.toLong()),
            )
        }
    }

    companion object {
        private const val TAG = "DementorSync"

        /**
         * Shared, and deliberately not per-instance.
         *
         * Several screens construct a coordinator, one of them on every resume.
         * A per-instance executor meant a leaked thread each time. Single-threaded
         * so concurrent polls cannot interleave writes to the replay-guard state.
         */
        private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dementor-sync").apply { isDaemon = true }
        }
        /**
         * Guarded by [io] being single-threaded; never read from anywhere else.
         */
        @Volatile
        private var cachedKey: Pair<Pair<String, String>, SyncKey>? = null

        private const val POLL_OVERLAP_SECONDS = 5L

        const val RESULT_NOT_CONFIGURED = "not configured"
        const val RESULT_BAD_PASSPHRASE = "passphrase too short"

        const val FIELD_REQUEST_ID = "requestId"
        const val FIELD_MINUTES = "minutes"
        const val FIELD_REASON = "reason"
        const val FIELD_TARGET = "target"
        const val FIELD_TARGET_LABEL = "targetLabel"
        const val FIELD_EXPIRES_AT = "expiresAt"
        const val FIELD_STATE = "state"
        const val FIELD_NOTE = "note"
        const val FIELD_PACKAGE = "package"
        const val FIELD_BLOCKED = "blocked"
        const val FIELD_POLICY_VERSION = "policyVersion"
        const val FIELD_BATTERY = "battery"
        const val FIELD_MANAGED = "managed"
        const val FIELD_USAGE_ACCESS = "usageAccess"
        const val FIELD_SUSPENDED = "suspended"
        const val FIELD_HISTORY_HEAD = "historyHead"
        const val FIELD_HISTORY_COUNT = "historyCount"
        const val FIELD_USED = "used"
        const val FIELD_ALLOWED = "allowed"
        const val FIELD_BONUS = "bonus"
        const val FIELD_LEVEL = "level"
        const val FIELD_APP_PREFIX = "app"
        const val MAX_SUMMARY_APPS = 10

        const val FIELD_RULE_NAME = "ruleName"
        const val FIELD_VOUCHED_ID = "vouchedId"
        const val FIELD_VOUCHED_KEY = "vouchedKey"
        const val FIELD_VOUCHED_ROLE = "vouchedRole"
        const val FIELD_LATITUDE = "lat"
        const val FIELD_LONGITUDE = "lon"
        const val FIELD_ACCURACY = "accuracy"
        const val FIELD_EMERGENCY = "emergency"
        const val FIELD_PLACE = "place"
        const val FIELD_ARRIVED = "arrived"
        const val FIELD_BLOCKED_COUNT = "blockedCount"
        const val FIELD_DOMAIN_PREFIX = "domain"
        const val FIELD_TEXT = "text"
        const val MAX_SUMMARY_DOMAINS = 40

        /** Minutes, then the label — so a label containing a digit cannot confuse it. */
        const val APP_SEPARATOR = ':' 

        /** Longer than any real app name or reason, short enough to render. */
        private const val MAX_WIRE_TEXT = 200
    }
}
