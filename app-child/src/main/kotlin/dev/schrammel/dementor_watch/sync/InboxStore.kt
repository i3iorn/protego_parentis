package dev.schrammel.dementor_watch.sync

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.RequestState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A request as a parent phone sees it.
 *
 * Deliberately not the domain `Request`. A parent phone genuinely knows less: it
 * has no copy of the child's installed apps or categories, so it cannot resolve a
 * package name into something a human would recognise. The child sends a label
 * along with the request, and this type is honest about the fact that the label
 * is all the parent has.
 */
data class IncomingRequest(
    val id: String,
    /** Human text supplied by the child's phone: an app name, a category, or "". */
    val label: String,
    val minutes: Int,
    val reason: String,
    val raisedAt: Instant,
    val expiresAt: Instant,
    val state: RequestState,
    val decidedAt: Instant? = null,
    val decisionNote: String? = null,
) {
    fun isOpenAt(now: Instant): Boolean = state.isOpen && now.isBefore(expiresAt)
}

/**
 * What the parent phone has heard from the child.
 *
 * Kept separate from `RequestStore`, which holds the child's own requests. Sharing
 * one store would mean a parent phone's inbox and a child phone's outbox occupying
 * the same file with different meanings — the sort of overloading that produces a
 * bug nobody can reproduce.
 *
 * Reads settle anything that has run out of time, so a request never sits in the
 * inbox looking answerable hours after the child gave up on it.
 */
class InboxStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Everything the parent has heard about, newest first. */
    fun all(now: Instant = Instant.now()): List<IncomingRequest> {
        val stored = read()
        val current = stored.map { request ->
            if (request.state.isOpen && !now.isBefore(request.expiresAt)) {
                request.copy(state = RequestState.EXPIRED, decidedAt = request.expiresAt)
            } else {
                request
            }
        }
        if (current != stored) write(current)
        return current.sortedByDescending { it.raisedAt }
    }

    fun pending(now: Instant = Instant.now()): List<IncomingRequest> =
        all(now).filter { it.isOpenAt(now) }

    /**
     * Records a request heard on the topic.
     *
     * A request already known is left exactly as it is. Messages arrive more than
     * once by design — the poll window overlaps — and re-recording would reopen
     * something this phone had already answered.
     */
    fun remember(request: IncomingRequest) {
        val existing = read()
        if (existing.any { it.id == request.id }) return
        write((existing + request).takeLast(MAX_ENTRIES))
    }

    /** Marks a request answered, whether by this phone or another parent's. */
    fun settle(
        id: String,
        state: RequestState,
        note: String?,
        at: Instant = Instant.now(),
    ): Boolean {
        val existing = read()
        val target = existing.firstOrNull { it.id == id } ?: return false
        if (!target.state.isOpen) return false

        write(
            existing.map { request ->
                if (request.id == id) {
                    request.copy(state = state, decidedAt = at, decisionNote = note)
                } else {
                    request
                }
            },
        )
        return true
    }

    private fun read(): List<IncomingRequest> {
        val raw = prefs.getString(KEY_INBOX, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromJson) }
    }

    private fun write(requests: List<IncomingRequest>) {
        prefs.edit { putString(KEY_INBOX, JSONArray(requests.map(::toJson)).toString()) }
    }

    private fun toJson(request: IncomingRequest): JSONObject = JSONObject().apply {
        put(KEY_ID, request.id)
        put(KEY_LABEL, request.label)
        put(KEY_MINUTES, request.minutes)
        put(KEY_REASON, request.reason)
        put(KEY_RAISED, request.raisedAt.toEpochMilli())
        put(KEY_EXPIRES, request.expiresAt.toEpochMilli())
        put(KEY_STATE, request.state.name)
        request.decidedAt?.let { put(KEY_DECIDED, it.toEpochMilli()) }
        request.decisionNote?.let { put(KEY_NOTE, it) }
    }

    private fun fromJson(json: JSONObject): IncomingRequest? {
        val minutes = json.optInt(KEY_MINUTES, 0).takeIf { it > 0 } ?: return null
        val raised = json.optLong(KEY_RAISED, 0L).takeIf { it > 0L } ?: return null
        val expires = json.optLong(KEY_EXPIRES, 0L).takeIf { it > raised } ?: return null
        val state = RequestState.entries.firstOrNull { it.name == json.optString(KEY_STATE) }
            ?: return null

        return IncomingRequest(
            id = json.optString(KEY_ID).takeIf(String::isNotBlank) ?: return null,
            label = json.optString(KEY_LABEL),
            minutes = minutes,
            reason = json.optString(KEY_REASON),
            raisedAt = Instant.ofEpochMilli(raised),
            expiresAt = Instant.ofEpochMilli(expires),
            state = state,
            decidedAt = json.optLong(KEY_DECIDED, 0L).takeIf { it > 0L }
                ?.let(Instant::ofEpochMilli),
            decisionNote = json.optString(KEY_NOTE).takeIf(String::isNotBlank),
        )
    }

    private companion object {
        const val FILE_NAME = "inbox"
        const val KEY_INBOX = "requests"

        /** A parent phone keeps recent history, not the family archive. */
        const val MAX_ENTRIES = 200

        const val KEY_ID = "id"
        const val KEY_LABEL = "label"
        const val KEY_MINUTES = "minutes"
        const val KEY_REASON = "reason"
        const val KEY_RAISED = "raisedAt"
        const val KEY_EXPIRES = "expiresAt"
        const val KEY_STATE = "state"
        const val KEY_DECIDED = "decidedAt"
        const val KEY_NOTE = "note"
    }
}
