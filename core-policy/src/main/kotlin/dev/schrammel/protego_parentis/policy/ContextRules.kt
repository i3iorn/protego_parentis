package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceContext
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import java.time.ZonedDateTime

/** What the active contextual rules add up to. */
data class ContextOutcome(
    /** Blocked outright while their rule applies, with the rule that did it. */
    val blocked: Map<PackageName, ContextRule>,
    /** Exempt from every limit while their rule applies. */
    val allowed: Set<PackageName>,
    /** Replaces the device budget when present. */
    val deviceBudget: WeeklyBudget?,
    /** The rules that are currently applying, for showing on screen. */
    val activeRules: List<ContextRule>,
)

/**
 * Works out which contextual rules apply, and what they add up to.
 *
 * Pure, and given only a resolved [DeviceContext] — no coordinates, no scan
 * results. Everything awkward about "where am I" is settled before this point,
 * which is what lets the interesting part be tested exhaustively without a phone.
 *
 * Two resolution rules, both chosen so a parent cannot accidentally create a hole:
 *
 *  - **Allow beats block.** An explicit "this app is fine here" is a more
 *    deliberate act than a broad block, and the safety allowlist already works
 *    this way, so the two behave alike.
 *  - **The tightest budget wins.** Several rules setting a device budget resolve
 *    to the smallest. A rule can tighten, never loosen past another rule.
 */
object ContextRules {

    /**
     * How deep a nested condition may go before it is treated as malformed.
     *
     * Conditions come from storage and could be corrupted or hand-edited; without
     * a bound, a deeply nested or self-referential structure would recurse until
     * the stack gave out inside the enforcement service.
     */
    const val MAX_DEPTH = 12

    fun evaluate(
        rules: List<ContextRule>,
        context: DeviceContext,
        hierarchy: ResolvedHierarchy,
        installedPackages: Set<PackageName>,
        /**
         * The moment to evaluate at (#101).
         *
         * Required rather than defaulted, and passed rather than read from a
         * clock: [Condition.DuringWindow] is the one condition [DeviceContext]
         * cannot answer, and a default would let a caller silently evaluate
         * every time-window rule as inactive. A restriction that disappears
         * because somebody forgot an argument is exactly the failure this
         * module's fail-closed conventions exist to prevent, so it is a
         * compile error instead.
         */
        now: ZonedDateTime,
    ): ContextOutcome {
        val active = rules.filter { it.enabled && holds(it.condition, context, now) }

        val blocked = LinkedHashMap<PackageName, ContextRule>()
        val allowed = mutableSetOf<PackageName>()
        var budget: WeeklyBudget? = null

        active.forEach { rule ->
            when (val effect = rule.effect) {
                is RuleEffect.Block ->
                    resolve(rule.target, hierarchy, installedPackages).forEach { pkg ->
                        blocked.putIfAbsent(pkg, rule)
                    }

                is RuleEffect.Allow ->
                    allowed += resolve(rule.target, hierarchy, installedPackages)

                is RuleEffect.SetDeviceBudget ->
                    budget = tightest(budget, effect.budget)
            }
        }

        return ContextOutcome(
            // Allow wins: see the note above.
            blocked = blocked.filterKeys { it !in allowed },
            allowed = allowed,
            deviceBudget = budget,
            activeRules = active,
        )
    }

    /**
     * Whether [condition] holds in [context].
     *
     * An empty [Condition.AllOf] is true and an empty [Condition.AnyOf] is false,
     * following the usual reading of "all of nothing" and "any of nothing". The
     * practical effect is that a half-built rule with no conditions chosen yet does
     * not silently start blocking things.
     */
    fun holds(condition: Condition, context: DeviceContext, now: ZonedDateTime, depth: Int = 0): Boolean {
        if (depth > MAX_DEPTH) return false

        return when (condition) {
            is Condition.Always -> true

            // Exact match. A missing permission leaves the SSID null, which fails
            // every Wi-Fi condition rather than passing them.
            is Condition.OnWifi -> context.wifiSsid == condition.ssid

            is Condition.NearDevice -> condition.tag in context.nearbyDevices

            is Condition.AtPlace -> condition.place in context.insidePlaces

            // Same fail-closed convention as Wi-Fi: an unreadable battery level
            // must not be indistinguishable from "plenty of charge left".
            is Condition.BatteryBelow ->
                context.batteryPercent?.let { it <= condition.percent } ?: false

            // Resolved by the same arithmetic a schedule is (#101), so a rule
            // written this way and a schedule written the old way cannot
            // disagree about midnight or an alternating week.
            is Condition.DuringWindow -> ScheduleResolver.isActive(condition.window, now)

            is Condition.AllOf ->
                condition.conditions.all { holds(it, context, now, depth + 1) }

            is Condition.AnyOf ->
                condition.conditions.any { holds(it, context, now, depth + 1) }

            is Condition.Not -> !holds(condition.condition, context, now, depth + 1)
        }
    }

    /** The packages a target covers, following category nesting. */
    private fun resolve(
        target: RuleTarget,
        hierarchy: ResolvedHierarchy,
        installedPackages: Set<PackageName>,
    ): Set<PackageName> = when (target) {
        is RuleTarget.Everything -> installedPackages
        is RuleTarget.Apps -> target.packages
        is RuleTarget.Categories -> target.categories.flatMapTo(mutableSetOf()) { id: CategoryId ->
            hierarchy.packagesIn(id)
        }
        is RuleTarget.Mixed -> target.packages + target.categories.flatMapTo(mutableSetOf()) { id: CategoryId ->
            hierarchy.packagesIn(id)
        }
    }

    /** The smaller of two budgets, comparing the most permissive day of each. */
    private fun tightest(existing: WeeklyBudget?, candidate: WeeklyBudget): WeeklyBudget {
        if (existing == null) return candidate
        val existingPeak = peak(existing)
        val candidatePeak = peak(candidate)
        return if (candidatePeak < existingPeak) candidate else existing
    }

    private fun peak(budget: WeeklyBudget) =
        java.time.DayOfWeek.entries.maxOf { budget.forDay(it) }
}
