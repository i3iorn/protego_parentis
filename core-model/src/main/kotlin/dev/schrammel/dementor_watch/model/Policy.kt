package dev.schrammel.dementor_watch.model

import java.time.Instant
import java.time.ZoneId

/** What the family has decided about a single app. */
sealed interface AppRule {

    /** No app-specific restriction. Still subject to device budget and schedules. */
    data object Allowed : AppRule

    /** Has its own daily allowance on top of the device budget. Tightest wins. */
    data class Budgeted(val budget: WeeklyBudget) : AppRule

    /**
     * Off-limits indefinitely. [hidden] chooses between `setApplicationHidden`
     * (icon disappears) and `setPackagesSuspended` (icon greys out, data intact).
     * Suspend is the default; hiding is for apps a parent has decided are simply
     * not on this phone.
     */
    data class Blocked(val hidden: Boolean = false) : AppRule

    /**
     * Exempt from every rule, always. Distinct from [Allowed]: an always-allowed
     * app survives bedtime, device-budget exhaustion, and schedule blocks.
     */
    data object AlwaysAllowed : AppRule
}

/** How new app installs are handled — see AC-3. */
enum class InstallPolicy {
    /** Installs are allowed; the parent is notified when a new package appears. */
    NOTIFY,

    /** Installs are blocked outright; the teen requests them through the app. */
    GATED,
}

/**
 * The complete rule set for one child's device.
 *
 * This is the on-device source of truth for enforcement. The server owns the
 * canonical copy and pushes updates, but evaluation never requires connectivity.
 */
data class Policy(
    val version: Long,
    val childId: ChildId,
    val timeZone: ZoneId,
    val deviceBudget: WeeklyBudget = WeeklyBudget.UNLIMITED,
    val appRules: Map<PackageName, AppRule> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val schedules: List<ScheduleBlock> = emptyList(),
    /** Rules that apply only in certain circumstances. See [ContextRule]. */
    val contextRules: List<ContextRule> = emptyList(),
    val safetyAllowlist: Set<PackageName> = emptySet(),
    /** A filtering resolver the whole device points at, or null for none (WF-1). */
    val dnsFilter: DnsFilter? = null,
    /** What managed browsers are told to do (WF-2). */
    val browserPolicy: BrowserPolicy = BrowserPolicy.OFF,
    val installPolicy: InstallPolicy = InstallPolicy.NOTIFY,
    /**
     * When a parent-opened installation window closes, under [InstallPolicy.GATED].
     *
     * Without this, gating installs is a door that locks from the outside: nobody
     * can ever add an app again, including the parent who turned it on, without
     * switching the whole policy back. A window expires on its own, so forgetting
     * to close it is not the same as never having gated installs at all.
     */
    val installWindowUntil: Instant? = null,
    /**
     * Whether to lock the device against tampering (TR-1): no factory reset, no
     * clock changes, no safe boot, and the app cannot be uninstalled.
     *
     * Separable from enforcement on purpose. These restrictions are the correct
     * end state for a child's device, but they also make the device very hard to
     * recover, so turning them on is a deliberate act rather than a side effect of
     * provisioning. Enforcement of limits works perfectly well without them.
     */
    val tamperProtection: Boolean = true,
    /** Hour of the local day at which usage counters reset. See [Defaults.DAY_BOUNDARY_HOUR]. */
    val dayBoundaryHour: Int = Defaults.DAY_BOUNDARY_HOUR,
    /** How far in advance the teenager is warned. See [WarningThresholds]. */
    val warningThresholds: WarningThresholds = WarningThresholds.STANDARD,
) {
    init {
        require(dayBoundaryHour in 0..23) { "dayBoundaryHour must be 0..23, was $dayBoundaryHour" }
        require(schedules.map { it.id }.toSet().size == schedules.size) { "duplicate schedule ids" }
        require(categories.map { it.id }.toSet().size == categories.size) { "duplicate category ids" }
    }

    fun ruleFor(pkg: PackageName): AppRule = appRules[pkg] ?: AppRule.Allowed

    /**
     * True when [pkg] must never be restricted by anything. Enforced as an invariant
     * in the engine rather than left to parents to configure correctly.
     */
    fun isSafetyExempt(pkg: PackageName): Boolean =
        pkg in safetyAllowlist || ruleFor(pkg) is AppRule.AlwaysAllowed
}
