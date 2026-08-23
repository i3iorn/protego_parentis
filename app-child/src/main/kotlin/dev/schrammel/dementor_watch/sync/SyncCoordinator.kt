package dev.schrammel.dementor_watch.sync

import android.content.Context
import android.util.Log
import dev.schrammel.dementor_watch.diagnostics.DiagnosticEvent
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestId
import dev.schrammel.dementor_watch.rules.RequestCoordinator
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
 * All network work happens on a background executor. The methods here return
 * immediately; nothing blocks the caller.
 */
class SyncCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val config = SyncConfigStore(appContext)
    private val requests = RequestCoordinator(appContext)


    fun settings(): SyncSettings = config.load()

    fun saveSettings(enabled: Boolean, serverUrl: String, topic: String, passphrase: String) {
        config.save(enabled, serverUrl, topic, passphrase)
    }

    fun suggestTopic(): String = config.suggestTopic()

    /** Announces a new request to every parent phone. */
    fun publishRequestRaised(request: Request) = publish(
        type = SyncMessageType.REQUEST_RAISED,
        fields = mapOf(
            FIELD_REQUEST_ID to request.id.value,
            FIELD_MINUTES to request.amount.inWholeMinutes.toString(),
            FIELD_REASON to request.reason,
            FIELD_TARGET to describe(request.target),
        ),
    )

    /** Confirms back to the other parents that a request has been answered. */
    fun publishDecision(request: Request) = publish(
        type = SyncMessageType.REQUEST_DECIDED,
        fields = mapOf(
            FIELD_REQUEST_ID to request.id.value,
            FIELD_STATE to request.state.name,
            FIELD_NOTE to request.decisionNote.orEmpty(),
        ),
    )

    /**
     * Fetches and applies anything the parents have sent.
     *
     * Runs on the executor and reports back through [onFinished] so the settings
     * screen can show a result. Called from the enforcement tick otherwise.
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
                        mapOf("received" to result.value.size.toLong(), "applied" to applied.toLong()),
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

        val message = SyncCodec.decode(plaintext) ?: return false
        val settings = config.load()

        // Our own messages come back from the topic; ignore them.
        if (message.sender == settings.deviceName) return false

        val seen = config.seenMessageIds()
        val admission = ReplayGuard.admit(message, Instant.now(), seen)
        if (admission is Admission.Reject) {
            Log.w(TAG, "Refused message ${message.id}: ${admission.reason}")
            Diagnostics.record(
                appContext,
                DiagnosticEvent.SYNC_MESSAGE_REFUSED,
                mapOf("reason" to admission.reason.ordinal.toLong()),
            )
            return false
        }
        config.rememberSeen(ReplayGuard.remember(seen, message.id))

        return when (message.type) {
            SyncMessageType.REQUEST_DECIDED -> applyDecision(message)
            SyncMessageType.BONUS_GRANTED -> applyBonus(message)
            // Raised requests and heartbeats are for the parent phones to read.
            SyncMessageType.REQUEST_RAISED, SyncMessageType.HEARTBEAT -> false
        }
    }

    private fun applyDecision(message: SyncMessage): Boolean {
        val id = RequestId(message.field(FIELD_REQUEST_ID) ?: return false)
        val note = message.field(FIELD_NOTE)

        return when (message.field(FIELD_STATE)) {
            STATE_APPROVED -> requests.approve(id, note)
            STATE_DENIED -> requests.deny(id, note)
            else -> false
        }
    }

    private fun applyBonus(message: SyncMessage): Boolean {
        // Deliberately not implemented yet: granting time from a message needs the
        // same target plumbing as a request, and a half-done version would silently
        // drop a parent's grant. Refusing it visibly is better.
        Log.i(TAG, "Bonus grants over sync are not handled yet (${message.id})")
        return false
    }

    private fun publish(type: SyncMessageType, fields: Map<String, String>) {
        val settings = config.load()
        if (!settings.isUsable) return

        io.execute {
            val key = runCatching { MessageCrypto.deriveKey(settings.passphrase, settings.topic) }
                .getOrNull() ?: return@execute

            val message = SyncMessage(
                id = UUID.randomUUID().toString(),
                type = type,
                sender = settings.deviceName,
                sentAt = Instant.now(),
                fields = fields,
            )
            val sent = NtfyClient(settings.serverUrl, settings.topic)
                .publish(MessageCrypto.seal(key, SyncCodec.encode(message)))
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

    private fun describe(target: GrantTarget): String = when (target) {
        is GrantTarget.Device -> "device"
        is GrantTarget.App -> target.pkg.value
        is GrantTarget.Category -> target.id.value
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
        const val FIELD_STATE = "state"
        const val FIELD_NOTE = "note"

        const val STATE_APPROVED = "APPROVED"
        const val STATE_DENIED = "DENIED"
    }
}
