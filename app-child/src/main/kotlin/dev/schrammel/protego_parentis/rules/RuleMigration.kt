package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.model.WeeklyBudget
import kotlin.time.Duration.Companion.minutes

/**
 * What each of [LocalPolicyStore]'s legacy, field-by-field rules becomes in
 * the unified [ContextRule] format — the actual business decision behind
 * [LocalPolicyStore.migrateToUnifiedRules] and
 * [LocalPolicyStore.migrateAppRulesToUnifiedRules], split out from them.
 *
 * Those two methods keep the parts that are genuinely this device's own
 * bookkeeping — checking and setting the migration flag, and the
 * SharedPreferences plumbing that requires — while every function here is a
 * pure `legacy value in, [ContextRule]s out` translation with no persistence
 * of its own, so a change to one shape can be exercised directly rather than
 * only through the whole store.
 */
internal object RuleMigration {

    /** One block rule per stored schedule, skipping any [StoredSchedule.toDomain] refuses. */
    fun scheduleRules(schedules: List<StoredSchedule>): List<ContextRule> = schedules.mapNotNull { stored ->
        val block = stored.toDomain() ?: return@mapNotNull null
        ContextRule(
            id = MigratedRuleIds.schedule(stored.id),
            name = stored.name,
            enabled = stored.enabled,
            condition = Condition.DuringWindow(block.window),
            effect = RuleEffect.Block(),
            target = when (stored.mode) {
                ScheduleBlock.Mode.INCLUSIVE -> RuleTarget.Apps(block.packages)
                ScheduleBlock.Mode.EXCLUSIVE -> RuleTarget.EverythingExcept(block.packages)
            },
        )
    }

    /** The flat daily limit as an always-on device budget rule, or null when there is no limit. */
    fun dailyLimitRule(context: Context, limitMinutes: Int): ContextRule? {
        if (limitMinutes <= 0) return null
        return ContextRule(
            id = LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID,
            name = context.getString(R.string.rules_row_limits),
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.SetBudget(WeeklyBudget.flat(limitMinutes.minutes)),
            target = RuleTarget.Everything,
        )
    }

    /** One safety-exempt rule per exempt package. */
    fun exemptRules(context: Context, exemptPackages: Set<String>): List<ContextRule> = exemptPackages.map { pkg ->
        ContextRule(
            id = MigratedRuleIds.exempt(PackageName(pkg)),
            name = context.getString(R.string.rules_exempt_header),
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.SafetyExempt,
            target = RuleTarget.Apps(setOf(PackageName(pkg))),
        )
    }

    /** One always-on app budget rule per package with a positive per-app limit. */
    fun appBudgetRules(context: Context, appLimits: Map<String, Int>): List<ContextRule> =
        appLimits.filterValues { it > 0 }.map { (pkg, minutes) ->
            ContextRule(
                id = MigratedRuleIds.appBudget(PackageName(pkg)),
                name = context.getString(R.string.app_limits_title),
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.SetBudget(WeeklyBudget.flat(minutes.minutes)),
                target = RuleTarget.Apps(setOf(PackageName(pkg))),
            )
        }

    /**
     * One block rule per blocked or hidden package, [blockedPackages] first
     * so a package in both sets — which no screen produces today, but a
     * hand-edited or replicated-from-an-older-build preference file might —
     * ends up at [hiddenPackages]'s stricter shape: the caller upserts this
     * list in order, and a later upsert with the same id replaces an earlier
     * one.
     */
    fun appBlockRules(context: Context, blockedPackages: Set<String>, hiddenPackages: Set<String>): List<ContextRule> {
        fun blockRule(pkg: String, hidden: Boolean) = ContextRule(
            id = MigratedRuleIds.appBlock(PackageName(pkg)),
            name = context.getString(R.string.apps_title),
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block(hidden = hidden),
            target = RuleTarget.Apps(setOf(PackageName(pkg))),
        )
        return blockedPackages.map { blockRule(it, hidden = false) } +
            hiddenPackages.map { blockRule(it, hidden = true) }
    }

    /** One always-on category budget rule per category with a positive limit. */
    fun categoryBudgetRules(categories: List<StoredCategory>): List<ContextRule> =
        categories.filter { it.limitMinutes > 0 }.map { stored ->
            ContextRule(
                id = MigratedRuleIds.categoryBudget(stored.id),
                name = stored.name,
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.SetBudget(WeeklyBudget.flat(stored.limitMinutes.minutes)),
                target = RuleTarget.Categories(setOf(CategoryId(stored.id))),
            )
        }
}
