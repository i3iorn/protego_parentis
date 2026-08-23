package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.Condition
import dev.schrammel.dementor_watch.model.ContextRule
import dev.schrammel.dementor_watch.model.DeviceTag
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Place
import dev.schrammel.dementor_watch.model.PlaceId
import dev.schrammel.dementor_watch.model.RuleEffect
import dev.schrammel.dementor_watch.model.RuleTarget
import dev.schrammel.dementor_watch.model.WeeklyBudget
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.minutes

/**
 * Stores contextual rules and the places they refer to.
 *
 * Places hold coordinates and so are the most sensitive thing this app keeps.
 * They stay in app-private storage, are excluded from backup along with
 * everything else, and are never included in a sync message or the diagnostic log.
 *
 * Parsing is defensive throughout: a rule that cannot be read is dropped rather
 * than crashing the enforcement service, which runs on the child's phone and has
 * to survive a corrupt file.
 */
class ContextRuleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------- rules ----------

    var rules: List<ContextRule>
        get() = decodeRules(prefs.getString(KEY_RULES, null))
        set(value) = prefs.edit { putString(KEY_RULES, encodeRules(value)) }

    fun upsert(rule: ContextRule) {
        rules = rules.filterNot { it.id == rule.id } + rule
    }

    fun delete(id: String) {
        rules = rules.filterNot { it.id == id }
    }

    // ---------- places ----------

    var places: List<Place>
        get() = decodePlaces(prefs.getString(KEY_PLACES, null))
        set(value) = prefs.edit { putString(KEY_PLACES, encodePlaces(value)) }

    fun upsertPlace(place: Place) {
        places = places.filterNot { it.id == place.id } + place
    }

    /**
     * Removes a place and every rule that referred to it.
     *
     * Leaving a rule pointing at a deleted place would make it silently
     * unsatisfiable — a rule that looks active in the list and never fires.
     */
    fun deletePlace(id: PlaceId) {
        places = places.filterNot { it.id == id }
        rules = rules.filterNot { mentionsPlace(it.condition, id) }
    }

    private fun mentionsPlace(condition: Condition, id: PlaceId): Boolean = when (condition) {
        is Condition.AtPlace -> condition.place == id
        is Condition.AllOf -> condition.conditions.any { mentionsPlace(it, id) }
        is Condition.AnyOf -> condition.conditions.any { mentionsPlace(it, id) }
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
                    put(KEY_CONDITION, encodeCondition(rule.condition))
                    put(KEY_EFFECT, encodeEffect(rule.effect))
                    put(KEY_TARGET, encodeTarget(rule.target))
                }
            },
        ).toString()

    private fun decodeRules(raw: String?): List<ContextRule> {
        val array = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull()
            ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                ContextRule(
                    id = json.optString(KEY_ID),
                    name = json.optString(KEY_NAME),
                    enabled = json.optBoolean(KEY_ENABLED, true),
                    condition = json.optJSONObject(KEY_CONDITION)
                        ?.let(::decodeCondition) ?: Condition.Always,
                    effect = json.optJSONObject(KEY_EFFECT)?.let(::decodeEffect)
                        ?: return@mapNotNull null,
                    target = json.optJSONObject(KEY_TARGET)?.let(::decodeTarget)
                        ?: return@mapNotNull null,
                )
            }.getOrNull()
        }
    }

    private fun encodeCondition(condition: Condition): JSONObject = JSONObject().apply {
        when (condition) {
            is Condition.Always -> put(KEY_KIND, ALWAYS)
            is Condition.OnWifi -> {
                put(KEY_KIND, WIFI)
                put(KEY_VALUE, condition.ssid)
            }

            is Condition.NearDevice -> {
                put(KEY_KIND, NEAR)
                put(KEY_VALUE, condition.tag.value)
            }

            is Condition.AtPlace -> {
                put(KEY_KIND, PLACE)
                put(KEY_VALUE, condition.place.value)
            }

            is Condition.AllOf -> {
                put(KEY_KIND, ALL_OF)
                put(KEY_PARTS, JSONArray(condition.conditions.map(::encodeCondition)))
            }

            is Condition.AnyOf -> {
                put(KEY_KIND, ANY_OF)
                put(KEY_PARTS, JSONArray(condition.conditions.map(::encodeCondition)))
            }

            is Condition.Not -> {
                put(KEY_KIND, NOT)
                put(KEY_PARTS, JSONArray(listOf(encodeCondition(condition.condition))))
            }
        }
    }

    private fun decodeCondition(json: JSONObject, depth: Int = 0): Condition? {
        // The same bound the engine applies, enforced here so a corrupt file
        // cannot blow the stack before evaluation even starts.
        if (depth > MAX_DEPTH) return null

        val value = json.optString(KEY_VALUE)
        fun parts(): List<Condition> {
            val array = json.optJSONArray(KEY_PARTS) ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { decodeCondition(it, depth + 1) }
            }
        }

        return when (json.optString(KEY_KIND)) {
            ALWAYS -> Condition.Always
            WIFI -> value.takeIf(String::isNotBlank)?.let(Condition::OnWifi)
            NEAR -> value.takeIf(String::isNotBlank)?.let { Condition.NearDevice(DeviceTag(it)) }
            PLACE -> value.takeIf(String::isNotBlank)?.let { Condition.AtPlace(PlaceId(it)) }
            ALL_OF -> Condition.AllOf(parts())
            ANY_OF -> Condition.AnyOf(parts())
            NOT -> parts().firstOrNull()?.let(Condition::Not)
            else -> null
        }
    }

    private fun encodeEffect(effect: RuleEffect): JSONObject = JSONObject().apply {
        when (effect) {
            is RuleEffect.Block -> put(KEY_KIND, BLOCK)
            is RuleEffect.Allow -> put(KEY_KIND, ALLOW)
            is RuleEffect.SetDeviceBudget -> {
                put(KEY_KIND, BUDGET)
                // Clamped: an infinite duration is Long.MAX_VALUE minutes, which
                // narrows to a negative Int on the way back in and would be
                // rejected by WeeklyBudget, silently losing the rule.
                put(KEY_MINUTES, effect.budget.default.inWholeMinutes.coerceIn(0L, MAX_MINUTES))
            }
        }
    }

    private fun decodeEffect(json: JSONObject): RuleEffect? = when (json.optString(KEY_KIND)) {
        BLOCK -> RuleEffect.Block
        ALLOW -> RuleEffect.Allow
        BUDGET -> RuleEffect.SetDeviceBudget(
            WeeklyBudget.flat(json.optLong(KEY_MINUTES, 0L).coerceIn(0L, MAX_MINUTES).toInt().minutes),
        )

        else -> null
    }

    private fun encodeTarget(target: RuleTarget): JSONObject = JSONObject().apply {
        when (target) {
            is RuleTarget.Everything -> put(KEY_KIND, EVERYTHING)
            is RuleTarget.Apps -> {
                put(KEY_KIND, APPS)
                put(KEY_PARTS, JSONArray(target.packages.map { it.value }))
            }

            is RuleTarget.Categories -> {
                put(KEY_KIND, CATEGORIES)
                put(KEY_PARTS, JSONArray(target.categories.map { it.value }))
            }
        }
    }

    private fun decodeTarget(json: JSONObject): RuleTarget? {
        fun values(): List<String> {
            val array = json.optJSONArray(KEY_PARTS) ?: return emptyList()
            return (0 until array.length())
                .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }

        return when (json.optString(KEY_KIND)) {
            EVERYTHING -> RuleTarget.Everything
            APPS -> RuleTarget.Apps(values().map(::PackageName).toSet())
            CATEGORIES -> RuleTarget.Categories(values().map(::CategoryId).toSet())
            else -> null
        }
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
                    radiusMetres = json.optInt(KEY_RADIUS, Place.MINIMUM_RADIUS_METRES),
                )
            }.getOrNull()
        }
    }

    private companion object {
        const val FILE_NAME = "context_rules"
        const val MAX_DEPTH = 12

        /** A day. Anything larger is not a daily limit. */
        const val MAX_MINUTES = 24L * 60L

        const val KEY_RULES = "rules"
        const val KEY_PLACES = "places"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_ENABLED = "enabled"
        const val KEY_CONDITION = "condition"
        const val KEY_EFFECT = "effect"
        const val KEY_TARGET = "target"
        const val KEY_KIND = "kind"
        const val KEY_VALUE = "value"
        const val KEY_PARTS = "parts"
        const val KEY_MINUTES = "minutes"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_RADIUS = "radius"

        const val ALWAYS = "always"
        const val WIFI = "wifi"
        const val NEAR = "near"
        const val PLACE = "place"
        const val ALL_OF = "allOf"
        const val ANY_OF = "anyOf"
        const val NOT = "not"

        const val BLOCK = "block"
        const val ALLOW = "allow"
        const val BUDGET = "budget"

        const val EVERYTHING = "everything"
        const val APPS = "apps"
        const val CATEGORIES = "categories"
    }
}
