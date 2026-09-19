package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.security.KeystoreSigner
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.MessageCrypto
import dev.schrammel.protego_parentis.sync.core.MessageFragments
import dev.schrammel.protego_parentis.sync.core.MessageSignatures
import dev.schrammel.protego_parentis.sync.core.SyncCodec
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncMessageType
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.TransportResult
import java.time.Instant
import java.util.UUID

/**
 * How a message physically leaves the phone: sign, seal, fragment, send, and
 * record the outcome — split out from [SyncPublisher], which decides *what*
 * goes out (one method per message type, formatting fields) but should not
 * also own *how*. Every one of [SyncPublisher]'s ~25 `publish*` methods ends
 * up here through the same one call, unchanged by this split.
 */
internal class MessageTransmitter(
    context: Context,
    private val config: SyncConfigStore,
    private val roles: RoleStore,
    private val signer: KeystoreSigner,
) {

    private val appContext = context.applicationContext

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
    fun send(
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
    }
}
