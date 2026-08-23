package dev.schrammel.dementor_watch.enforcement

import android.content.Context
import android.util.Log
import dev.schrammel.dementor_watch.diagnostics.DiagnosticEvent
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import dev.schrammel.dementor_watch.dpc.AndroidDevicePolicyPort
import dev.schrammel.dementor_watch.context.ContextProvider
import dev.schrammel.dementor_watch.model.DeviceContext
import dev.schrammel.dementor_watch.model.DeviceState
import dev.schrammel.dementor_watch.rules.ContextRuleStore
import dev.schrammel.dementor_watch.model.Enforcement
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Policy
import dev.schrammel.dementor_watch.model.UsageSnapshot
import dev.schrammel.dementor_watch.policy.PolicyClock
import dev.schrammel.dementor_watch.policy.PolicyEngine
import dev.schrammel.dementor_watch.policy.reconcile.DeviceReleaser
import dev.schrammel.dementor_watch.policy.reconcile.PolicyReconciler
import dev.schrammel.dementor_watch.policy.reconcile.ReleaseResult
import dev.schrammel.dementor_watch.policy.reconcile.ReconcileResult
import dev.schrammel.dementor_watch.rules.GrantStore
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.usage.CollectedUsage
import dev.schrammel.dementor_watch.usage.InstalledApps
import dev.schrammel.dementor_watch.usage.UsageAccess
import dev.schrammel.dementor_watch.usage.UsageCollector
import dev.schrammel.dementor_watch.usage.UsageHistoryRecorder
import dev.schrammel.dementor_watch.usage.UsageHistoryStore
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
 */
class EnforcementCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val collector = UsageCollector(appContext)
    private val store = LocalPolicyStore(appContext)
    private val grants = GrantStore(appContext)
    private val history = UsageHistoryStore(appContext)
    private val recorder = UsageHistoryRecorder(collector, history)
    private val installedApps = InstalledApps(appContext)
    private val contextProvider = ContextProvider(appContext)
    private val contextRules = ContextRuleStore(appContext)
    private val port = AndroidDevicePolicyPort(appContext)
    private val reconciler = PolicyReconciler(port, PackageName(appContext.packageName))

    fun hasUsageAccess(): Boolean = UsageAccess.isGranted(appContext)

    /** Closed days from history plus today, computed live. */
    fun historyIncludingToday(now: Instant = Instant.now()): List<UsageSnapshot> {
        if (!hasUsageAccess()) return emptyList()
        val policy = store.load()
        recorder.recordClosedDays(policy, now)
        return history.all() + collector.collect(policy, now).snapshot
    }

    fun isDeviceOwner(): Boolean = port.isDeviceOwner()

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

        val enforcement = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = installed,
            usage = usage.snapshot,
            grants = grants.active(now),
            deviceState = DeviceState(foregroundPackage = usage.foregroundPackage),
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

        val reconcile = if (port.isDeviceOwner()) {
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
