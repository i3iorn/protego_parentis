package dev.schrammel.dementor_watch.policy

import dev.schrammel.dementor_watch.model.AppRule
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.model.BudgetStatus
import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.DeviceState
import dev.schrammel.dementor_watch.model.Enforcement
import dev.schrammel.dementor_watch.model.Grant
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Policy
import dev.schrammel.dementor_watch.model.Reason
import dev.schrammel.dementor_watch.model.UsageSnapshot
import dev.schrammel.dementor_watch.model.WarningThresholds
import java.time.Instant
import kotlin.time.Duration

/**
 * Decides what the device should look like right now.
 *
 * Pure: same inputs, same [Enforcement], no clock and no I/O. Everything that
 * makes this product correct — day boundaries, midnight-crossing bedtimes,
 * conflicting rules, the safety allowlist — is decided here and tested here,
 * so none of it depends on having a provisioned handset to hand.
 *
 * The caller is expected to be a single reconciler that drives `DevicePolicyManager`
 * towards the returned state idempotently. Nothing else in the app may call
 * `setPackagesSuspended` directly.
 */
object PolicyEngine {

    fun evaluate(
        policy: Policy,
        installedPackages: Set<PackageName>,
        usage: UsageSnapshot,
        grants: List<Grant> = emptyList(),
        deviceState: DeviceState = DeviceState.IDLE,
        now: Instant,
    ): Enforcement {
        val zone = policy.timeZone
        val nowZdt = now.atZone(zone)
        val day = PolicyClock.policyDay(now, zone, policy.dayBoundaryHour)
        val dayEnd = PolicyClock.dayEnd(day, zone, policy.dayBoundaryHour)
        val weekday = day.dayOfWeek

        val liveGrants = grants.filter { it.isActiveAt(now) }
        fun bonusFor(target: GrantTarget): Duration =
            liveGrants.filter { it.target == target }.fold(Duration.ZERO) { acc, g -> acc + g.amount }

        val deviceBudget = statusOf(
            allowed = policy.deviceBudget.forDay(weekday),
            used = usage.deviceTotal,
            bonus = bonusFor(GrantTarget.Device),
            thresholds = policy.warningThresholds,
        )
        // A category's usage is the sum of its members' time. An app in two
        // categories spends from both — see Category's documentation. Apps that are
        // exempt from limits do not consume a category's allowance either.
        val categoryBudgets = policy.categories.associate { category ->
            val used = category.packages
                .filterNot { policy.isSafetyExempt(it) }
                .fold(Duration.ZERO) { acc, pkg -> acc + usage.forPackage(pkg) }
            category.id to statusOf(
                allowed = category.budget.forDay(weekday),
                used = used,
                bonus = bonusFor(GrantTarget.Category(category.id)),
                thresholds = policy.warningThresholds,
            )
        }

        val activeSchedules = policy.schedules.filter { ScheduleResolver.isActive(it, nowZdt) }

        val suspend = LinkedHashMap<PackageName, Reason>()
        val hide = LinkedHashSet<PackageName>()
        val appBudgets = LinkedHashMap<PackageName, BudgetStatus>()

        for (pkg in installedPackages) {
            val rule = policy.ruleFor(pkg)

            // Computed even for exempt apps: the teen's dashboard shows usage for
            // everything, it just does not enforce on all of it.
            if (rule is AppRule.Budgeted) {
                appBudgets[pkg] = statusOf(
                    allowed = rule.budget.forDay(weekday),
                    used = usage.forPackage(pkg),
                    bonus = bonusFor(GrantTarget.App(pkg)),
                    thresholds = policy.warningThresholds,
                )
            }

            // Hard invariant, checked before anything else can restrict the app.
            if (policy.isSafetyExempt(pkg)) continue

            val reasons = buildList {
                if (rule is AppRule.Blocked) {
                    add(Reason.ManuallyBlocked)
                    if (rule.hidden) hide += pkg
                }
                for (schedule in activeSchedules) {
                    if (pkg in schedule.allowedPackages) continue
                    val endsAt = ScheduleResolver.endOfActiveWindow(schedule, nowZdt) ?: continue
                    add(Reason.ScheduleActive(schedule.id, schedule.name, endsAt))
                }
                if (deviceBudget.isExhausted) add(Reason.DeviceBudgetExhausted(dayEnd))
                if (appBudgets[pkg]?.isExhausted == true) add(Reason.AppBudgetExhausted(pkg, dayEnd))

                // Every category the app belongs to is enforced independently.
                for (category in policy.categoriesFor(pkg)) {
                    if (categoryBudgets[category.id]?.isExhausted != true) continue
                    add(Reason.CategoryBudgetExhausted(category.id, category.name, dayEnd))
                }
            }

            mostRestrictive(reasons)?.let { suspend[pkg] = it }
        }

        // A call or a live navigation session postpones suspension rather than
        // cancelling it. The reconciler re-evaluates the moment it clears.
        val deferring = deviceState.suspensionDeferred

        return Enforcement(
            policyVersion = policy.version,
            suspend = if (deferring) emptyMap() else suspend,
            hide = hide,
            deferred = if (deferring) suspend else emptyMap(),
            deviceBudget = deviceBudget,
            appBudgets = appBudgets,
            categoryBudgets = categoryBudgets,
            activeSchedules = activeSchedules,
            nextChangeAt = nextChangeAt(
                policy = policy,
                now = now,
                nowZdt = nowZdt,
                dayEnd = dayEnd,
                liveGrants = liveGrants,
                deviceBudget = deviceBudget,
                appBudgets = appBudgets,
                categoryBudgets = categoryBudgets,
                deviceState = deviceState,
            ),
        )
    }

    /**
     * Of several applicable reasons, the one that lifts last — which is the honest
     * answer to "when can I use this again". An indefinite reason (a parent block)
     * always wins.
     */
    private fun mostRestrictive(reasons: List<Reason>): Reason? =
        reasons.maxWithOrNull(compareBy(nullsLast()) { it.liftsAt })

    private fun statusOf(
        allowed: Duration,
        used: Duration,
        bonus: Duration,
        thresholds: WarningThresholds,
    ): BudgetStatus {
        if (allowed.isInfinite()) {
            return BudgetStatus(allowed, used, bonus, Duration.INFINITE, BudgetLevel.NORMAL)
        }
        val remaining = (allowed + bonus - used).coerceAtLeast(Duration.ZERO)
        val level = when {
            remaining == Duration.ZERO -> BudgetLevel.EXHAUSTED
            remaining <= thresholds.grace -> BudgetLevel.GRACE
            remaining <= thresholds.notice -> BudgetLevel.NOTICE
            else -> BudgetLevel.NORMAL
        }
        return BudgetStatus(allowed, used, bonus, remaining, level)
    }

    /**
     * The earliest moment enforcement would change without anyone touching the device.
     *
     * Includes the projected instants at which the foreground app crosses the notice
     * and grace thresholds, so the warning ladder is driven by exact alarms rather
     * than by a polling loop that costs battery and still arrives late.
     */
    private fun nextChangeAt(
        policy: Policy,
        now: Instant,
        nowZdt: java.time.ZonedDateTime,
        dayEnd: Instant,
        liveGrants: List<Grant>,
        deviceBudget: BudgetStatus,
        appBudgets: Map<PackageName, BudgetStatus>,
        categoryBudgets: Map<CategoryId, BudgetStatus>,
        deviceState: DeviceState,
    ): Instant? {
        val candidates = mutableListOf(dayEnd)

        ScheduleResolver.nextEdgeAfter(policy.schedules, nowZdt)?.let(candidates::add)
        liveGrants.forEach { candidates += it.expiresAt }

        val foreground = deviceState.foregroundPackage
        if (foreground != null && !policy.isSafetyExempt(foreground)) {
            // Whichever of the budgets covering this app runs out first is the one
            // that decides when the next warning fires.
            val covering = buildList {
                add(deviceBudget)
                appBudgets[foreground]?.let(::add)
                policy.categoriesFor(foreground).forEach { category ->
                    categoryBudgets[category.id]?.let(::add)
                }
            }
            val binding = covering
                .filterNot { it.isUnlimited || it.isExhausted }
                .minByOrNull { it.remaining }
            if (binding != null) {
                val remaining = binding.remaining
                candidates += now.plusMillis(remaining.inWholeMilliseconds)
                val thresholds = policy.warningThresholds
                for (threshold in listOf(thresholds.notice, thresholds.grace)) {
                    if (remaining > threshold) {
                        candidates += now.plusMillis((remaining - threshold).inWholeMilliseconds)
                    }
                }
            }
        }

        return candidates.filter { it.isAfter(now) }.minOrNull()
    }
}
