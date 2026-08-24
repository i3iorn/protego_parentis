package dev.schrammel.dementor_watch.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.ChatText
import org.json.JSONArray
import org.json.JSONObject
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

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Oldest first, which is the order a conversation is read in. */
    fun all(): List<ChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()

        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .mapNotNull { json ->
                // Sanitised again on the way out. What is on disk was clean when it
                // was written, but this costs nothing and means a tampered
                // preferences file cannot put anything on screen either.
                val text = ChatText.sanitise(json.optString(KEY_TEXT)) ?: return@mapNotNull null
                ChatMessage(
                    id = json.optString(KEY_ID).takeIf(String::isNotBlank)
                        ?: return@mapNotNull null,
                    text = text,
                    mine = json.optBoolean(KEY_MINE),
                    sender = json.optString(KEY_SENDER),
                    at = Instant.ofEpochMilli(json.optLong(KEY_AT, 0L)),
                )
            }
            .sortedBy { it.at }
    }

    /**
     * Adds a message, unless this phone already has it.
     *
     * The de-duplication is not tidiness. A message stays on the relay for its
     * retention window and every poll sees it again, so without this the
     * conversation would repeat itself on every check.
     *
     * Returns false when nothing was added, so a caller knows whether to notify.
     */
    fun add(message: ChatMessage): Boolean = synchronized(LOCK) {
        val existing = all()
        if (existing.any { it.id == message.id }) return false

        // Newest kept: takeLast, because the list is oldest first and it is the
        // start of a long conversation that stops being interesting.
        val kept = (existing + message).takeLast(MAX_MESSAGES)
        prefs.edit {
            putString(
                KEY_MESSAGES,
                JSONArray(
                    kept.map { entry ->
                        JSONObject().apply {
                            put(KEY_ID, entry.id)
                            put(KEY_TEXT, entry.text)
                            put(KEY_MINE, entry.mine)
                            put(KEY_SENDER, entry.sender)
                            put(KEY_AT, entry.at.toEpochMilli())
                        }
                    },
                ).toString(),
            )
        }
        return true
    }

    /** Wipes the conversation. Offered on the screen itself, to both sides. */
    fun forgetAll() = synchronized(LOCK) {
        prefs.edit { clear() }
    }

    private companion object {
        val LOCK = Any()

        const val FILE_NAME = "chat"

        /**
         * How much conversation a phone keeps.
         *
         * Enough to scroll back through an evening. Not enough to be a log of a
         * childhood, which is the only other place this number could go.
         */
        const val MAX_MESSAGES = 200

        const val KEY_MESSAGES = "messages"
        const val KEY_ID = "id"
        const val KEY_TEXT = "text"
        const val KEY_MINE = "mine"
        const val KEY_SENDER = "sender"
        const val KEY_AT = "at"
    }
}
