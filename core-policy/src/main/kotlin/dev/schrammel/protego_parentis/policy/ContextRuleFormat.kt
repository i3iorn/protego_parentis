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
 * than half-enforcing it. The editor uses [supportsV2] to warn a parent before
 * saving a V2 rule that some phone in the family would ignore.
 */
object ContextRuleFormat {

    /** The first build that reads V2 rules. */
    const val V2_MIN_VERSION = "1.2.0"

    /**
     * The most actions one rule may have. Storage reads an action count
     * before the actions themselves, and this bounds what a corrupt count can
     * ask a reader to look for.
     */
    const val MAX_ACTIONS = 32

    fun needsV2(rule: ContextRule): Boolean =
        rule.actions.size > 1 || rule.exceptWhen != null || usesV2Condition(rule.condition)

    private fun usesV2Condition(condition: Condition, depth: Int = 0): Boolean {
        if (depth > ContextRules.MAX_DEPTH) return false
        return when (condition) {
            is Condition.AtLeast -> true
            is Condition.AllOf -> condition.conditions.any { usesV2Condition(it, depth + 1) }
            is Condition.AnyOf -> condition.conditions.any { usesV2Condition(it, depth + 1) }
            is Condition.Not -> usesV2Condition(condition.condition, depth + 1)
            Condition.Always,
            is Condition.OnWifi,
            is Condition.NearDevice,
            is Condition.AtPlace,
            is Condition.BatteryBelow,
            is Condition.DuringWindow,
            is Condition.AllowanceSpent,
            -> false
        }
    }

    /**
     * Whether a phone reporting [versionName] reads V2 rules.
     *
     * False for a version that is missing or doesn't parse: a phone that has
     * never said what it runs is treated as old, since the cost of a needless
     * warning is a tap and the cost of a missing one is a rule that silently
     * does nothing.
     */
    fun supportsV2(versionName: String?): Boolean {
        val version = parse(versionName) ?: return false
        val minimum = parse(V2_MIN_VERSION) ?: return false
        for (i in 0 until maxOf(version.size, minimum.size)) {
            val a = version.getOrElse(i) { 0 }
            val b = minimum.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    /** "1.2.0", or "1.2.0-debug": the numeric part before any suffix. */
    private fun parse(versionName: String?): List<Int>? {
        val numeric = versionName?.trim()?.substringBefore('-')?.takeIf(String::isNotEmpty) ?: return null
        return numeric.split('.').map { it.toIntOrNull() ?: return null }
    }
}
