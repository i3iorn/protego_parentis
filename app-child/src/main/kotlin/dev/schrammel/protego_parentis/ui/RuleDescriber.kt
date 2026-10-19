package dev.schrammel.protego_parentis.ui

import android.app.Activity
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.glucose.GlucoseUnit
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget

/**
 * A rule's parts as phrases, shared by the rules list and the rule editor so
 * the two can't describe the same rule differently.
 *
 * [placeName] and [categoryName] are looked up lazily because both screens
 * read them from whichever phone's rules they are showing.
 */
internal class RuleDescriber(
    private val activity: Activity,
    private val placeName: (PlaceId) -> String?,
    private val categoryName: (CategoryId) -> String?,
    /** The family's unit, if this phone knows it; both units are shown when it doesn't. */
    private val glucoseUnit: GlucoseUnit? = null,
) {

    /** The whole rule as one sentence: when, except when, then every action. */
    fun rule(rule: ContextRule): String {
        val whenText = rule.exceptWhen
            ?.let { activity.getString(R.string.context_when_except, condition(rule.condition), condition(it)) }
            ?: condition(rule.condition)
        return if (rule.actions.size == 1) {
            activity.getString(R.string.context_rule_summary, whenText, effect(rule.effect), target(rule.target))
        } else {
            activity.getString(
                R.string.context_rule_summary_actions,
                whenText,
                rule.actions.joinToString(activity.getString(R.string.context_join_actions)) { action(it) },
            )
        }
    }

    /**
     * The rule as labelled parts for a rule card (rules-UX phase C) — the same
     * phrases [rule] concatenates, kept separate so a screen can label each
     * one and a screen reader can read them in order. Separate rows also
     * translate more safely than one concatenated sentence, whose word order
     * is fixed in the template (§7).
     *
     * [RuleParts.kind] is the family of the rule's first leaf, read through any
     * negation — it is the eye's first sort, not the whole truth for a rule
     * that mixes families.
     */
    data class RuleParts(
        val kind: RuleKind,
        /** One phrase per leaf of the "when" tree, in tree order, negations kept. */
        val whenLines: List<String>,
        /** One phrase per action: effect and target, as [context_action_phrase]. */
        val thenLines: List<String>,
        /** The "except when" phrase alone, or null when the rule has no exception. */
        val except: String?,
    )

    fun parts(rule: ContextRule): RuleParts = RuleParts(
        kind = RuleKind.of(rule.condition),
        whenLines = leavesOf(rule.condition).map { condition(it, nested = true) },
        thenLines = rule.actions.map { action(it) },
        except = rule.exceptWhen?.let { condition(it, nested = false) },
    )

    fun action(action: RuleAction): String =
        activity.getString(R.string.context_action_phrase, effect(action.effect), target(action.target))

    /**
     * [current] as a phrase. A group inside another group is bracketed, so
     * "school and (home or low battery)" can't be misread.
     */
    fun condition(current: Condition, nested: Boolean = false): String = when (current) {
        is Condition.Always -> activity.getString(R.string.context_when_always)
        is Condition.OnWifi -> activity.getString(R.string.context_when_wifi, current.ssid)
        is Condition.NearDevice -> activity.getString(R.string.context_when_near, current.tag.value)
        is Condition.AtPlace -> activity.getString(
            R.string.context_when_place,
            placeName(current.place) ?: activity.getString(R.string.context_place_unknown),
        )

        is Condition.BatteryBelow -> activity.getString(R.string.context_when_battery, current.percent)

        // #101. Phrased from the same day and time vocabulary a schedule uses,
        // because to a parent this *is* a schedule, written as a rule.
        is Condition.DuringWindow -> activity.getString(
            if (current.window.parityAnchor == null) {
                R.string.context_when_window
            } else {
                R.string.context_when_window_alternating
            },
            DayPicker.describe(activity, current.window.days),
            activity.getString(
                R.string.schedules_window,
                formatTime(current.window.start),
                formatTime(current.window.end),
            ),
        )

        is Condition.AllowanceSpent -> activity.getString(R.string.context_when_allowance_spent, current.minutes)

        is Condition.GlucoseBelow ->
            activity.getString(R.string.context_when_glucose_below, activity.glucoseText(current.mgdl, glucoseUnit))
        is Condition.GlucoseAbove ->
            activity.getString(R.string.context_when_glucose_above, activity.glucoseText(current.mgdl, glucoseUnit))
        is Condition.GlucoseStale -> activity.getString(R.string.context_when_glucose_stale, current.minutes)

        is Condition.Not -> activity.getString(R.string.context_when_not, condition(current.condition, nested = true))

        is Condition.AllOf -> bracketed(nested, current.conditions.size) {
            current.conditions
                .joinToString(activity.getString(R.string.context_join_and)) { condition(it, nested = true) }
                .ifBlank { activity.getString(R.string.context_when_always) }
        }

        is Condition.AnyOf -> bracketed(nested, current.conditions.size) {
            current.conditions
                .joinToString(activity.getString(R.string.context_join_or)) { condition(it, nested = true) }
                .ifBlank { activity.getString(R.string.context_when_never) }
        }

        is Condition.AtLeast -> activity.getString(
            R.string.context_when_at_least,
            current.count,
            current.conditions.joinToString(activity.getString(R.string.context_join_list)) {
                condition(it, nested = true)
            },
        )
    }

    private fun bracketed(nested: Boolean, size: Int, text: () -> String): String =
        if (nested && size > 1) activity.getString(R.string.context_when_group, text()) else text()

    fun effect(current: RuleEffect): String = when (current) {
        is RuleEffect.Block -> activity.getString(R.string.context_effect_block)
        is RuleEffect.Allow -> activity.getString(R.string.context_effect_allow)
        is RuleEffect.AlwaysBlock -> activity.getString(R.string.context_effect_always_block)
        is RuleEffect.SafetyExempt -> activity.getString(R.string.context_effect_safety_exempt)
        is RuleEffect.SetBudget ->
            activity.getString(R.string.context_effect_budget, activity.formatDuration(current.budget.default))
    }

    fun target(current: RuleTarget): String = when (current) {
        is RuleTarget.Everything -> activity.getString(R.string.context_target_everything)
        is RuleTarget.Apps -> apps(current.packages)
        is RuleTarget.Categories -> categories(current.categories)
        is RuleTarget.Mixed ->
            activity.getString(R.string.context_target_mixed, apps(current.packages), categories(current.categories))
        is RuleTarget.EverythingExcept ->
            activity.getString(R.string.context_target_everything_except, apps(current.packages))
    }

    private fun apps(packages: Set<PackageName>): String =
        activity.resources.getQuantityString(R.plurals.rules_category_apps, packages.size, packages.size)

    private fun categories(categories: Set<CategoryId>): String = categories
        .mapNotNull(categoryName)
        .joinToString(", ")
        .ifBlank { activity.getString(R.string.context_target_categories) }
}

/**
 * Every leaf of a condition tree in tree order, keeping negations where they
 * are written: a leaf's phrase is exactly the row its editor shows, so "not on
 * school Wi-Fi" stays negated here, while [conditionToFamily] reads through
 * the negation to keep calling the rule about Wi-Fi.
 */
private fun leavesOf(condition: Condition): List<Condition> = when (condition) {
    is Condition.AllOf -> condition.conditions.flatMap(::leavesOf)
    is Condition.AnyOf -> condition.conditions.flatMap(::leavesOf)
    is Condition.AtLeast -> condition.conditions.flatMap(::leavesOf)
    else -> listOf(condition)
}
