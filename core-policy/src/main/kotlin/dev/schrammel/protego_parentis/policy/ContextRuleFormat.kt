package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule

/**
 * Which rules an older app version can read, and which app versions can read
 * the rest.
 *
 * Rules replicate between phones that may not all run the same build. A V1
 * rule is one a build before [V2_MIN_VERSION] reads correctly: one action, no
 * "except when", and only condition kinds it knows. Nested groups and a NOT on
 * a single condition are V1: the old enforcement already evaluated any
 * AllOf/AnyOf/Not tree, only the old editor showed them flat.
 *
 * Anything else is V2, and is written so that an older build drops it rather
 * than half-enforcing it. Dropping it is not harmless: an older child's phone
 * republishes its own rule list on every sync, which deletes rules it could
 * not read. So the editor uses [supportsV2] to warn a parent before saving a
 * V2 rule while any phone in the family might be older.
 */
object ContextRuleFormat {

    /** The first build that reads V2 rules. */
    const val V2_MIN_VERSION = "1.2.0"

    /** The first build that reads glucose conditions. */
    const val GLUCOSE_MIN_VERSION = "1.3.0"

    /**
     * The most actions one rule may have. Storage reads an action count
     * before the actions themselves, and this bounds what a corrupt count can
     * ask a reader to look for.
     */
    const val MAX_ACTIONS = 32

    /**
     * Whether [rule] has to be stored in the V2 shape: without the V1
     * effect/target fields, so a build before [V2_MIN_VERSION] drops it.
     *
     * Every condition kind added after V1 counts, glucose included: a build
     * before 1.2.0 reads a condition it doesn't know as Always, which would
     * turn "block games while glucose is high" into "block games".
     */
    fun needsV2(rule: ContextRule): Boolean =
        rule.actions.size > 1 || rule.exceptWhen != null || usesNewCondition(rule.condition)

    /**
     * The oldest app version that reads [rule] correctly, or null for a V1
     * rule every version reads.
     */
    fun requiredVersion(rule: ContextRule): String? = when {
        usesGlucose(rule.condition) || rule.exceptWhen?.let(::usesGlucose) == true -> GLUCOSE_MIN_VERSION
        needsV2(rule) -> V2_MIN_VERSION
        else -> null
    }

    private fun usesNewCondition(condition: Condition): Boolean =
        anyIn(condition) { it is Condition.AtLeast || isGlucose(it) }

    private fun usesGlucose(condition: Condition): Boolean = anyIn(condition, test = ::isGlucose)

    private fun isGlucose(condition: Condition) =
        condition is Condition.GlucoseBelow || condition is Condition.GlucoseAbove || condition is Condition.GlucoseStale

    private fun anyIn(condition: Condition, depth: Int = 0, test: (Condition) -> Boolean): Boolean {
        if (depth > ContextRules.MAX_DEPTH) return false
        if (test(condition)) return true
        return when (condition) {
            is Condition.AtLeast -> condition.conditions.any { anyIn(it, depth + 1, test) }
            is Condition.AllOf -> condition.conditions.any { anyIn(it, depth + 1, test) }
            is Condition.AnyOf -> condition.conditions.any { anyIn(it, depth + 1, test) }
            is Condition.Not -> anyIn(condition.condition, depth + 1, test)
            Condition.Always,
            is Condition.OnWifi,
            is Condition.NearDevice,
            is Condition.AtPlace,
            is Condition.BatteryBelow,
            is Condition.DuringWindow,
            is Condition.AllowanceSpent,
            is Condition.GlucoseBelow,
            is Condition.GlucoseAbove,
            is Condition.GlucoseStale,
            -> false
        }
    }

    /**
     * Whether a phone reporting [versionName] is at least [required].
     *
     * False for a version that is missing or doesn't parse: a phone that has
     * never said what it runs is treated as old, since the cost of a needless
     * warning is a tap and the cost of a missing one is a rule that silently
     * does nothing.
     */
    fun supports(versionName: String?, required: String): Boolean {
        val version = parse(versionName) ?: return false
        val minimum = parse(required) ?: return false
        for (i in 0 until maxOf(version.size, minimum.size)) {
            val a = version.getOrElse(i) { 0 }
            val b = minimum.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    fun supportsV2(versionName: String?): Boolean = supports(versionName, V2_MIN_VERSION)

    /**
     * What the family's phones ([phones], a name and last reported version
     * each) are missing, or null when nothing.
     *
     * Urgent ([MixedVersions.rulesAtRisk]) when a rule in [rules] needs a
     * version some phone hasn't reported, since an older child's phone
     * deletes it on its next sync; [MixedVersions.version] is then the
     * newest version any rule needs. Otherwise a reminder while any phone is
     * below [V2_MIN_VERSION], before anyone writes such a rule.
     */
    fun mixedVersions(phones: List<Pair<String, String?>>, rules: List<ContextRule>): MixedVersions? {
        fun below(required: String) = phones.filterNot { (_, version) -> supports(version, required) }.map { it.first }

        val needed = rules.mapNotNull(::requiredVersion).maxWithOrNull(::compareVersions)
        if (needed != null) {
            val atRisk = below(needed)
            if (atRisk.isNotEmpty()) return MixedVersions(atRisk, rulesAtRisk = true, version = needed)
        }
        val older = below(V2_MIN_VERSION)
        if (older.isEmpty()) return null
        return MixedVersions(older, rulesAtRisk = false, version = V2_MIN_VERSION)
    }

    data class MixedVersions(val olderPhones: List<String>, val rulesAtRisk: Boolean, val version: String)

    private fun compareVersions(a: String, b: String): Int = when {
        a == b -> 0
        supports(a, b) -> 1
        else -> -1
    }

    /** "1.2.0", or "1.2.0-debug": the numeric part before any suffix. */
    private fun parse(versionName: String?): List<Int>? {
        val numeric = versionName?.trim()?.substringBefore('-')?.takeIf(String::isNotEmpty) ?: return null
        return numeric.split('.').map { it.toIntOrNull() ?: return null }
    }
}
