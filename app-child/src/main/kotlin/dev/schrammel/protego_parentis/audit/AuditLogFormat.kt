package dev.schrammel.protego_parentis.audit

import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.AuditChain
import dev.schrammel.protego_parentis.model.AuditEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A log after an entry was added, the anchor the chain now starts from, and
 * the entry itself with its chain fields filled in — so a caller that needs
 * to carry this entry's [AuditEntry.previousHash]/[AuditEntry.hash] onward
 * (#84 — replicating a chained entry, not just its content) does not have to
 * recompute what [append] already worked out.
 */
data class Appended(val log: String, val anchor: String, val entry: AuditEntry)

/**
 * How the shared history is written to disk.
 *
 * Kept apart from [Audit] so the awkward parts — the cap, the ordering, the hash
 * chain, tolerating a file written by a newer build — can be tested without a
 * device.
 *
 * Decoding treats stored text as suspect. A row that no longer parses is dropped
 * rather than thrown, because losing one line of history is a great deal better
 * than an app that cannot open its own log. A dropped row does break the chain,
 * which is the correct outcome: something in the file is not what it was.
 */
object AuditLogFormat {

    /**
     * The spec asks for history "for the life of the family". A preferences file
     * cannot honour that, so the log keeps the most recent [MAX_ENTRIES] and the
     * screen says so plainly rather than quietly pretending to be complete.
     */
    const val MAX_ENTRIES = 500

    /**
     * Appends [entry], chaining it to whatever is already stored.
     *
     * Returns the new anchor as well as the new log: trimming the oldest entry
     * moves the point the chain starts from, and losing that would make an
     * ordinary trim indistinguishable from someone deleting the first page.
     */
    fun append(raw: String?, anchor: String, entry: AuditEntry): Appended {
        val existing = decodeAll(raw)
        val previousHash = existing.lastOrNull()?.hash ?: anchor
        val hash = AuditChain.link(entry, previousHash)

        val linked = entry.copy(
            previousHash = previousHash,
            hash = hash,
            sig = AuditSignature.sign(hash),
        )

        val combined = existing + linked
        if (combined.size <= MAX_ENTRIES) return Appended(encode(combined), anchor, linked)

        val dropped = combined.size - MAX_ENTRIES
        val kept = combined.drop(dropped)
        return Appended(encode(kept), combined[dropped - 1].hash, linked)
    }

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
        put(KEY_PREVIOUS_HASH, entry.previousHash)
        put(KEY_HASH, entry.hash)
        entry.sig?.let { put(KEY_SIG, it) }
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
            previousHash = json.optString(KEY_PREVIOUS_HASH).ifBlank { AuditChain.GENESIS },
            hash = json.optString(KEY_HASH),
            sig = json.optString(KEY_SIG).takeIf(String::isNotBlank),
        )
    }

    private const val KEY_ID = "id"
    private const val KEY_AT = "at"
    private const val KEY_ACTOR = "actor"
    private const val KEY_ACTION = "action"
    private const val KEY_SUBJECT = "subject"
    private const val KEY_BEFORE = "before"
    private const val KEY_AFTER = "after"
    private const val KEY_PREVIOUS_HASH = "prev"
    private const val KEY_HASH = "hash"
    private const val KEY_SIG = "sig"
}
