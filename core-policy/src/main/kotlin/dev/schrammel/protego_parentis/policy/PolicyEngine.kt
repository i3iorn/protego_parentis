package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.AppRule
import dev.schrammel.protego_parentis.model.BrowserRestrictions
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.BudgetStatus
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.DeviceContext
import dev.schrammel.protego_parentis.model.DeviceState
import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.Grant
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.RestrictionValue
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.model.WarningThresholds
import dev.schrammel.protego_parentis.model.WeeklyBudget
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
        context: DeviceContext = DeviceContext.UNKNOWN,
        /**
         * Which installed packages can open a web page.
         *
         * Supplied rather than derived: working out what counts as a browser
         * means asking the package manager, which this module deliberately cannot
         * do. See WF-2.
         */
        browsers: Set<PackageName> = emptySet(),
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

        // Contextual rules are settled before budgets, because one of them can
        // replace the device allowance outright.
        val hierarchyForRules = CategoryHierarchy.resolve(policy.categories)
        val contextOutcome = ContextRules.evaluate(
            rules = policy.contextRules,
            context = context,
            hierarchy = hierarchyForRules,
            installedPackages = installedPackages,
            now = nowZdt,
            // Net of bonus time, matching what the dedicated device-budget
            // comparison below treats as "used" — a Condition.AllowanceSpent
            // rule and Policy.deviceBudget must agree on that arithmetic.
            deviceUsage = (usage.deviceTotal - bonusFor(GrantTarget.Device)).coerceAtLeast(Duration.ZERO),
        )
        // #105: no fallback to policy.deviceBudget any more — the migrated
        // daily limit is a Condition.Always/SetDeviceBudget rule now, so an
        // unlimited default here means only "no rule set one", never "the
        // unified format still needs the old field to answer this".
        val effectiveDeviceBudget = contextOutcome.deviceBudget ?: WeeklyBudget.UNLIMITED

        val deviceBudget = statusOf(
            allowed = effectiveDeviceBudget.forDay(weekday),
            used = usage.deviceTotal,
            bonus = bonusFor(GrantTarget.Device),
            thresholds = policy.warningThresholds,
        )
        val hierarchy = hierarchyForRules

        // A category's usage is the sum of its members' time. An app in two
        // categories spends from both — see Category's documentation. Apps that are
        // exempt from limits do not consume a category's allowance either.
        val categoryBudgets = policy.categories.associate { category ->
            val used = hierarchy.packagesIn(category.id)
                .filterNot { policy.isSafetyExempt(it) }
                .fold(Duration.ZERO) { acc, pkg -> acc + usage.forPackage(pkg) }
            category.id to statusOf(
                allowed = category.budget.forDay(weekday),
                used = used,
                bonus = bonusFor(GrantTarget.Category(category.id)),
                thresholds = policy.warningThresholds,
            )
        }

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

            // A contextual allow works like a temporary safety exemption: nothing
            // else may restrict the app while the rule applies.
            if (pkg in contextOutcome.allowed) continue

            val reasons = buildList {
                if (rule is AppRule.Blocked) {
                    add(Reason.ManuallyBlocked)
                    if (rule.hidden) hide += pkg
                }
                // A browser that ignores managed configuration would be a way
                // straight round the filter, so with filtering on it is paused.
                if (unmanageableBrowser(policy, browsers, pkg)) {
                    add(Reason.UnmanagedBrowser)
                }
                contextOutcome.blocked[pkg]?.let { rule ->
                    // #103: derived from the rule's own condition rather than
                    // assumed null. A window-based rule can say when it lifts;
                    // a Wi-Fi one still cannot, and null still means that.
                    add(
                        Reason.ContextRuleActive(
                            ruleId = rule.id,
                            ruleName = rule.name,
                            liftsAt = ContextRules.liftsAt(rule.condition, nowZdt),
                        ),
                    )
                }
                if (deviceBudget.isExhausted) add(Reason.DeviceBudgetExhausted(dayEnd))
                if (appBudgets[pkg]?.isExhausted == true) add(Reason.AppBudgetExhausted(pkg, dayEnd))

                // Every category the app belongs to is enforced independently,
                // including the ones it belongs to only through nesting.
                for (category in policy.categories) {
                    if (pkg !in hierarchy.packagesIn(category.id)) continue
                    if (categoryBudgets[category.id]?.isExhausted != true) continue
                    add(Reason.CategoryBudgetExhausted(category.id, category.name, dayEnd))
                }
            }

            mostRestrictive(reasons)?.let { suspend[pkg] = it }
        }

        // A live navigation session postpones that app's suspension rather than
        // cancelling it. The reconciler re-evaluates the moment it clears. Only
        // the navigating app is spared: holding back the whole map would turn
        // "start navigating" into a way round every other rule at once.
        val deferring = deviceState.suspensionDeferredFor?.takeIf(suspend::containsKey)

        // An emergency lifts everything, and lifts it rather than deferring it: a
        // child who has pressed the SOS button must not find the phone closing
        // back down while they are still in whatever made them press it. Hidden
        // apps come back too — an icon that is not there is no use to anybody.
        val emergency = deviceState.emergency

        return Enforcement(
            policyVersion = policy.version,
            suspend = when {
                emergency -> emptyMap()
                deferring != null -> suspend - deferring
                else -> suspend
            },
            hide = if (emergency) emptySet() else hide,
            deferred = if (deferring != null && !emergency) {
                mapOf(deferring to suspend.getValue(deferring))
            } else {
                emptyMap()
            },
            deviceBudget = deviceBudget,
            appBudgets = appBudgets,
            categoryBudgets = categoryBudgets,
            activeContextRules = contextOutcome.activeRules,
            // Filtering stays on in an emergency. It blocks sites, not calls or
            // maps, so lifting it would buy nothing and cost the one protection
            // that has no bearing on getting help.
            dnsHost = policy.dnsFilter?.host,
            emergency = emergency,
            appRestrictions = browserRestrictions(policy),
            // Never anything on the safety list, and never in an emergency: a
            // phone that cannot reach the network is a phone that cannot call for
            // help through anything but the dialer.
            networkBlocked = if (emergency) {
                emptySet()
            } else {
                policy.networkBlockedPackages.filterNot(policy::isSafetyExempt).toSet()
            },
            installsBlocked = installsBlocked(policy, now),
            nextChangeAt = nextChangeAt(
                policy = policy,
                now = now,
                nowZdt = nowZdt,
                dayEnd = dayEnd,
                liveGrants = liveGrants,
                deviceBudget = deviceBudget,
                appBudgets = appBudgets,
                categoryBudgets = categoryBudgets,
                hierarchy = hierarchy,
                deviceState = deviceState,
            ),
        )
    }

    /**
     * Whether [pkg] is a browser this app has no way to filter (WF-2).
     *
     * Only while the family has actually asked for browser policy: suspending
     * every alternative browser on a device with no filtering configured would be
     * a restriction nobody chose.
     */
    private fun unmanageableBrowser(
        policy: Policy,
        browsers: Set<PackageName>,
        pkg: PackageName,
    ): Boolean {
        if (!policy.browserPolicy.suspendUnmanagedBrowsers) return false
        if (pkg !in browsers) return false
        if (pkg.value in MANAGED_BROWSERS) return false

        // With no manageable browser on the phone, suspending the rest does not
        // leave a filtered web — it leaves no web. The switch promises the first,
        // so it does nothing rather than quietly delivering the second.
        return browsers.any { it.value in MANAGED_BROWSERS }
    }

    /**
     * The managed configuration to push, keyed by package.
     *
     * Empty when the family has set no browser policy, which is not the same as a
     * policy of "allow everything": pushing an empty configuration would clear
     * settings an administrator or the family had made elsewhere.
     */
    private fun browserRestrictions(
        policy: Policy,
    ): Map<PackageName, Map<String, RestrictionValue>> {
        val chrome = BrowserRestrictions.forChrome(policy.browserPolicy)
        if (chrome.isEmpty()) return emptyMap()
        return mapOf(PackageName(BrowserRestrictions.CHROME) to chrome)
    }

    /**
     * Browsers that honour enterprise managed configuration.
     *
     * A short, explicit list rather than a guess. Adding one means checking that
     * it really reads these policies — being wrong here means a browser that looks
     * filtered and is not, which is worse than one that is visibly paused.
     */
    private val MANAGED_BROWSERS = setOf(BrowserRestrictions.CHROME)

    /**
     * Gated installs, unless a parent has the window open right now.
     *
     * A window in the past is the same as no window: it is left in the policy so
     * the audit log and the parent's screen can both say when it closed, rather
     * than having it vanish and look as though it was never opened.
     */
    private fun installsBlocked(policy: Policy, now: Instant): Boolean {
        if (policy.installPolicy != InstallPolicy.GATED) return false
        val until = policy.installWindowUntil ?: return true
        return !now.isBefore(until)
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
        hierarchy: ResolvedHierarchy,
        deviceState: DeviceState,
    ): Instant? {
        val candidates = mutableListOf(dayEnd)

        // #103: a rule carrying a time window changes on its own at the window's
        // edges, exactly as a schedule does. Without this the block would sit
        // there until something unrelated woke enforcement, which on a quiet
        // phone can be hours after the window ended. Disabled rules are skipped
        // — waking for a rule that cannot fire is battery spent on nothing.
        val ruleWindows = policy.contextRules
            .filter { it.enabled }
            .flatMap { ContextRules.windowsIn(it.condition) }
        ScheduleResolver.nextWindowEdgeAfter(ruleWindows, nowZdt)?.let(candidates::add)
        liveGrants.forEach { candidates += it.expiresAt }
        // Otherwise an installation window would stay open until the next tick for
        // some unrelated reason, which could be five minutes or the 4am reset.
        policy.installWindowUntil?.let(candidates::add)

        val foreground = deviceState.foregroundPackage
        if (foreground != null && !policy.isSafetyExempt(foreground)) {
            // Whichever of the budgets covering this app runs out first is the one
            // that decides when the next warning fires.
            val covering = buildList {
                add(deviceBudget)
                appBudgets[foreground]?.let(::add)
                policy.categories
                    .filter { foreground in hierarchy.packagesIn(it.id) }
                    .forEach { category -> categoryBudgets[category.id]?.let(::add) }
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
