package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** The last position a parent's phone was given. */
data class LastKnownPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Int,
    val at: Instant,
)

/** An arrival at, or departure from, a named place. */
data class PlaceEvent(val place: String, val arrived: Boolean, val at: Instant)

/** Today's domains as a parent's phone was told them. */
data class WebSummary(val domains: List<Pair<String, Int>>, val blocked: Int, val at: Instant)

/**
 * What a parent's phone has been told about the child's day, beyond the numbers.
 *
 * One position, a short list of recent arrivals, whether an emergency is running,
 * and today's domains. All of it replaced rather than accumulated — a parent phone
 * building a movement history would be exactly the surveillance archive this
 * product refuses to keep, only in the one place the child cannot see it.
 *
 * The position in particular is a single value with no history behind it. A parent
 * can ask again; they cannot scroll back.
 */
class FamilyEventStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------- where ----------

    val lastPosition: LastKnownPosition?
        get() {
            val at = prefs.getLong(KEY_POSITION_AT, 0L).takeIf { it > 0L } ?: return null
            return LastKnownPosition(
                latitude = prefs.getFloat(KEY_LATITUDE, 0f).toDouble(),
                longitude = prefs.getFloat(KEY_LONGITUDE, 0f).toDouble(),
                accuracyMetres = prefs.getInt(KEY_ACCURACY, 0),
                at = Instant.ofEpochMilli(at),
            )
        }

    fun recordLocation(latitude: Double, longitude: Double, accuracyMetres: Int, at: Instant) {
        // Older than what we hold means an out-of-order poll, not a newer answer.
        lastPosition?.let { known -> if (at.isBefore(known.at)) return }

        prefs.edit {
            putFloat(KEY_LATITUDE, latitude.toFloat())
            putFloat(KEY_LONGITUDE, longitude.toFloat())
            putInt(KEY_ACCURACY, accuracyMetres)
            putLong(KEY_POSITION_AT, at.toEpochMilli())
        }
    }

    // ---------- arrivals ----------

    /**
     * Recent arrivals and departures, newest first.
     *
     * Short on purpose. "Did they get to school" is answered by the last few
     * events; a month of them would be a movement log, which is a different
     * product and not this one.
     */
    fun transitions(): List<PlaceEvent> {
        val raw = prefs.getString(KEY_TRANSITIONS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .mapNotNull { json ->
                val place = json.optString(KEY_PLACE).takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                PlaceEvent(
                    place = place,
                    arrived = json.optBoolean(KEY_ARRIVED),
                    at = Instant.ofEpochMilli(json.optLong(KEY_AT, 0L)),
                )
            }
            .sortedByDescending { it.at }
    }

    fun recordTransition(place: String, arrived: Boolean, at: Instant) = synchronized(LOCK) {
        val kept = (
            listOf(PlaceEvent(place, arrived, at)) + transitions()
            ).take(MAX_TRANSITIONS)

        prefs.edit {
            putString(
                KEY_TRANSITIONS,
                JSONArray(
                    kept.map { event ->
                        JSONObject().apply {
                            put(KEY_PLACE, event.place)
                            put(KEY_ARRIVED, event.arrived)
                            put(KEY_AT, event.at.toEpochMilli())
                        }
                    },
                ).toString(),
            )
        }
    }

    // ---------- emergencies ----------

    val sosRaisedAt: Instant?
        get() = prefs.getLong(KEY_SOS_AT, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    fun recordSos(raised: Boolean, at: Instant) {
        prefs.edit {
            if (raised) putLong(KEY_SOS_AT, at.toEpochMilli()) else remove(KEY_SOS_AT)
        }
    }

    // ---------- web ----------

    fun webSummary(): WebSummary? {
        val at = prefs.getLong(KEY_WEB_AT, 0L).takeIf { it > 0L } ?: return null
        val raw = prefs.getString(KEY_WEB_DOMAINS, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        return WebSummary(
            domains = json.keys().asSequence()
                .map { it to json.optInt(it, 0) }
                .sortedByDescending { it.second }
                .toList(),
            blocked = prefs.getInt(KEY_WEB_BLOCKED, 0),
            at = Instant.ofEpochMilli(at),
        )
    }

    fun recordWebSummary(domains: List<Pair<String, Int>>, blocked: Int, at: Instant) {
        prefs.edit {
            putString(KEY_WEB_DOMAINS, JSONObject(domains.toMap()).toString())
            putInt(KEY_WEB_BLOCKED, blocked)
            putLong(KEY_WEB_AT, at.toEpochMilli())
        }
    }

    /**
     * Forgets the position, the arrivals and the browsing.
     *
     * The most important of these to be able to clear: a parent's phone that has
     * left a family has no business still knowing where the child was.
     */
    fun forgetAll() = synchronized(LOCK) {
        prefs.edit { clear() }
    }

    private companion object {
        val LOCK = Any()

        const val FILE_NAME = "family_events"

        /** Enough to answer "did they get there"; too few to be a movement log. */
        const val MAX_TRANSITIONS = 20

        const val KEY_LATITUDE = "lat"
        const val KEY_LONGITUDE = "lon"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_POSITION_AT = "positionAt"
        const val KEY_TRANSITIONS = "transitions"
        const val KEY_PLACE = "place"
        const val KEY_ARRIVED = "arrived"
        const val KEY_AT = "at"
        const val KEY_SOS_AT = "sosAt"
        const val KEY_WEB_DOMAINS = "webDomains"
        const val KEY_WEB_BLOCKED = "webBlocked"
        const val KEY_WEB_AT = "webAt"
    }
}
