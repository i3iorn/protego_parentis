package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestId
import dev.schrammel.dementor_watch.model.RequestState
import dev.schrammel.dementor_watch.policy.request.RequestRules
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * The child's requests and their answers.
 *
 * Reads always run expiry first, so an unanswered request never lingers as
 * "pending" past its deadline no matter which screen happens to open next.
 */
class RequestStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Everything, newest first, with lapsed requests already closed. */
    fun all(now: Instant): List<Request> {
        val stored = read()
        val current = RequestRules.expire(stored, now)
        if (current != stored) write(current)
        return current.sortedByDescending { it.createdAt }
    }

    fun pending(now: Instant): List<Request> = all(now).filter { it.state.isOpen }

    fun save(request: Request) {
        write(read().filterNot { it.id == request.id } + request)
    }

    fun find(id: RequestId, now: Instant): Request? = all(now).firstOrNull { it.id == id }

    /** Requests belong to the child; they leave with them. */
    fun clear() {
        prefs.edit { remove(KEY_REQUESTS) }
    }

    private fun read(): List<Request> {
        val raw = prefs.getString(KEY_REQUESTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
    }

    private fun write(requests: List<Request>) {
        prefs.edit {
            putString(KEY_REQUESTS, JSONArray(requests.map(::toJson)).toString())
        }
    }

    private fun toJson(request: Request): JSONObject = JSONObject().apply {
        put(KEY_ID, request.id.value)
        TargetJson.write(this, request.target)
        put(KEY_MINUTES, request.amount.inWholeMinutes)
        put(KEY_REASON, request.reason)
        put(KEY_STATE, request.state.name)
        put(KEY_CREATED, request.createdAt.toEpochMilli())
        put(KEY_EXPIRES, request.expiresAt.toEpochMilli())
        request.decidedAt?.let { put(KEY_DECIDED_AT, it.toEpochMilli()) }
        request.decidedBy?.let { put(KEY_DECIDED_BY, it) }
        request.decisionNote?.let { put(KEY_NOTE, it) }
    }

    private fun fromJson(json: JSONObject): Request? {
        val target = TargetJson.read(json) ?: return null
        val minutes = json.optLong(KEY_MINUTES, 0L)
        if (minutes <= 0L) return null

        val state = runCatching { RequestState.valueOf(json.optString(KEY_STATE)) }.getOrNull()
            ?: return null
        val decidedAt = json.optLong(KEY_DECIDED_AT, 0L)
            .takeIf { it > 0L }
            ?.let(Instant::ofEpochMilli)

        // A settled request with no timestamp would fail the model's invariant;
        // dropping the row is better than crashing on a corrupt file.
        if (!state.isOpen && decidedAt == null) return null

        val created = json.optLong(KEY_CREATED, 0L)
        val expires = json.optLong(KEY_EXPIRES, 0L)
        if (created <= 0L || expires <= created) return null

        return Request(
            id = RequestId(json.optString(KEY_ID)),
            target = target,
            amount = minutes.toInt().minutes,
            reason = json.optString(KEY_REASON),
            state = state,
            createdAt = Instant.ofEpochMilli(created),
            expiresAt = Instant.ofEpochMilli(expires),
            decidedAt = decidedAt,
            decidedBy = json.optString(KEY_DECIDED_BY).takeIf(String::isNotBlank),
            decisionNote = json.optString(KEY_NOTE).takeIf(String::isNotBlank),
        )
    }

    private companion object {
        const val FILE_NAME = "requests"
        const val KEY_REQUESTS = "requests"
        const val KEY_ID = "id"
        const val KEY_MINUTES = "minutes"
        const val KEY_REASON = "reason"
        const val KEY_STATE = "state"
        const val KEY_CREATED = "createdAt"
        const val KEY_EXPIRES = "expiresAt"
        const val KEY_DECIDED_AT = "decidedAt"
        const val KEY_DECIDED_BY = "decidedBy"
        const val KEY_NOTE = "note"
    }
}
