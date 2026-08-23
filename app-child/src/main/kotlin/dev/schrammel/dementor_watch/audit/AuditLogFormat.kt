package dev.schrammel.dementor_watch.audit

import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.AuditEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * How the shared history is written to disk.
 *
 * Kept apart from [Audit] so the awkward parts — the cap, the ordering, tolerating
 * a file written by a newer build — can be tested without a device.
 *
 * Decoding treats stored text as suspect. A row that no longer parses is dropped
 * rather than thrown, because losing one line of history is a great deal better
 * than an app that cannot open its own log.
 */
object AuditLogFormat {

    /**
     * The spec asks for history "for the life of the family". A preferences file
     * cannot honour that, so the log keeps the most recent [MAX_ENTRIES] and the
     * screen says so plainly rather than quietly pretending to be complete.
     */
    const val MAX_ENTRIES = 500

    /** Appends [entry], returning the new file contents. Newest is stored last. */
    fun append(raw: String?, entry: AuditEntry): String =
        encode(decodeAll(raw).takeLast(MAX_ENTRIES - 1) + entry)

    /** Every entry, oldest first. */
    fun decodeAll(raw: String?): List<AuditEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::fromJson)
        }
    }

    fun encode(entries: List<AuditEntry>): String =
        JSONArray(entries.map(::toJson)).toString()

    private fun toJson(entry: AuditEntry): JSONObject = JSONObject().apply {
        put(KEY_ID, entry.id)
        put(KEY_AT, entry.at.toEpochMilli())
        put(KEY_ACTOR, entry.actor.name)
        put(KEY_ACTION, entry.action.name)
        entry.subject?.let { put(KEY_SUBJECT, it) }
        entry.before?.let { put(KEY_BEFORE, it) }
        entry.after?.let { put(KEY_AFTER, it) }
    }

    private fun fromJson(json: JSONObject): AuditEntry? {
        // Presence rather than a positive value: a phone whose clock reads 1970
        // has a broken clock, and showing that is more honest than quietly
        // dropping the row and leaving a hole in the history.
        if (!json.has(KEY_AT)) return null
        val at = json.optLong(KEY_AT, 0L)

        // An unknown action means a newer build wrote this row. Skip it rather
        // than rendering a line the reader cannot interpret.
        val action = AuditAction.entries.firstOrNull { it.name == json.optString(KEY_ACTION) }
            ?: return null
        val actor = AuditActor.entries.firstOrNull { it.name == json.optString(KEY_ACTOR) }
            ?: return null

        return AuditEntry(
            id = json.optString(KEY_ID).takeIf(String::isNotBlank) ?: return null,
            at = Instant.ofEpochMilli(at),
            actor = actor,
            action = action,
            subject = json.optString(KEY_SUBJECT).takeIf(String::isNotBlank),
            before = json.optString(KEY_BEFORE).takeIf(String::isNotBlank),
            after = json.optString(KEY_AFTER).takeIf(String::isNotBlank),
        )
    }

    private const val KEY_ID = "id"
    private const val KEY_AT = "at"
    private const val KEY_ACTOR = "actor"
    private const val KEY_ACTION = "action"
    private const val KEY_SUBJECT = "subject"
    private const val KEY_BEFORE = "before"
    private const val KEY_AFTER = "after"
}
