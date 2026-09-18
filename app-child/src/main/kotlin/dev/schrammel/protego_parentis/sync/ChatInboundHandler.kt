package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.ChatText
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.FeatureFlags
import dev.schrammel.protego_parentis.security.KeystoreSigner
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TEXT
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.SyncMessage

/**
 * Family chat, both directions it can arrive by: as its own message
 * ([rememberChat], from a phone still on the older protocol) or as
 * replicated ops ([announceNewChat], called from
 * [SyncReceiver.onStateChanged] once a chat section op has landed). Kept
 * together because both end in the same notification and both have to
 * re-derive the sender's name from the roster rather than trust the wire.
 */
internal class ChatInboundHandler(
    context: Context,
    private val roster: RosterStore,
    private val notifier: RequestNotifier,
    private val signer: KeystoreSigner,
) {
    private val appContext = context.applicationContext

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
    fun rememberChat(message: SyncMessage): Boolean {
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

    /**
     * Buzzes once for a conversation that moved, not once per register.
     *
     * A message is several ops — text, sender, author — so notifying per op
     * would buzz three times for one line. Only the text register is counted,
     * and only for messages this phone did not itself write.
     */
    fun announceNewChat(applied: List<StateOp>) {
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
}
