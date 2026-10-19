package dev.schrammel.protego_parentis.ui.rules

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.rules.LocalPolicyStore

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
 *
 * The daily limit is the one rule with a well-known id ([LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID]),
 * and it is grouped first rather than left out of the grouping — it is a rule
 * like any other, just the one the screen leads with, so it belongs in the same
 * system rather than a pinned slot above it (rules-UX phase E).
 */
internal enum class RuleSection { DAILY_LIMIT, SCHEDULES, WHERE, HEALTH, OTHER, PAUSED }

internal data class RuleGroup(val section: RuleSection, val rules: List<ContextRule>)

/**
 * The rules, grouped and ordered. The daily limit's group comes first, and
 * empty groups are dropped rather than shown as a heading over nothing.
 */
internal fun ruleGroups(rules: List<ContextRule>): List<RuleGroup> =
    RuleSection.entries.mapNotNull { section ->
        val inSection = rules.filter { sectionOf(it) == section }.sortedBy { it.name.lowercase() }
        if (inSection.isEmpty()) null else RuleGroup(section, inSection)
    }

/**
 * Which group [rule] belongs in.
 *
 * The daily limit keeps its own group, and stays there even when switched off:
 * it is the reason the app exists, so it keeps its place at the top rather than
 * slipping down into "paused" the moment it is toggled. Every other rule follows
 * the kind it is written in, with paused winning over every kind.
 */
internal fun sectionOf(rule: ContextRule): RuleSection {
    if (rule.id == LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID) return RuleSection.DAILY_LIMIT
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
