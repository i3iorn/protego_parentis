package dev.schrammel.dementor_watch.enforcement

import android.content.Context
import android.util.Log
import dev.schrammel.dementor_watch.dpc.AndroidDevicePolicyPort
import dev.schrammel.dementor_watch.model.DeviceState
import dev.schrammel.dementor_watch.model.Enforcement
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Policy
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
    private val installedApps = InstalledApps(appContext)
    private val port = AndroidDevicePolicyPort(appContext)
    private val reconciler = PolicyReconciler(port, PackageName(appContext.packageName))

    fun hasUsageAccess(): Boolean = UsageAccess.isGranted(appContext)

    fun isDeviceOwner(): Boolean = port.isDeviceOwner()

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
        val usage = collector.collect(policy, now)
        val installed = installedApps.launchable().map { it.pkg }.toSet()

        val enforcement = PolicyEngine.evaluate(
            policy = policy,
            installedPackages = installed,
            usage = usage.snapshot,
            grants = grants.active(now),
            deviceState = DeviceState(foregroundPackage = usage.foregroundPackage),
            now = now,
        )

        val reconcile = if (port.isDeviceOwner()) {
            reconciler.reconcile(policy, enforcement).also { result ->
                if (result.invariantViolations.isNotEmpty()) {
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
