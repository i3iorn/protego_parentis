package dev.schrammel.protego_parentis.model

import java.time.Instant
import kotlin.time.Duration

/**
 * Why an app is unavailable.
 *
 * Every reason carries [liftsAt] so the block screen can always answer the only
 * question the teen actually has, which is "until when?". A null [liftsAt] means
 * indefinitely — only a parent can clear it.
 */
sealed interface Reason {
    val liftsAt: Instant?

    /** A parent blocked this app outright. */
    data object ManuallyBlocked : Reason {
        override val liftsAt: Instant? get() = null
    }

    /**
     * A browser that does not honour managed configuration, while web filtering
     * is on (WF-2).
     *
     * Distinct from [ManuallyBlocked] because the answer to "why?" is different
     * and actionable: this app cannot filter this browser, so it is paused rather
     * than left as a way round the filter. Using the managed browser lifts it.
     */
    data object UnmanagedBrowser : Reason {
        override val liftsAt: Instant? get() = null
    }

    /** The device-wide daily allowance is spent. */
    data class DeviceBudgetExhausted(override val liftsAt: Instant) : Reason

    /** This app's own daily allowance is spent. */
    data class AppBudgetExhausted(
        val pkg: PackageName,
        override val liftsAt: Instant,
    ) : Reason

    /**
     * A contextual rule applies right now — on this network, near this device, at
     * this place, or inside a time window.
     *
     * [liftsAt] was null by construction until #103, on the reasoning that
     * context is not a schedule and nobody can say when the phone will leave the
     * school Wi-Fi. That is still true of a Wi-Fi or place condition, and null
     * still means exactly that. It stopped being true of every rule once #101
     * gave conditions a time window: a rule that applies between 08:00 and 15:00
     * knows precisely when it stops, and a block screen that refused to say so
     * would be withholding something it had.
     *
     * So this is now derived from the rule's own condition rather than assumed
     * from which subsystem fired — see `ContextRules.liftsAt`. Null keeps its
     * meaning of "no end this app can promise", and the block screen already
     * says only which rule in that case.
     */
    data class ContextRuleActive(
        val ruleId: String,
        val ruleName: String,
        override val liftsAt: Instant?,
    ) : Reason

    /**
     * A category this app belongs to has spent its allowance. Carries the name so
     * the block screen can say "Games is used up" rather than something abstract.
     */
    data class CategoryBudgetExhausted(
        val categoryId: CategoryId,
        val categoryName: String,
        override val liftsAt: Instant,
    ) : Reason
}

/** Position on the warning ladder. */
enum class BudgetLevel {
    /** More than the notice threshold remaining. */
    NORMAL,

    /** Inside the notice threshold — a one-off notification has been earned. */
    NOTICE,

    /** Inside the grace threshold — persistent banner, one-tap request offered. */
    GRACE,

    /** Spent. Enforcement applies. */
    EXHAUSTED,
}

/**
 * A budget's standing right now. [allowed] excludes bonus time and [remaining]
 * includes it, so reports can tell a well-behaved week from a topped-up one.
 */
data class BudgetStatus(
    val allowed: Duration,
    val used: Duration,
    val bonus: Duration,
    val remaining: Duration,
    val level: BudgetLevel,
) {
    val isUnlimited: Boolean get() = allowed.isInfinite()
    val isExhausted: Boolean get() = level == BudgetLevel.EXHAUSTED
}

/**
 * The complete desired state of the device, computed from policy plus usage.
 *
 * This is what the reconciler drives `DevicePolicyManager` towards. It is a pure
 * value: the same inputs always produce the same enforcement, which is what makes
 * the whole engine testable without a device.
 */
data class Enforcement(
    val policyVersion: Long,
    val suspend: Map<PackageName, Reason>,
    val hide: Set<PackageName>,
    /**
     * The suspension withheld because that app is running a live navigation
     * session. At most one entry, and it applies as soon as the session ends —
     * see [DeviceState.suspensionDeferredFor].
     */
    val deferred: Map<PackageName, Reason>,
    val deviceBudget: BudgetStatus,
    val appBudgets: Map<PackageName, BudgetStatus>,
    val categoryBudgets: Map<CategoryId, BudgetStatus>,
    /** Contextual rules applying right now, for showing on the dashboard. */
    val activeContextRules: List<ContextRule> = emptyList(),
    /**
     * The filtering resolver the device should be pointed at, or null for none.
     *
     * Carried through the enforcement value rather than read from the policy by
     * the reconciler, so everything the device is driven towards arrives from one
     * place and can be asserted in one test.
     */
    val dnsHost: String? = null,
    /** Managed configuration per package, applied as-is by the reconciler (WF-2). */
    val appRestrictions: Map<PackageName, Map<String, RestrictionValue>> = emptyMap(),
    /**
     * Apps barred from the network entirely (WF-4).
     *
     * Separate from [suspend] because it is a different kind of restriction: the
     * app still opens, and still says what it wants to say about being offline.
     */
    val networkBlocked: Set<PackageName> = emptySet(),
    /** True while the child has an emergency raised, so screens can say so. */
    val emergency: Boolean = false,
    /**
     * Whether new installs are barred right now (AC-3).
     *
     * Decided here rather than read from the policy by the reconciler, because it
     * depends on the clock: a gated device with an open installation window is not
     * barring anything.
     */
    val installsBlocked: Boolean = false,
    /**
     * When this decision would next change on its own — the next day boundary, a
     * schedule edge, a grant expiry, or the projected moment the foreground app
     * exhausts its budget. The service sets an exact alarm for this instead of polling.
     */
    val nextChangeAt: Instant?,
) {
    fun reasonFor(pkg: PackageName): Reason? = suspend[pkg] ?: deferred[pkg]
}
