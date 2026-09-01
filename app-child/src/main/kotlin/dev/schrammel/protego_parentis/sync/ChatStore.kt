package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import java.time.Instant

/** One message, as this phone holds it. */
data class ChatMessage(
    /** The sender's message id, so the same message arriving twice is one row. */
    val id: String,
    val text: String,
    /** True when this phone sent it. */
    val mine: Boolean,
    /**
     * Who sent it, as the roster knew them at the time.
     *
     * Copied rather than looked up later. A name is chosen on the sending device
     * and can change; a conversation that silently rewrites who said what is worse
     * than one showing a name that has since been updated.
     */
    val sender: String,
    val at: Instant,
)

/**
 * The conversation, as far back as this phone keeps it.
 *
 * Short on purpose, and the same length on both phones. This is a way to say
 * "dinner in ten minutes" or "can I stay out", not a record — and a parental
 * control app that quietly accumulates a searchable archive of everything a child
 * has ever said to their family has become something nobody agreed to. The cap is
 * the feature, not a storage compromise.
 *
 * Nothing here is written to the shared history either. The audit log exists so a
 * child can see what was *done to them*; filing their own conversations into it
 * would be a surveillance record wearing a transparency badge.
 */
class ChatStore(context: Context) {

    private val appContext = context.applicationContext
    private val replication = ChatReplication(appContext)

    /**
     * When this phone was last told to forget the conversation.
     *
     * Local, and deliberately never replicated. "Clear" promises the other
     * phones keep their copy, and under replication a delete cannot be local —
     * a tombstone travels, and simply dropping the entries would have the next
     * digest repair fetch them straight back. So nothing is deleted at all:
     * this phone stops showing what it was holding, which is exactly what was
     * promised and the only version of it that survives a repair.
     */
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Oldest first, which is the order a conversation is read in.
     *
     * Capped on the way out rather than on the way in. The replica keeps
     * whatever the family's retention window holds; this decides how much of it
     * is a *conversation*, which is a product decision and belongs here rather
     * than in the sync layer.
     */
    fun all(): List<ChatMessage> {
        val self = LocalDeviceId.current()
        val hiddenBefore = prefs.getLong(KEY_CLEARED_AT, 0L)
        return replication.messages()
            .filter { it.at.epochSecond > hiddenBefore }
            .sortedBy { it.at }
            .takeLast(MAX_MESSAGES)
            .map { line ->
                ChatMessage(
                    id = line.id,
                    text = line.text,
                    mine = self != null && line.by == self,
                    sender = line.from,
                    at = line.at,
                )
            }
    }

    /**
     * Sends a message. Returns false when the text sanitised to nothing.
     *
     * De-duplication is no longer this store's problem: a message is a set of
     * registers under an id, so the same one arriving on every poll for the
     * length of the relay's retention window merges to exactly what is already
     * held. That was previously the whole reason [add] existed.
     */
    fun send(raw: String, senderName: String): Boolean =
        replication.send(raw, senderName) != null

    /**
     * Stops this phone showing the conversation so far.
     *
     * Local, as the confirmation says. Nothing is tombstoned, because a
     * tombstone would replicate and clear everybody's copy — which is the
     * opposite of what the button promises. The messages stay in the replica
     * until the ordinary retention window drops them, and are simply not shown
     * here any more.
     */
    fun forgetAll() {
        prefs.edit { putLong(KEY_CLEARED_AT, Instant.now().epochSecond) }
    }

    companion object {
        private const val FILE_NAME = "chat"
        private const val KEY_CLEARED_AT = "clearedAt"

        /**
         * How much of a conversation is kept.
         *
         * Short on purpose, and the same on both phones. See the note above the
         * class: the cap is the feature.
         */
        const val MAX_MESSAGES = 200
    }
}
