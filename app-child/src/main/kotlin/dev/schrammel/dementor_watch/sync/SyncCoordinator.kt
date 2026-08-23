package dev.schrammel.dementor_watch.sync

import android.content.Context
import android.util.Log
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.diagnostics.DiagnosticEvent
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.enforcement.RequestNotifier
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.Heartbeat
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestId
import dev.schrammel.dementor_watch.model.RequestState
import dev.schrammel.dementor_watch.policy.request.RequestRules
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.rules.AppReviewStore
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
    private val notifier by lazy { RequestNotifier(appContext) }

    fun settings(): SyncSettings = config.load()

    fun saveSettings(enabled: Boolean, serverUrl: String, topic: String, passphrase: String) {
        config.save(enabled, serverUrl, topic, passphrase)
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

    /** A parent's answer about a new app. */
    fun publishAppDecision(packageName: String, blocked: Boolean) = publish(
        type = SyncMessageType.APP_DECIDED,
        fields = mapOf(
            FIELD_PACKAGE to packageName,
            FIELD_BLOCKED to blocked.toString(),
        ),
    )

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
            val key = runCatching { MessageCrypto.deriveKey(settings.passphrase, settings.topic) }
                .getOrNull()
            if (key == null) {
                onFinished(RESULT_BAD_PASSPHRASE)
                return@execute
            }

            val client = NtfyClient(settings.serverUrl, settings.topic)
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
                roster.notice(admittance.device)
                Diagnostics.record(appContext, DiagnosticEvent.SYNC_DEVICE_PENDING)
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
        // A child phone is the only source of these; hearing one back means a
        // second child device shares the family topic, which is not supported yet.
        // An announcement has already done its work in the roster by this point.
        SyncMessageType.REQUEST_RAISED,
        SyncMessageType.HEARTBEAT,
        SyncMessageType.APP_INSTALLED,
        SyncMessageType.DEVICE_ANNOUNCE,
        -> false
    }

    private fun applyAsParent(message: SyncMessage): Boolean = when (message.type) {
        SyncMessageType.REQUEST_RAISED -> rememberRequest(message)
        // Another parent answered first. Recording that here is what stops two
        // parents answering the same request twice.
        SyncMessageType.REQUEST_DECIDED -> settleRequest(message)
        SyncMessageType.HEARTBEAT -> rememberHeartbeat(message)
        SyncMessageType.APP_INSTALLED -> rememberNewApp(message)
        // Sent by a parent, including possibly this one. Nothing to do with it.
        // An announcement has already done its work in the roster by this point.
        SyncMessageType.BONUS_GRANTED,
        SyncMessageType.APP_DECIDED,
        SyncMessageType.DEVICE_ANNOUNCE,
        -> false
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

    private fun rememberHeartbeat(message: SyncMessage): Boolean {
        heartbeats.latest = Heartbeat(
            sentAt = message.sentAt,
            policyVersion = message.longField(FIELD_POLICY_VERSION) ?: 0L,
            batteryPercent = message.longField(FIELD_BATTERY)?.toInt() ?: -1,
            managed = message.field(FIELD_MANAGED).toBoolean(),
            usageAccessGranted = message.field(FIELD_USAGE_ACCESS).toBoolean(),
            suspendedCount = message.longField(FIELD_SUSPENDED)?.toInt() ?: 0,
        )
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

    private fun publish(type: SyncMessageType, fields: Map<String, String>) {
        val settings = config.load()
        if (!settings.isUsable) return

        io.execute {
            val key = runCatching { MessageCrypto.deriveKey(settings.passphrase, settings.topic) }
                .getOrNull() ?: return@execute

            // Signing can fail on a phone whose keystore has been invalidated.
            // Publishing unsigned would be worse than not publishing: every
            // receiver refuses it anyway, and the sender would think it had spoken.
            val signed = runCatching {
                val message = SyncMessage(
                    id = UUID.randomUUID().toString(),
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

            val sent = NtfyClient(settings.serverUrl, settings.topic)
                .publish(MessageCrypto.seal(key, signed))
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

        /** Longer than any real app name or reason, short enough to render. */
        private const val MAX_WIRE_TEXT = 200
    }
}
