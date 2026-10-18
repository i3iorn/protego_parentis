package dev.schrammel.protego_parentis.ui.rules

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule

/**
 * How the rules list groups itself, decided here rather than in the screen so
 * it can be tested without Android — the same split [dev.schrammel.protego_parentis.ui.HomeSections]
 * already uses for the home screens.
 *
 * Grouped by what a parent recognises — a schedule, somewhere or something the
 * phone is near, a health reading — not by which part of the model implements
 * the condition. Paused rules are their own group regardless of kind: a rule
 * that is off is not "a schedule", it is a rule that is off, and hiding the
 * inactive ones at the bottom is what keeps the active setup scannable.
 */
internal enum class RuleSection { SCHEDULES, WHERE, HEALTH, OTHER, PAUSED }

internal data class RuleGroup(val section: RuleSection, val rules: List<ContextRule>)

/**
 * The rules, grouped and ordered. The daily limit is not among them — it keeps
 * its own pinned slot above the list — and empty groups are dropped rather than
 * shown as a heading over nothing.
 */
internal fun ruleGroups(rules: List<ContextRule>): List<RuleGroup> =
    RuleSection.entries.mapNotNull { section ->
        val inSection = rules.filter { sectionOf(it) == section }.sortedBy { it.name.lowercase() }
        if (inSection.isEmpty()) null else RuleGroup(section, inSection)
    }

/** Which group [rule] belongs in. Paused wins over every kind. */
internal fun sectionOf(rule: ContextRule): RuleSection {
    if (!rule.enabled) return RuleSection.PAUSED
    val leaves = conditionLeaves(rule.condition)
    return when {
        leaves.any { it is Condition.GlucoseBelow || it is Condition.GlucoseAbove || it is Condition.GlucoseStale } ->
            RuleSection.HEALTH

        // A plain window is a schedule; a window combined with somewhere the
        // phone must also be is a "where", which is the harder half to guess.
        leaves.any { it is Condition.DuringWindow } && leaves.none { it.isPlaceLike } ->
            RuleSection.SCHEDULES

        leaves.any { it.isPlaceLike } -> RuleSection.WHERE
        else -> RuleSection.OTHER
    }
}

private val Condition.isPlaceLike: Boolean
    get() = this is Condition.OnWifi || this is Condition.AtPlace || this is Condition.NearDevice

/** Every leaf of a condition tree, ignoring how the groups combined them. */
private fun conditionLeaves(condition: Condition): List<Condition> = when (condition) {
    is Condition.AllOf -> condition.conditions.flatMap(::conditionLeaves)
    is Condition.AnyOf -> condition.conditions.flatMap(::conditionLeaves)
    is Condition.AtLeast -> condition.conditions.flatMap(::conditionLeaves)
    is Condition.Not -> conditionLeaves(condition.condition)
    else -> listOf(condition)
}
