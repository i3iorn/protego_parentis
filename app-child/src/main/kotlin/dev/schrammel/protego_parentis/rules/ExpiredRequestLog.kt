package dev.schrammel.protego_parentis.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.RequestId
import org.json.JSONArray

/**
 * Which lapsed requests [RequestCoordinator.notifyLapsedRequests] has already
 * announced.
 *
 * Exists only because expiry itself is computed lazily on read rather than
 * written back as a discrete event — see that method's own doc comment. This
 * is what keeps a request from being announced a second time on the next
 * enforcement tick just because it is still sitting there, expired.
 */
class ExpiredRequestLog(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun contains(id: RequestId): Boolean = announcedIds().contains(id.value)

    /**
     * Every announced id in one read. [contains] decodes the whole list per
     * call, which made a lapsed-request sweep one parse per expired request;
     * the tick's sweep reads the set once and filters against it.
     */
    fun announcedIds(): Set<String> = announced().toSet()

    /**
     * Bounded like every other persisted collection in this app (#156's own
     * precedent on [dev.schrammel.protego_parentis.rules.GoalStore]) — nothing
     * needs the full history, and an unbounded log would otherwise grow for as
     * long as the family keeps using the requests feature.
     *
     * A request id is an opaque UUID with no ordering of its own, unlike
     * [dev.schrammel.protego_parentis.rules.GoalStore]'s dates — so "the
     * newest 200" only means anything if arrival order is kept explicitly.
     * `SharedPreferences`' own string-set type stores an unordered `Set`,
     * which would make that impossible; a small JSON array in appended order
     * is what [takeLast] actually needs to mean "most recently announced".
     */
    fun markAnnounced(ids: List<RequestId>) {
        val kept = (announced() + ids.map { it.value }).takeLast(MAX_ANNOUNCED)
        prefs.edit { putString(KEY_ANNOUNCED, JSONArray(kept).toString()) }
    }

    private fun announced(): List<String> {
        val raw = prefs.getString(KEY_ANNOUNCED, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map(array::optString)
    }

    private companion object {
        const val MAX_ANNOUNCED = 200
        const val FILE_NAME = "expired_requests"
        const val KEY_ANNOUNCED = "announced"
    }
}
