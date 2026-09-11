package dev.schrammel.protego_parentis.rules

import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes

/**
 * Turns a [Condition]/[RuleEffect]/[RuleTarget] into JSON and back.
 *
 * Extracted from [ContextRuleStore], which persists a whole rule list as one
 * blob, so [dev.schrammel.protego_parentis.sync.PolicyReplication] can reuse
 * exactly the same encoding for a rule's three logic fields as three
 * separate replicated registers — one path each, so two parents editing a
 * rule's effect and its target at the same moment both survive, the same
 * reasoning `PolicyReplication.flattenSchedules` applies to a schedule's
 * fields. Only `condition`, `effect` and `target` are opaque JSON *within*
 * their own register; decomposing a condition tree itself into further paths
 * was judged not worth the complexity for how rarely two parents edit the
 * exact same rule's logic at the exact same instant — see the "one PR per
 * record type" scoping on #41.
 *
 * The [JSONObject]-returning functions are the ones [ContextRuleStore]
 * already used before this was extracted, unchanged, so its on-disk shape —
 * `condition`/`effect`/`target` nested as JSON objects inside a rule's own
 * object — does not move. The `*ToString`/`*FromString` wrappers exist only
 * for replication, where a register's value has to be a string.
 *
 * A [Condition] is `{"kind": "...", ...}`, with `AllOf`/`AnyOf`/`Not` nesting
 * further conditions under `parts`. Deliberately open-ended: adding a new
 * condition kind — a future `Xor`, say — is a new `KIND` constant and a new
 * branch in each function below, never a change to this shape or to what is
 * already on the wire, whether that wire is local storage or replication.
 * [decodeCondition]'s depth bound guards against a malicious or corrupt
 * payload nesting deeply enough to blow the stack; it is not a limit on how
 * many kinds may exist.
 */
internal object ContextRuleCodec {

    /** The same bound [dev.schrammel.protego_parentis.enforcement] applies when evaluating a condition. */
    const val MAX_CONDITION_DEPTH = 12

    /** A day. Anything larger is not a daily limit. */
    private const val MAX_MINUTES = 24L * 60L

    fun encodeCondition(condition: Condition): JSONObject = JSONObject().apply {
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

            is Condition.BatteryBelow -> {
                put(KEY_KIND, BATTERY)
                put(KEY_VALUE, condition.percent)
            }

            // #101. Days as ISO numbers and times as minutes-of-day: both are
            // locale-independent and stable across a device's clock format,
            // unlike anything rendered. The parity anchor is an epoch day for
            // the same reason, and is simply absent on a window that runs
            // every week.
            is Condition.DuringWindow -> {
                put(KEY_KIND, WINDOW)
                put(KEY_DAYS, JSONArray(condition.window.days.map { it.value }))
                put(KEY_START, condition.window.start.toSecondOfDay() / SECONDS_PER_MINUTE)
                put(KEY_END, condition.window.end.toSecondOfDay() / SECONDS_PER_MINUTE)
                condition.window.parityAnchor?.let { put(KEY_PARITY_ANCHOR, it.toEpochDay()) }
            }

            // #102. Same minute unit and the same MAX_MINUTES clamp as
            // RuleEffect.SetDeviceBudget's KEY_MINUTES, since both describe a
            // duration within one day.
            is Condition.AllowanceSpent -> {
                put(KEY_KIND, ALLOWANCE_SPENT)
                put(KEY_VALUE, condition.minutes.coerceIn(0, MAX_MINUTES.toInt()))
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

    fun decodeCondition(json: JSONObject, depth: Int = 0): Condition? {
        // The same bound the engine applies, enforced here so a corrupt or
        // hostile payload cannot blow the stack before evaluation even starts.
        if (depth > MAX_CONDITION_DEPTH) return null

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
            BATTERY -> json.optInt(KEY_VALUE, 0)
                .takeIf { it in 1..100 }
                ?.let(Condition::BatteryBelow)
            WINDOW -> decodeWindow(json)?.let(Condition::DuringWindow)
            ALLOWANCE_SPENT -> json.optInt(KEY_VALUE, -1)
                .takeIf { it >= 0 }
                ?.let(Condition::AllowanceSpent)
            ALL_OF -> Condition.AllOf(parts())
            ANY_OF -> Condition.AnyOf(parts())
            NOT -> parts().firstOrNull()?.let(Condition::Not)
            else -> null
        }
    }

    /**
     * A [RecurringWindow] from stored fields, or null if they cannot make one.
     *
     * [RecurringWindow] refuses a window with no days or with equal start and
     * end, and a rule whose condition will not read is dropped rather than
     * thrown — the same defensive posture the rest of this codec takes, since
     * this is loaded on the phone that has to keep working.
     */
    private fun decodeWindow(json: JSONObject): RecurringWindow? {
        val daysArray = json.optJSONArray(KEY_DAYS) ?: return null
        val days = (0 until daysArray.length())
            .mapNotNull { index -> DayOfWeek.entries.firstOrNull { it.value == daysArray.optInt(index, 0) } }
            .toSet()

        val start = minuteOfDay(json, KEY_START) ?: return null
        val end = minuteOfDay(json, KEY_END) ?: return null
        val anchor = if (json.has(KEY_PARITY_ANCHOR)) {
            runCatching { LocalDate.ofEpochDay(json.getLong(KEY_PARITY_ANCHOR)) }.getOrNull() ?: return null
        } else {
            null
        }

        return runCatching { RecurringWindow(days, start, end, anchor) }.getOrNull()
    }

    private fun minuteOfDay(json: JSONObject, key: String): LocalTime? =
        json.optInt(key, -1)
            .takeIf { it in 0 until MINUTES_PER_DAY }
            ?.let { LocalTime.ofSecondOfDay(it.toLong() * SECONDS_PER_MINUTE) }

    fun encodeEffect(effect: RuleEffect): JSONObject = JSONObject().apply {
        when (effect) {
            is RuleEffect.Block -> put(KEY_KIND, BLOCK)
            is RuleEffect.Allow -> put(KEY_KIND, ALLOW)
            is RuleEffect.SafetyExempt -> put(KEY_KIND, SAFETY_EXEMPT)
            is RuleEffect.SetDeviceBudget -> {
                put(KEY_KIND, BUDGET)
                // Clamped: an infinite duration is Long.MAX_VALUE minutes, which
                // narrows to a negative Int on the way back in and would be
                // rejected by WeeklyBudget, silently losing the rule.
                put(KEY_MINUTES, effect.budget.default.inWholeMinutes.coerceIn(0L, MAX_MINUTES))
            }
        }
    }

    fun decodeEffect(json: JSONObject): RuleEffect? = when (json.optString(KEY_KIND)) {
        BLOCK -> RuleEffect.Block
        ALLOW -> RuleEffect.Allow
        SAFETY_EXEMPT -> RuleEffect.SafetyExempt
        BUDGET -> RuleEffect.SetDeviceBudget(
            WeeklyBudget.flat(json.optLong(KEY_MINUTES, 0L).coerceIn(0L, MAX_MINUTES).toInt().minutes),
        )

        else -> null
    }

    fun encodeTarget(target: RuleTarget): JSONObject = JSONObject().apply {
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

            is RuleTarget.Mixed -> {
                put(KEY_KIND, MIXED)
                put(KEY_PARTS, JSONArray(target.packages.map { it.value }))
                put(KEY_PARTS_CATEGORIES, JSONArray(target.categories.map { it.value }))
            }

            is RuleTarget.EverythingExcept -> {
                put(KEY_KIND, EVERYTHING_EXCEPT)
                put(KEY_PARTS, JSONArray(target.packages.map { it.value }))
            }
        }
    }

    fun decodeTarget(json: JSONObject): RuleTarget? {
        fun valuesAt(key: String): List<String> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length())
                .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }
        fun values() = valuesAt(KEY_PARTS)

        return when (json.optString(KEY_KIND)) {
            EVERYTHING -> RuleTarget.Everything
            APPS -> RuleTarget.Apps(values().map(::PackageName).toSet())
            CATEGORIES -> RuleTarget.Categories(values().map(::CategoryId).toSet())
            MIXED -> RuleTarget.Mixed(
                packages = values().map(::PackageName).toSet(),
                categories = valuesAt(KEY_PARTS_CATEGORIES).map(::CategoryId).toSet(),
            )
            EVERYTHING_EXCEPT -> RuleTarget.EverythingExcept(values().map(::PackageName).toSet())

            else -> null
        }
    }

    // ---------- string wrappers, for a replicated register's value ----------

    fun encodeConditionToString(condition: Condition): String = encodeCondition(condition).toString()

    fun decodeConditionFromString(raw: String?): Condition? =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { decodeCondition(it) }

    fun encodeEffectToString(effect: RuleEffect): String = encodeEffect(effect).toString()

    fun decodeEffectFromString(raw: String?): RuleEffect? =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(::decodeEffect)

    fun encodeTargetToString(target: RuleTarget): String = encodeTarget(target).toString()

    fun decodeTargetFromString(raw: String?): RuleTarget? =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(::decodeTarget)

    private const val KEY_KIND = "kind"
    private const val KEY_VALUE = "value"
    private const val KEY_PARTS = "parts"
    private const val KEY_PARTS_CATEGORIES = "partsCategories"
    private const val KEY_MINUTES = "minutes"
    private const val KEY_DAYS = "days"
    private const val KEY_START = "startMinute"
    private const val KEY_END = "endMinute"
    private const val KEY_PARITY_ANCHOR = "parityAnchorEpochDay"

    private const val MINUTES_PER_DAY = 24 * 60
    private const val SECONDS_PER_MINUTE = 60

    private const val ALWAYS = "always"
    private const val WIFI = "wifi"
    private const val NEAR = "near"
    private const val PLACE = "place"
    private const val BATTERY = "battery"
    private const val WINDOW = "window"
    private const val ALLOWANCE_SPENT = "allowanceSpent"
    private const val ALL_OF = "allOf"
    private const val ANY_OF = "anyOf"
    private const val NOT = "not"

    private const val BLOCK = "block"
    private const val ALLOW = "allow"
    private const val SAFETY_EXEMPT = "safetyExempt"
    private const val BUDGET = "budget"

    private const val EVERYTHING = "everything"
    private const val APPS = "apps"
    private const val CATEGORIES = "categories"
    private const val MIXED = "mixed"
    private const val EVERYTHING_EXCEPT = "everythingExcept"
}
