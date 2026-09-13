package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.model.ChatText
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.context.PlaceTransition
import dev.schrammel.protego_parentis.context.Position
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.FeatureFlags
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.KeystoreSigner
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.FamilyToken
import dev.schrammel.protego_parentis.sync.core.KnownDevice
import dev.schrammel.protego_parentis.sync.core.MessageCrypto
import dev.schrammel.protego_parentis.sync.core.MessageFragments
import dev.schrammel.protego_parentis.sync.core.MessageSignatures
import dev.schrammel.protego_parentis.sync.core.StateCodec
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.SyncCodec
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncMessageType
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.TransportKind
import dev.schrammel.protego_parentis.sync.core.TransportResult
import dev.schrammel.protego_parentis.sync.SyncWireFields.APP_SEPARATOR
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ACCURACY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ALLOWED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APP_PREFIX
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APP_VERSION
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APPS_JSON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_ARRIVED
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BUCKETS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_DIGEST
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_OPS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_BATTERY
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
import java.time.Instant
import java.util.UUID
import dev.schrammel.protego_parentis.sync.core.Enrolment

/**
 * Everything a phone *sends*: the family's own commands and reports, and the
 * one-shot connectivity check used while setting up a connection.
 *
 * Deliberately knows nothing about what arrives — see [SyncReceiver] for that
 * half. The two meet only at [SyncKeying], the executor and key cache neither
 * owns alone, and at the roster/replay state on disk, which both read through
 * their own store instances rather than a shared one in memory — the same
 * pattern the rest of this app already uses for a store that more than one
 * screen or thread might touch.
 */
class SyncPublisher(context: Context) {

    private val appContext = context.applicationContext
    private val config = SyncConfigStore(appContext)
    private val roles = RoleStore(appContext)
    private val signer = KeystoreSigner()
    private val chat by lazy { ChatStore(appContext) }
    private val state by lazy { ReplicatedStateStore(appContext) }

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
        // Carried as an ordinary field rather than in the envelope: older peers
        // drop unknown fields harmlessly, and the signature already covers them.
        publish(
            SyncMessageType.DEVICE_ANNOUNCE,
            mapOf(FIELD_APP_VERSION to appVersionName(appContext)),
        )
    }

    /**
     * Announces a new request to every parent phone.
     *
     * [label] travels with it because the parent phone cannot resolve a package
     * name — it holds no copy of the child's installed apps. Sending "Instagram"
     * rather than a bare identifier is the difference between a parent answering
     * from the notification and having to go and ask what was meant.
     */
    fun publishRequestRaised(request: Request, label: String) {
        // Written as replicated state: every parent phone converges on the same
        // request and, crucially, on the same answer to it. The old message
        // published a request and a separate decision, so two parents answering
        // at once left each phone believing whichever arrived last.
        RequestReplication(appContext).raise(
            minutes = request.amount.inWholeMinutes.toInt(),
            reason = request.reason,
            target = SyncTargets.encode(request.target),
            targetLabel = label,
            expiresAt = request.expiresAt,
            localId = request.id.value,
        )
        publishPendingOps()
    }

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
        remainingMinutes: Int,
        level: Int,
        topApps: List<AppTime>,
    ) {
        // Replicated state only. This used to publish a whole DAILY_SUMMARY
        // message *as well*, which meant the same figures went out twice on
        // every tick that moved them — the exact duplication the replication
        // work was meant to remove. A parent phone reads these registers now,
        // and a parent still on the older build is served by the legacy handler
        // on the receiving side rather than by sending everything twice.
        TelemetryReplication(appContext).report(
            usedMinutes = usedMinutes,
            allowedMinutes = allowedMinutes,
            bonusMinutes = bonusMinutes,
            remainingMinutes = remainingMinutes,
            level = level,
            topApps = topApps,
        )
        publishPendingOps()
    }

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

        // Written as replicated state, which is what carries it to the family
        // now. No separate CHAT_MESSAGE goes out: the ops published at the end
        // of this poll are the message, and every replica merges them into the
        // same conversation without a de-duplication pass.
        val id = ChatReplication(appContext).send(raw, settings.deviceName) ?: return null
        publishPendingOps()

        return ChatMessage(
            id = id,
            text = text,
            mine = true,
            sender = settings.deviceName,
            at = Instant.now(),
        )
    }

    /** Says the phone is going off, so silence can be told apart from shutdown. */
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

    /**
     * Tells the family that this phone has removed a device.
     *
     * Only ever called for a parent device — see [Enrolment.shouldAcceptRevoke]
     * for why every receiver, including this one's own future self reading this
     * code, must never extend the same courtesy to the managed phone. Revoking
     * that one stays local: no message, no propagation, exactly as before this
     * existed.
     */
    fun publishRevoke(device: KnownDevice) = publish(
        type = SyncMessageType.DEVICE_REVOKED,
        fields = mapOf(FIELD_REVOKED_ID to device.id.value),
    )

    /**
     * Tells the family this arrangement is over.
     *
     * Sent once, by whoever is disbanding, and also applied locally on this
     * phone — the sender does not hear its own broadcast back, so acting on it
     * here is the only way the initiating phone actually leaves too.
     */
    fun publishDisband() = publish(SyncMessageType.FAMILY_DISBANDED, emptyMap())

    // ---------- replicated state ----------

    /**
     * Sends everything this device has changed and not yet published.
     *
     * Split into several messages when a repair produces more ops than one
     * body can carry. Each batch is cleared from the queue only once the
     * transport has accepted it, so an edit made in a tunnel is republished on
     * the next attempt rather than lost.
     */
    fun publishPendingOps() {
        val queued = state.pending
        if (queued.isEmpty()) return
        StateCodec.batch(queued).forEach { batch ->
            publishOps(batch) { state.clearPending(batch) }
        }
    }

    /** Sends [ops] as one batch, calling [onSent] if the transport took it. */
    fun publishOps(ops: List<StateOp>, onSent: () -> Unit = {}) {
        if (ops.isEmpty()) return
        publish(
            type = SyncMessageType.STATE_OPS,
            fields = mapOf(FIELD_OPS to StateCodec.encodeOps(ops)),
            onSent = onSent,
        )
    }

    /**
     * Says what this device holds, so a replica that missed ops can notice.
     *
     * Cheap enough to send often and small enough not to matter when everyone
     * already agrees, which is the ordinary case.
     */
    fun publishDigest() = publish(
        type = SyncMessageType.STATE_DIGEST,
        fields = mapOf(FIELD_DIGEST to StateCodec.encodeDigest(state.digest())),
    )

    /** Asks the family for everything it has in [buckets]. */
    fun publishRepairRequest(buckets: Set<Int>) {
        if (buckets.isEmpty()) return
        publish(
            type = SyncMessageType.STATE_REPAIR,
            fields = mapOf(FIELD_BUCKETS to StateCodec.encodeBuckets(buckets)),
        )
    }

    /** Child: answers with every rule this phone currently enforces. */
    fun publishPolicySnapshot() = publish(
        type = SyncMessageType.POLICY_SNAPSHOT,
        fields = mapOf(FIELD_POLICY_JSON to LocalPolicyStore(appContext).snapshot().encode()),
    )

    /** Parent: asks the child phone for the apps it has installed. */
    fun publishAppInventoryRequest() = publish(SyncMessageType.APP_INVENTORY_REQUESTED, emptyMap())

    /** Child: answers with the apps it has installed. */
    fun publishAppInventory() = publish(
        type = SyncMessageType.APP_INVENTORY,
        fields = mapOf(FIELD_APPS_JSON to AppInventoryCodec.encode(installedInventory(appContext))),
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
        SyncKeying.execute {
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

    internal fun syncRole(): SyncRole = roles.roleOrChild.toSyncRole()

    /**
     * [id] is a parameter only because of the chat.
     *
     * A chat message is stored on the sending phone the moment it is typed, and
     * then comes back off the relay like everything else. [SyncReceiver.apply]
     * normally drops a phone's own messages by comparing the sender to this
     * device's id — but that id is read from the keystore and is the empty
     * string when the keystore call fails, and an empty id matches nothing.
     * Giving the stored copy and the published message the same id means the
     * conversation stays right even in that case, instead of quietly doubling
     * every line.
     */
    private fun publish(
        type: SyncMessageType,
        fields: Map<String, String>,
        id: String = UUID.randomUUID().toString(),
        onSent: () -> Unit = {},
    ) {
        val settings = config.load()
        if (!settings.isUsable) return

        SyncKeying.execute {
            val key = SyncKeying.keyFor(settings)
            if (key == null) {
                // Otherwise this fails with no trace anywhere: not a log line, not
                // a toast, nothing on the ntfy topic to notice is missing. A
                // publish that never left the phone deserves exactly as loud a
                // record as one the server refused.
                Diagnostics.record(appContext, DiagnosticEvent.SYNC_KEY_UNAVAILABLE)
                return@execute
            }

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

            val transport = Transports.of(settings)
            val sealed = MessageCrypto.seal(key, signed)
            val chunks = MessageFragments.split(id, sealed, transport.maxEnvelopeChars)

            // One failure stops the rest: a partial group the receiver can
            // never complete is no different from the message never having
            // been sent, so there is nothing to gain from spending the
            // remaining chunks' worth of quota on a group already lost.
            var sent: TransportResult<Unit> = TransportResult.Ok(Unit)
            for (chunk in chunks) {
                sent = transport.publish(chunk)
                if (sent is TransportResult.Failed) break
            }

            if (sent is TransportResult.Ok) onSent()
            Diagnostics.record(
                appContext,
                if (sent is TransportResult.Ok) {
                    DiagnosticEvent.SYNC_PUBLISHED
                } else {
                    DiagnosticEvent.SYNC_PUBLISH_FAILED
                },
                buildMap {
                    put("type", type.ordinal.toLong())
                    if (chunks.size > 1) put("chunks", chunks.size.toLong())
                    if (sent is TransportResult.Failed) {
                        put("kind", sent.kind.ordinal.toLong())
                        sent.httpStatus?.let { put("httpStatus", it.toLong()) }
                    }
                },
            )
        }
    }

    private companion object {
        const val TAG = "DementorSync"
        const val POLL_OVERLAP_SECONDS = 5L
    }
}
