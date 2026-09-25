package dev.schrammel.protego_parentis.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestId
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.policy.request.RequestRules
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

    /**
     * Decoded across instances — see [DecodeCache]. Stores here are constructed
     * per call site, so the cache keys on the raw stored string; a save from
     * another instance rewrites it and any other instance's cache misses once.
     * `all()` runs on the enforcement tick (lapsed-request checks) and on the
     * UI thread; both used to re-parse the whole list on every call.
     */
    private val cache = DecodeCache<List<Request>>()

    /**
     * Everything within the retention horizon, newest first, with lapsed
     * requests already closed.
     *
     * Synchronized on [prefs]: this both reads and, when something has expired
     * or aged out, writes back, and it runs from the UI thread and the
     * enforcement worker alike. Without a lock, a save() landing between the
     * read and the write here could be silently overwritten by the stale pass.
     */
    fun all(now: Instant): List<Request> = synchronized(prefs) {
        val stored = read()
        val current = RequestRules.prune(RequestRules.expire(stored, now), now)
        if (current != stored) write(current)
        current.sortedByDescending { it.createdAt }
    }

    fun pending(now: Instant): List<Request> = all(now).filter { it.state.isOpen }

    /**
     * Synchronized on [prefs]: a request being answered on the UI thread can race
     * a new request being raised from elsewhere, and without a lock one save()'s
     * read-modify-write could clobber the other's.
     */
    fun save(request: Request) = synchronized(prefs) {
        write(read().filterNot { it.id == request.id } + request)
    }

    fun find(id: RequestId, now: Instant): Request? = all(now).firstOrNull { it.id == id }

    /**
     * Requests belong to the child; they leave with them.
     *
     * Synchronized on [prefs] like every other mutator here: without the lock, a
     * concurrent save() could interleave with this and either resurrect a request
     * this call meant to wipe, or have its own write silently undone.
     */
    fun clear() = synchronized(prefs) {
        prefs.edit { remove(KEY_REQUESTS) }
    }

    private fun read(): List<Request> =
        cache.get(prefs.getString(KEY_REQUESTS, null), ::decode)

    private fun decode(raw: String?): List<Request> {
        if (raw == null) return emptyList()
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
