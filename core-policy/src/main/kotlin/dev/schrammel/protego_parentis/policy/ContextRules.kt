package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceContext
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What the active contextual rules add up to.
 *
 * Internal: [PolicyEngine.evaluate] is this module's one public entry point,
 * and it folds this into the [dev.schrammel.protego_parentis.model.Enforcement]
 * it returns rather than handing the intermediate shape to callers.
 */
internal data class ContextOutcome(
    /** Blocked outright while their rule applies, with the rule that did it. */
    val blocked: Map<PackageName, ContextRule>,
    /** Exempt from every limit while their rule applies. */
    val allowed: Set<PackageName>,
    /**
     * Exempt from everything while their rule applies — the same tier as the
     * built-in safety allowlist, not just the other contextual rules [allowed]
     * settles. See [RuleEffect.SafetyExempt].
     */
    val safetyExempt: Set<PackageName>,
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
 *
 * Internal: reached only through [PolicyEngine.evaluate].
 */
internal object ContextRules {

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
        /**
         * How much of the device's daily allowance is already spent (#102).
         *
         * Same requirement as [now]: [Condition.AllowanceSpent] cannot be
         * answered from [DeviceContext] alone, and a defaulted usage figure
         * would let a caller silently evaluate every allowance condition as
         * never met. Net of bonus time, exactly like the dedicated
         * `Policy.deviceBudget` comparison this condition is meant to match —
         * see `PolicyEngine`'s call site for where that netting happens.
         */
        deviceUsage: Duration,
    ): ContextOutcome {
        val active = rules.filter { it.enabled && holds(it.condition, context, now, deviceUsage) }

        val blocked = LinkedHashMap<PackageName, ContextRule>()
        val allowed = mutableSetOf<PackageName>()
        val safetyExempt = mutableSetOf<PackageName>()
        var budget: WeeklyBudget? = null

        active.forEach { rule ->
            when (val effect = rule.effect) {
                is RuleEffect.Block ->
                    resolve(rule.target, hierarchy, installedPackages).forEach { pkg ->
                        blocked.putIfAbsent(pkg, rule)
                    }

                is RuleEffect.Allow ->
                    allowed += resolve(rule.target, hierarchy, installedPackages)

                is RuleEffect.SafetyExempt ->
                    safetyExempt += resolve(rule.target, hierarchy, installedPackages)

                is RuleEffect.SetDeviceBudget ->
                    budget = tightest(budget, effect.budget, now.dayOfWeek)
            }
        }

        return ContextOutcome(
            // Allow wins: see the note above.
            blocked = blocked.filterKeys { it !in allowed && it !in safetyExempt },
            allowed = allowed,
            safetyExempt = safetyExempt,
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
    fun holds(
        condition: Condition,
        context: DeviceContext,
        now: ZonedDateTime,
        deviceUsage: Duration,
        depth: Int = 0,
    ): Boolean {
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

            is Condition.AllowanceSpent -> deviceUsage >= condition.minutes.minutes

            is Condition.AllOf ->
                condition.conditions.all { holds(it, context, now, deviceUsage, depth + 1) }

            is Condition.AnyOf ->
                condition.conditions.any { holds(it, context, now, deviceUsage, depth + 1) }

            is Condition.Not -> !holds(condition.condition, context, now, deviceUsage, depth + 1)
        }
    }

    /**
     * When [condition] stops holding, or null if this app cannot say (#103).
     *
     * Only meaningful for a condition that holds at [now] — the caller has
     * already decided the rule is active, and "when does a thing that is not
     * happening stop happening" has no answer worth showing anyone.
     *
     * Null means "no end this app can promise", and is deliberately not the
     * same claim as "never ends". Both are unshowable, so both are null; the
     * block screen simply names the rule and says nothing about when.
     *
     * The rule throughout is that under-promising is safe and over-promising is
     * not. A block that lifts earlier than announced is a pleasant surprise; one
     * that is still there at the time the phone promised it would end is the
     * app having lied to a child about its own rules, which is the one thing
     * this product cannot afford.
     *
     *  - [Condition.DuringWindow] knows its own end exactly.
     *  - [Condition.AllOf] holds only while every branch does, so it stops when
     *    the first branch stops: the earliest known end. Branches that cannot
     *    say drop out of the comparison, which is safe — they can only make the
     *    real end earlier than the one reported, never later.
     *  - [Condition.AnyOf] reports nothing, even when every branch knows its
     *    own end. It holds while *any* branch does, so a branch that is not
     *    holding now can start later and extend the block past any time
     *    computed from the branches that are. That is precisely the
     *    over-promise above. (An earlier note of mine on #103 claimed an OR
     *    has a knowable end whenever all its branches do — that is wrong for
     *    exactly this reason, and the issue has been corrected.)
     *  - [Condition.Not] reports nothing. "Not at school" stops when school
     *    starts, which is a question about when a condition *begins* — a
     *    different traversal from this one, and not one anything needs yet.
     */
    fun liftsAt(condition: Condition, now: ZonedDateTime, depth: Int = 0): Instant? {
        if (depth > MAX_DEPTH) return null

        return when (condition) {
            is Condition.DuringWindow -> ScheduleResolver.endOfActiveWindow(condition.window, now)

            is Condition.AllOf ->
                condition.conditions.mapNotNull { liftsAt(it, now, depth + 1) }.minOrNull()

            is Condition.AnyOf -> null
            is Condition.Not -> null

            // The day boundary is the real end, but this function has no policy
            // to ask for one (see the class doc on under- vs over-promising) —
            // guessing here would risk disagreeing with `PolicyEngine`'s own
            // answer for the dedicated device-budget path this mirrors.
            is Condition.AllowanceSpent -> null

            is Condition.Always,
            is Condition.OnWifi,
            is Condition.NearDevice,
            is Condition.AtPlace,
            is Condition.BatteryBelow,
            -> null
        }
    }

    /**
     * Every time window anywhere in [condition], however deeply nested (#103).
     *
     * Enforcement sets an exact alarm at the next moment its answer could
     * change, rather than polling. A schedule's edges already fed that; a rule
     * carrying a window would otherwise stay blocked until something unrelated
     * woke the service, which on a quiet phone can be hours. Collected from the
     * whole tree — including under a [Condition.Not] or an [Condition.AnyOf],
     * whose *ends* this module will not promise but whose edges are still
     * exactly when the answer changes.
     */
    fun windowsIn(condition: Condition, depth: Int = 0): List<RecurringWindow> {
        if (depth > MAX_DEPTH) return emptyList()

        return when (condition) {
            is Condition.DuringWindow -> listOf(condition.window)
            is Condition.AllOf -> condition.conditions.flatMap { windowsIn(it, depth + 1) }
            is Condition.AnyOf -> condition.conditions.flatMap { windowsIn(it, depth + 1) }
            is Condition.Not -> windowsIn(condition.condition, depth + 1)

            is Condition.Always,
            is Condition.OnWifi,
            is Condition.NearDevice,
            is Condition.AtPlace,
            is Condition.BatteryBelow,
            is Condition.AllowanceSpent,
            -> emptyList()
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
        is RuleTarget.EverythingExcept -> installedPackages - target.packages
    }

    /**
     * The smaller of two budgets, comparing [today]'s value of each.
     *
     * Comparing weekly peaks would let a budget with a low peak but a high value
     * on the day actually being evaluated win over one that is genuinely tighter
     * today — violating "a rule can tighten, never loosen past another rule".
     */
    private fun tightest(
        existing: WeeklyBudget?,
        candidate: WeeklyBudget,
        today: java.time.DayOfWeek,
    ): WeeklyBudget {
        if (existing == null) return candidate
        return if (candidate.forDay(today) < existing.forDay(today)) candidate else existing
    }
}
