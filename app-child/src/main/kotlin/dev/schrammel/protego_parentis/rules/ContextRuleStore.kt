package dev.schrammel.protego_parentis.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.policy.ContextRuleFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores contextual rules and the places they refer to.
 *
 * Places hold coordinates and so are the most sensitive thing this app keeps.
 * They stay in app-private storage, are excluded from backup along with
 * everything else, and a coordinate is never included in a sync message or the
 * diagnostic log — see [dev.schrammel.protego_parentis.model.PlaceRef] for the
 * name and radius that, unlike a coordinate, do replicate.
 *
 * Parsing is defensive throughout: a rule that cannot be read is dropped rather
 * than crashing the enforcement service, which runs on the child's phone and has
 * to survive a corrupt file.
 */
class ContextRuleStore(context: Context) {

    /**
     * Whether arriving at or leaving a saved place is announced to the parents
     * (LOC-1).
     *
     * Lives here, with the places it is about. Off until a parent turns it on, and
     * it sends the place's name and never a position — positions go only when a
     * parent asks, visibly, or in an emergency.
     */
    var announceArrivals: Boolean
        get() = prefs.getBoolean(KEY_ANNOUNCE_ARRIVALS, false)
        set(value) = prefs.edit { putBoolean(KEY_ANNOUNCE_ARRIVALS, value) }


    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------- rules ----------

    var rules: List<ContextRule>
        get() = rulesCache.get(prefs.getString(KEY_RULES, null), ::decodeRules)
        set(value) = prefs.edit { putString(KEY_RULES, encodeRules(value)) }

    fun upsert(rule: ContextRule) = synchronized(prefs) {
        rules = rules.filterNot { it.id == rule.id } + rule
    }

    fun delete(id: String) = synchronized(prefs) {
        rules = rules.filterNot { it.id == id }
    }

    // ---------- places ----------

    var places: List<Place>
        get() = placesCache.get(prefs.getString(KEY_PLACES, null), ::decodePlaces)
        set(value) = prefs.edit { putString(KEY_PLACES, encodePlaces(value)) }

    /**
     * Places [places] is currently reading with a larger radius than what was
     * actually saved for them (#56) — [decodePlaces] clamps a stored radius
     * below [Place.MINIMUM_RADIUS_METRES] up rather than rejecting it, since
     * that floor has only ever gone up and a family's already-saved place is
     * not the thing to punish for it. Live, not a one-off migration flag: this
     * reflects what is on disk right now, so if the floor changes again this
     * says so again, and a place a family has since resized itself no longer
     * appears here.
     */
    val clampedPlaceIds: Set<PlaceId>
        get() = clampedPlaceIds(prefs.getString(KEY_PLACES, null))

    fun upsertPlace(place: Place) = synchronized(prefs) {
        places = places.filterNot { it.id == place.id } + place
    }

    /**
     * Removes a place and every rule that referred to it.
     *
     * Leaving a rule pointing at a deleted place would make it silently
     * unsatisfiable — a rule that looks active in the list and never fires.
     */
    fun deletePlace(id: PlaceId) = synchronized(prefs) {
        places = places.filterNot { it.id == id }
        rules = rules.filterNot { mentionsPlace(it.activeWhen, id) }
    }

    private fun mentionsPlace(condition: Condition, id: PlaceId): Boolean = when (condition) {
        is Condition.AtPlace -> condition.place == id
        is Condition.AllOf -> condition.conditions.any { mentionsPlace(it, id) }
        is Condition.AnyOf -> condition.conditions.any { mentionsPlace(it, id) }
        is Condition.AtLeast -> condition.conditions.any { mentionsPlace(it, id) }
        is Condition.Not -> mentionsPlace(condition.condition, id)
        else -> false
    }

    // ---------- encoding ----------

    private fun encodeRules(rules: List<ContextRule>): String =
        JSONArray(
            rules.map { rule ->
                JSONObject().apply {
                    put(KEY_ID, rule.id)
                    put(KEY_NAME, rule.name)
                    put(KEY_ENABLED, rule.enabled)
                    put(KEY_CONDITION, ContextRuleCodec.encodeCondition(rule.condition))
                    if (ContextRuleFormat.needsV2(rule)) {
                        // No effect/target keys, so a build from before V2
                        // drops this rule rather than enforcing part of it.
                        put(KEY_FORMAT, FORMAT_V2)
                        put(KEY_ACTIONS, ContextRuleCodec.encodeActions(rule.actions))
                        rule.exceptWhen?.let { put(KEY_EXCEPT, ContextRuleCodec.encodeCondition(it)) }
                    } else {
                        put(KEY_EFFECT, ContextRuleCodec.encodeEffect(rule.effect))
                        put(KEY_TARGET, ContextRuleCodec.encodeTarget(rule.target))
                    }
                }
            },
        ).toString()

    private fun decodeRules(raw: String?): List<ContextRule> {
        val array = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull()
            ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching { decodeRule(json) }.getOrNull()
        }
    }

    /**
     * One rule, or null if any part of it can't be read. An unreadable
     * condition used to fall back to Always, which turned a rule meant for
     * school hours into one that never switched off.
     */
    private fun decodeRule(json: JSONObject): ContextRule? {
        val condition = json.optJSONObject(KEY_CONDITION)?.let(ContextRuleCodec::decodeCondition) ?: return null
        val v2 = json.optInt(KEY_FORMAT, 1) >= FORMAT_V2
        val actions = if (v2) {
            ContextRuleCodec.decodeActions(json.optJSONArray(KEY_ACTIONS)) ?: return null
        } else {
            val effect = json.optJSONObject(KEY_EFFECT)?.let(ContextRuleCodec::decodeEffect) ?: return null
            val target = json.optJSONObject(KEY_TARGET)?.let(ContextRuleCodec::decodeTarget) ?: return null
            listOf(RuleAction(effect, target))
        }
        val exceptWhen = if (v2 && json.has(KEY_EXCEPT)) {
            json.optJSONObject(KEY_EXCEPT)?.let(ContextRuleCodec::decodeCondition) ?: return null
        } else {
            null
        }
        return ContextRule(
            id = json.optString(KEY_ID),
            name = json.optString(KEY_NAME),
            enabled = json.optBoolean(KEY_ENABLED, true),
            condition = condition,
            actions = actions,
            exceptWhen = exceptWhen,
        )
    }

    private fun encodePlaces(places: List<Place>): String =
        JSONArray(
            places.map { place ->
                JSONObject().apply {
                    put(KEY_ID, place.id.value)
                    put(KEY_NAME, place.name)
                    put(KEY_LAT, place.latitude)
                    put(KEY_LON, place.longitude)
                    put(KEY_RADIUS, place.radiusMetres)
                }
            },
        ).toString()

    private fun decodePlaces(raw: String?): List<Place> {
        val array = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull()
            ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                Place(
                    id = PlaceId(json.optString(KEY_ID)),
                    name = json.optString(KEY_NAME),
                    latitude = json.optDouble(KEY_LAT),
                    longitude = json.optDouble(KEY_LON),
                    // Clamped up, never rejected: a radius saved under an
                    // earlier, smaller MINIMUM_RADIUS_METRES must still
                    // decode, or raising the floor would silently drop every
                    // place that predates it — see clampedPlaceIds.
                    radiusMetres = maxOf(
                        json.optInt(KEY_RADIUS, Place.MINIMUM_RADIUS_METRES),
                        Place.MINIMUM_RADIUS_METRES,
                    ),
                )
            }.getOrNull()
        }
    }

    private fun clampedPlaceIds(raw: String?): Set<PlaceId> {
        val array = runCatching { JSONArray(raw ?: return emptySet()) }.getOrNull()
            ?: return emptySet()

        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val id = json.optString(KEY_ID).takeIf(String::isNotBlank) ?: return@mapNotNull null
            val storedRadius = json.optInt(KEY_RADIUS, Place.MINIMUM_RADIUS_METRES)
            PlaceId(id).takeIf { storedRadius < Place.MINIMUM_RADIUS_METRES }
        }.toSet()
    }

    private companion object {
        // Shared across instances (see DecodeCache): screens and the per-poll
        // replication classes each build their own store, so a cache held
        // per instance would almost never be warm.
        private val rulesCache = DecodeCache<List<ContextRule>>()
        private val placesCache = DecodeCache<List<Place>>()

        const val KEY_ANNOUNCE_ARRIVALS = "announce_arrivals"
        const val FILE_NAME = "context_rules"

        const val KEY_RULES = "rules"
        const val KEY_PLACES = "places"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_ENABLED = "enabled"
        const val KEY_CONDITION = "condition"
        const val KEY_EFFECT = "effect"
        const val KEY_TARGET = "target"
        const val KEY_FORMAT = "format"
        const val KEY_ACTIONS = "actions"
        const val KEY_EXCEPT = "except"
        const val FORMAT_V2 = 2
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_RADIUS = "radius"
    }
}
