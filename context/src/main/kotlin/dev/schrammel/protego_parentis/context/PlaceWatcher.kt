package dev.schrammel.protego_parentis.context

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId

/** A place the phone has just arrived at, or just left (LOC-1). */
data class PlaceTransition(
    val place: Place,
    val arrived: Boolean,
)

/**
 * Notices when the phone arrives at or leaves a saved place.
 *
 * No geofencing API and no Play Services: this app has never depended on them, and
 * "which of these circles is the phone inside" is a distance calculation the
 * context provider already does for the rules. Comparing this tick's answer with
 * the last one turns it into arrivals and departures for nothing extra.
 *
 * The cost is that transitions are noticed on the enforcement tick rather than the
 * moment they happen, so an arrival can be a few minutes late. For "did they get
 * to school" that is fine, and it buys freedom from a dependency that would want
 * its own location permission, its own background allowance and its own privacy
 * story.
 *
 * A transition carries a *place name*, never a position. "Arrived at School" is
 * what a parent asked for; where the phone actually is remains something they have
 * to ask for, visibly, one answer at a time.
 */
class PlaceWatcher(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Compares [insideNow] against what was true last time and reports the changes.
     *
     * The first call after enabling this records the state and reports nothing:
     * every place the phone happens to be in is not an arrival, and telling a
     * parent their child "arrived home" the moment they set the feature up is the
     * kind of wrongness that makes people distrust the rest of it.
     */
    fun transitions(places: List<Place>, insideNow: Set<PlaceId>): List<PlaceTransition> {
        val known = places.associateBy { it.id }
        val before = remembered()
        val nowIds = insideNow.filter { it in known }.mapTo(mutableSetOf()) { it.value }

        remember(nowIds)

        if (!hasSeeded) {
            seeded()
            return emptyList()
        }

        val arrived = (nowIds - before).mapNotNull { known[PlaceId(it)] }
            .map { PlaceTransition(it, arrived = true) }
        val left = (before - nowIds).mapNotNull { known[PlaceId(it)] }
            .map { PlaceTransition(it, arrived = false) }

        return arrived + left
    }

    /** Forgets where the phone was, so the next pass seeds again rather than reporting. */
    fun reset() {
        prefs.edit { clear() }
    }

    private val hasSeeded: Boolean get() = prefs.getBoolean(KEY_SEEDED, false)

    private fun seeded() = prefs.edit { putBoolean(KEY_SEEDED, true) }

    private fun remembered(): Set<String> = prefs.getStringSet(KEY_INSIDE, emptySet()).orEmpty()

    private fun remember(ids: Set<String>) = prefs.edit { putStringSet(KEY_INSIDE, ids) }

    private companion object {
        const val FILE_NAME = "places_seen"
        const val KEY_INSIDE = "inside"
        const val KEY_SEEDED = "seeded"
    }
}
