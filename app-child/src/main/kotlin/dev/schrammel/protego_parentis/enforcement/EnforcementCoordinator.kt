package dev.schrammel.protego_parentis.enforcement

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.context.ContextProvider
import dev.schrammel.protego_parentis.context.NavigationWatcher
import dev.schrammel.protego_parentis.glucose.GlucoseSource
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.dpc.AndroidDevicePolicyPort
import dev.schrammel.protego_parentis.model.DeviceContext
import dev.schrammel.protego_parentis.model.DeviceState
import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.policy.PolicyClock
import dev.schrammel.protego_parentis.policy.PolicyEngine
import dev.schrammel.protego_parentis.policy.reconcile.DeviceLocker
import dev.schrammel.protego_parentis.policy.reconcile.DevicePolicyPort
import dev.schrammel.protego_parentis.policy.reconcile.DeviceReleaser
import dev.schrammel.protego_parentis.policy.reconcile.LockResult
import dev.schrammel.protego_parentis.policy.reconcile.PolicyReconciler
import dev.schrammel.protego_parentis.policy.reconcile.ReconcileResult
import dev.schrammel.protego_parentis.policy.reconcile.ReleaseResult
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.GrantStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.SosStore
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.usage.CollectedUsage
import dev.schrammel.protego_parentis.usage.InstalledApps
import dev.schrammel.protego_parentis.usage.UsageAccess
import dev.schrammel.protego_parentis.usage.UsageCollector
import dev.schrammel.protego_parentis.usage.UsageHistoryRecorder
import dev.schrammel.protego_parentis.usage.UsageHistoryStore
import java.time.Instant

/** One complete evaluation, ready for the UI or the notification. */
data class EnforcementSnapshot(
    val policy: Policy,
    val usage: CollectedUsage,
    val enforcement: Enforcement,
    /** Null when the device is not provisioned — nothing was applied. */
    val reconcile: ReconcileResult?,
    val evaluatedAt: Instant,
) {
    /** True when rules are advisory only, because we are not device owner. */
    val monitorOnly: Boolean get() = reconcile == null
}

/**
 * Ties the pieces together: read the rules, measure usage, ask the engine, and —
 * if this device is actually provisioned — apply the answer.
 *
 * Everything interesting has already been decided by [PolicyEngine] and
 * [PolicyReconciler]. This class exists to sequence them and to be the one place
 * the UI and the service both call, so they can never disagree about the state.
 *
 * Every collaborator below is a constructor parameter with a production
 * default, not a hardcoded `new`, so [evaluate]'s sequencing — in particular
 * the branches gated on [DevicePolicyPort.isDeviceOwner], which nothing on a
 * test device can make true — can be exercised against a fake [DevicePolicyPort]
 * instead of only ever running for real on a provisioned phone.
 */
class EnforcementCoordinator(
    context: Context,
    private val port: DevicePolicyPort = AndroidDevicePolicyPort(context.applicationContext),
    private val collector: UsageCollector = UsageCollector(context.applicationContext),
    private val store: LocalPolicyStore = LocalPolicyStore(context.applicationContext),
    private val grants: GrantStore = GrantStore(context.applicationContext),
    private val history: UsageHistoryStore = UsageHistoryStore(context.applicationContext),
    private val installedApps: InstalledApps = InstalledApps(context.applicationContext),
    private val contextProvider: ContextProvider = ContextProvider(context.applicationContext),
    private val contextRules: ContextRuleStore = ContextRuleStore(context.applicationContext),
    private val recorder: UsageHistoryRecorder = UsageHistoryRecorder(collector, history),
    private val hygiene: PlatformHygiene = PlatformHygiene(context.applicationContext, port),
    private val sosStore: SosStore = AppRegistry.childStores.sos,
) {

    private val appContext = context.applicationContext
    private val reconciler = PolicyReconciler(port, PackageName(appContext.packageName))

    /**
     * Runs each migration once, before anything here ever reads a Policy — see
     * each method's own doc comment for why this is safe to call rather than
     * gating it, and why the three are separate calls.
     *
     * Lazy rather than in `init`: screens construct this coordinator on the main
     * thread (HomeActivity, WeekActivity, DeviceControlActivity), and these are
     * preference-file reads and writes. Touched only from [historyIncludingToday]
     * and [evaluate] — the two paths that read [store] — both of which run on
     * Background's executor, so the cost lands where the rest of the disk work
     * already lives.
     */
    private val migrationsApplied: Boolean by lazy {
        store.migrateToUnifiedRules()
        store.migrateAppRulesToUnifiedRules()
        // Recovers a family whose rules were set while PolicyReplication's
        // defaultChildId() bug was live — see backfillIfNeeded's own doc
        // comment. Same shape as the two migrations above: a flag-gated
        // one-time correction, safe to call every time this runs.
        PolicyReplication(appContext).backfillIfNeeded()
        true
    }

    fun hasUsageAccess(): Boolean = UsageAccess.isGranted(appContext)

    /** Closed days from history plus today, computed live. */
    fun historyIncludingToday(now: Instant = Instant.now()): List<UsageSnapshot> {
        check(migrationsApplied)
        if (!hasUsageAccess()) return emptyList()
        val policy = store.load()
        recorder.recordClosedDays(policy, now)
        return history.all() + collector.collect(policy, now).snapshot
    }

    fun isDeviceOwner(): Boolean = port.isDeviceOwner()

    /** Locks the screen now, on a parent's instruction. */
    fun lockDevice(): LockResult = DeviceLocker(port).lock()

    /** See [PolicyReconciler.forgetAppliedRestrictions]. */
    fun forgetAppliedRestrictions() = reconciler.forgetAppliedRestrictions()

    /**
     * Whether the last pass could not point the device at its filtering resolver.
     *
     * Recorded rather than recomputed, because the rules screen needs to show it
     * and asking for a fresh evaluation from a screen would mean opening that
     * screen drove `DevicePolicyManager` — a read with side effects.
     */
    fun dnsFailedLastPass(): Boolean = EnforcementStatus(appContext).dnsFailed

    /**
     * Hands the device back: lifts every restriction, then relinquishes ownership.
     * See [DeviceReleaser] for why the order matters.
     */
    fun releaseDevice(): ReleaseResult =
        DeviceReleaser(port, PackageName(appContext.packageName)).release()

    /**
     * Returns null when usage access has not been granted — without it every
     * measurement would read zero, and silently reporting "no screen time today"
     * would be worse than reporting nothing.
     */
    fun evaluate(now: Instant = Instant.now()): EnforcementSnapshot? {
        check(migrationsApplied)
        if (!hasUsageAccess()) return null

        val policy = store.load()

        // Almost always a no-op; acts only when the policy day has rolled over.
        // History is a reporting nicety — never let it block enforcement.
        runCatching { recorder.recordClosedDays(policy, now) }
            .onSuccess { days ->
                if (days.isNotEmpty()) {
                    Diagnostics.record(
                        appContext,
                        DiagnosticEvent.HISTORY_RECORDED,
                        mapOf("days" to days.size.toLong()),
                    )
                }
            }
            .onFailure {
                Log.w(TAG, "Could not record history", it)
                Diagnostics.record(appContext, DiagnosticEvent.HISTORY_FAILED)
            }

        // UsageStatsManager can throw on some OEM builds. A failed read must not
        // take enforcement with it: fall back to an empty day, which enforces
        // nothing rather than enforcing something wrong.
        val usage = runCatching { collector.collect(policy, now) }
            .onFailure {
                Log.w(TAG, "Usage read failed; treating today as unmeasured", it)
                Diagnostics.record(appContext, DiagnosticEvent.USAGE_READ_FAILED)
            }
            .getOrElse {
                CollectedUsage(
                    snapshot = UsageSnapshot.empty(
                        PolicyClock.policyDay(now, policy.timeZone, policy.dayBoundaryHour),
                    ),
                    foregroundPackage = null,
                )
            }
        val installed = installedApps.launchable().map { it.pkg }.toSet()

        // Resolved here and nowhere else: the engine receives place ids, never a
        // position. A failure leaves the context unknown, which makes contextual
        // rules simply not apply.
        val surroundings = runCatching { contextProvider.current(contextRules.places) }
            .getOrDefault(DeviceContext.UNKNOWN)
            // The newest stored reading, never a fetch: see GlucoseSource.
            .copy(glucose = runCatching { GlucoseSource.latest(appContext) }.getOrNull())

        val enforcement = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = installed,
            usage = usage.snapshot,
            grants = grants.active(now),
            deviceState = DeviceState(
                foregroundPackage = usage.foregroundPackage,
                // Null unless notification access was granted and a route is
                // actually running, which is the direction that enforces the
                // rules as written rather than quietly widening them.
                navigatingPackage = NavigationWatcher.current(),
                // An emergency lifts everything, so it is read on every pass
                // rather than pushed — a restart must not quietly end one.
                emergency = sosStore.isActive,
            ),
            context = surroundings,
            // Only read when browser policy is on; on a phone with no filtering
            // configured this is an empty set and costs a map lookup.
            browsers = if (policy.browserPolicy.suspendUnmanagedBrowsers) {
                runCatching { installedApps.browsers() }.getOrDefault(emptySet())
            } else {
                emptySet()
            },
            now = now,
        )

        // The two platform-hygiene assertions (network logging, support
        // messages) live behind [PlatformHygiene]: they are reads with side
        // effects on DevicePolicyPort, which is why they are not part of the
        // snapshot-building query itself.
        // Asked fresh every pass, never remembered across passes: ownership
        // can arrive while this service is already running — `adb shell dpm
        // set-device-owner` is run against an installed app, and
        // DementorDeviceAdminReceiver.onEnabled does not reach this instance —
        // and a cached `false` would leave the phone unenforced until the
        // process happened to restart. One binder call per tick.
        val owner = port.isDeviceOwner()
        hygiene.apply(owner)

        val reconcile = if (owner) {
            reconciler.reconcile(policy, enforcement).also { result ->
                EnforcementStatus(appContext).dnsFailed =
                    result.invariantViolations.any { it.contains("private DNS") }
                if (result.invariantViolations.isNotEmpty()) {
                    Diagnostics.record(
                        appContext,
                        DiagnosticEvent.INVARIANT_VIOLATION,
                        mapOf("count" to result.invariantViolations.size.toLong()),
                    )
                    // Never swallowed: this means a bug upstream, and on a real
                    // device it raises a parent-visible alert.
                    Log.e(TAG, "Invariant violations: ${result.invariantViolations}")
                }
            }
        } else {
            null
        }

        return EnforcementSnapshot(policy, usage, enforcement, reconcile, now)
    }

    private companion object {
        const val TAG = "DementorCoordinator"
    }
}
