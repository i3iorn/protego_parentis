package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.security.KeystoreSigner
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_APP_VERSION
import dev.schrammel.protego_parentis.sync.core.Admission
import dev.schrammel.protego_parentis.sync.core.Admittance
import dev.schrammel.protego_parentis.sync.core.DecryptResult
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.FragmentAssembler
import dev.schrammel.protego_parentis.sync.core.MessageCrypto
import dev.schrammel.protego_parentis.sync.core.MessageSignatures
import dev.schrammel.protego_parentis.sync.core.ReplayGuard
import dev.schrammel.protego_parentis.sync.core.RosterRules
import dev.schrammel.protego_parentis.sync.core.SeenMessage
import dev.schrammel.protego_parentis.sync.core.SignedPayload
import dev.schrammel.protego_parentis.sync.core.Signer
import dev.schrammel.protego_parentis.sync.core.SyncCodec
import dev.schrammel.protego_parentis.sync.core.SyncKey
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.TransportResult
import java.time.Instant

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
 * decryption, replay admission, roster admission, and dispatch. Replicated
 * state — applying ops and keeping the replica current — is
 * [ReplicaMaintainer]'s.
 *
 * [publisher] exists here only because a small number of inbound messages
 * produce an outbound one in response — a locate request answered with a
 * position, most notably. Nothing here signs or seals anything itself; every
 * byte that changes hands to leave the phone still goes out through
 * [SyncPublisher]'s own signing and sealing, unchanged.
 */
class SyncReceiver(
    context: Context,
    private val publisher: SyncPublisher,
    private val signer: Signer = KeystoreSigner()
) {

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
    private val chat by lazy { ChatInboundHandler(appContext, roster, notifier, signer) }

    // The receiver delegates dispatch and replicated-state maintenance to these
    // two — see [MessageRouter] and [ReplicaMaintainer]. Lazy so a phone that
    // never receives a message pays nothing for them.
    private val replica by lazy {
        ReplicaMaintainer(
            appContext,
            roles,
            publisher,
            state,
            config,
            chat,
            requestSync,
            telemetry,
            notifier
        )
    }
    private val router by lazy {
        MessageRouter(
            appContext,
            roles,
            deviceTrust,
            childCommand,
            requestSync,
            telemetry,
            chat,
            publisher,
            replica
        )
    }

    /**
     * Fetches and applies anything the other phones have sent.
     *
     * Runs on the executor and reports back through [onFinished] so a screen can
     * show a result. Called from the enforcement tick on the child device, and on
     * resume on a parent's.
     */
    fun pollNow(minAgeMillis: Long = 0L, onFinished: (String) -> Unit = {}) {
        val settings = config.load()
        if (!settings.isUsable) {
            onFinished(RESULT_NOT_CONFIGURED)
            return
        }

        // A minimum age on the last successful poll. The realtime stream already
        // delivers every message the moment it is published, so a background
        // caller that also polls on a short timer (the enforcement tick, up to
        // every fifteen seconds when a limit is close) spends its network budget
        // re-downloading a topic it just had pushed to it — and each slow poll
        // blocks every publish and stream delivery behind it on the shared sync
        // executor. Callers that need an answer right now (a screen on resume,
        // the chat's own timer) pass the default 0 and are never gated.
        if (minAgeMillis > 0L) {
            val age = System.currentTimeMillis() - config.lastPolledAt().toEpochMilli()
            if (age in 0 until minAgeMillis) {
                onFinished(RESULT_SKIPPED_FRESH)
                return
            }
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
                    replica.keepReplicaCurrent()
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
     *
     * Deliberately does not move the poll cursor ([SyncConfigStore.lastPolledAt]).
     * The stream connects to `/sse` with no `since`, so a message published
     * while it was down is never replayed by it — only a poll fetches it. If
     * this advanced the cursor, the first message to arrive after a reconnect
     * would push it past the gap and the next poll would ask for nothing
     * older, losing that message for good. A one-shot command (an answered
     * request, bonus time, a lock) has nothing else to repair it. The poll
     * re-fetching what the stream already delivered costs a download; the
     * replay guard makes it a no-op. The same cursor also gates
     * [pollNow]'s `minAgeMillis`, so stream traffic moving it would starve the
     * tick's poll — and the replica maintenance that rides on it.
     */
    fun receiveEnvelope(key: SyncKey, envelope: String): Boolean = apply(key, envelope)

    /**
     * Opens one envelope and acts on it.
     *
     * Reads top to bottom as the pipeline it is — reassemble the wire bytes,
     * decrypt and decode them, admit the message on timing, admit the sender
     * on identity, then dispatch. Each stage below returns null/false the
     * moment it refuses the message, so this function is nothing but that
     * sequence and the plumbing between stages; the reasoning for *why* a
     * stage refuses what it refuses lives on that stage, not here.
     *
     * Anything that fails to decrypt is someone else's traffic on a public
     * topic, which is expected and silent. Anything that decrypts but fails
     * the replay guard is logged, because that is either a clock problem or
     * an attack.
     */
    private fun apply(key: SyncKey, envelope: String): Boolean {
        val whole = reassemble(envelope) ?: return false
        val opened = decrypt(key, whole) ?: return false

        // Our own messages come back from the topic; ignore them.
        if (opened.message.sender == config.load().deviceId) return false

        val now = Instant.now()
        val seen = config.seenMessages()
        if (!admitReplay(opened.message, now, seen)) return false

        val admittance = admitSender(opened.message, opened.payload, now) ?: return false
        rememberReplay(opened.message, now, seen)

        return router.dispatch(opened.message, admittance)
    }

    /**
     * A whole envelope, or one piece of one MessageFragments split apart —
     * either way, [FragmentAssembler.accept] hands back an opaque sealed
     * string once (and only once) there is a complete one to open, and both
     * a poll batch and the realtime stream feed every wire message through
     * here, so this is the one place reassembly needs to happen.
     */
    private fun reassemble(envelope: String): String? = fragments.accept(envelope)

    /** Decrypts a reassembled envelope and decodes the message inside it. */
    private fun decrypt(key: SyncKey, whole: String): OpenedMessage? {
        val plaintext = when (val opened = MessageCrypto.open(key, whole)) {
            is DecryptResult.Opened -> opened.plaintext
            is DecryptResult.Failed -> return null
        }

        val payload = MessageSignatures.split(plaintext) ?: return null
        val message = SyncCodec.decode(payload.body) ?: return null
        return OpenedMessage(message, payload)
    }

    /** Refuses a message that decrypted fine but is too old, too new, or already seen. */
    private fun admitReplay(message: SyncMessage, now: Instant, seen: List<SeenMessage>): Boolean {
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
        return true
    }

    /**
     * Who is speaking, decided from the roster rather than from the message.
     *
     * Returns the accepted admittance, or null once this stage has already
     * refused the message and recorded why.
     */
    private fun admitSender(message: SyncMessage, payload: SignedPayload, now: Instant): Admittance.Accepted? {
        return when (val admittance = RosterRules.admit(message, payload, roster.devices, now)) {
            is Admittance.Refused -> {
                Log.w(TAG, "Refused message ${message.id}: ${admittance.reason}")
                Diagnostics.record(
                    appContext,
                    DiagnosticEvent.SYNC_SENDER_REFUSED,
                    mapOf("reason" to admittance.reason.ordinal.toLong()),
                )
                null
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
                null
            }

            is Admittance.Accepted -> {
                roster.observed(
                    id = admittance.device.id,
                    name = message.senderName,
                    at = now,
                    appVersion = message.field(FIELD_APP_VERSION),
                )
                admittance
            }
        }
    }

    /** Marks an admitted message seen, so a later replay of it is refused. */
    private fun rememberReplay(message: SyncMessage, now: Instant, seen: List<SeenMessage>) {
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
    }

    /** A decrypted, decoded message alongside the signed payload it came from. */
    private data class OpenedMessage(val message: SyncMessage, val payload: SignedPayload)

    private fun syncRole(): SyncRole = roles.roleOrChild.toSyncRole()


    companion object {
        const val RESULT_NOT_CONFIGURED = "not configured"
        const val RESULT_BAD_PASSPHRASE = "passphrase too short"

        /** Returned when a gated poll was skipped because the last one is fresh. */
        const val RESULT_SKIPPED_FRESH = "skipped: recently polled"

        private const val TAG = "DementorSync"
        private const val POLL_OVERLAP_SECONDS = 5L
    }
}
