package dev.schrammel.dementor_watch.policy.reconcile

import dev.schrammel.dementor_watch.model.Enforcement
import dev.schrammel.dementor_watch.model.InstallPolicy
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Policy

/**
 * Drives the device towards a desired [Enforcement].
 *
 * This is the *only* thing in the app permitted to call `setPackagesSuspended`.
 * Two features each suspending packages independently is how you end up with one
 * of them silently undoing the other, so all of it funnels through here.
 *
 * Idempotent by construction: it diffs desired state against what the OS reports
 * and acts only on the difference, so running it every fifteen minutes on an
 * unchanged policy costs nothing and changes nothing.
 */
class PolicyReconciler(
    private val port: DevicePolicyPort,
    private val selfPackage: PackageName,
) {

    fun reconcile(policy: Policy, enforcement: Enforcement): ReconcileResult {
        if (!port.isDeviceOwner()) {
            // Monitor-only mode, or the app lost ownership. Enforcement is not
            // available, but usage collection and reporting continue.
            return ReconcileResult.skipped("not device owner", enforcement.policyVersion)
        }

        val violations = mutableListOf<String>()

        // Defence in depth. The engine already honours the safety allowlist; if a
        // protected package ever reaches this point, refuse it here too and shout.
        fun protect(packages: Set<PackageName>, action: String): Set<PackageName> {
            val (blocked, allowed) = packages.partition {
                it == selfPackage || policy.isSafetyExempt(it)
            }
            blocked.forEach { violations += "refused to $action protected package $it" }
            return allowed.toSet()
        }

        val desiredSuspend = protect(enforcement.suspend.keys, "suspend")
        val desiredHide = protect(enforcement.hide, "hide")

        val failed = mutableSetOf<PackageName>()

        // --- suspensions ---
        val actualSuspended = port.currentlySuspended()
        val toSuspend = desiredSuspend - actualSuspended
        val toUnsuspend = actualSuspended - desiredSuspend

        if (toSuspend.isNotEmpty()) failed += port.setSuspended(toSuspend, suspended = true)
        if (toUnsuspend.isNotEmpty()) failed += port.setSuspended(toUnsuspend, suspended = false)

        // --- hidden apps ---
        val actualHidden = port.currentlyHidden()
        val toHide = desiredHide - actualHidden
        val toUnhide = actualHidden - desiredHide

        toHide.forEach { if (!port.setHidden(it, hidden = true)) failed += it }
        toUnhide.forEach { if (!port.setHidden(it, hidden = false)) failed += it }

        // --- tamper restrictions ---
        val desiredRestrictions = buildSet {
            // Only when the family has explicitly asked to lock the device down.
            // Without this, provisioning alone would make the device unresettable.
            if (policy.tamperProtection) addAll(TamperRestrictions.BASELINE)
            if (policy.installPolicy == InstallPolicy.GATED) {
                add(TamperRestrictions.NO_INSTALL_APPS)
            }
        }
        val actualRestrictions = port.currentUserRestrictions()
        val restrictionsToAdd = desiredRestrictions - actualRestrictions
        // Only ever clear restrictions this app owns; anything else on the device
        // was put there by someone else and is none of our business.
        val restrictionsToClear =
            (actualRestrictions intersect TamperRestrictions.MANAGED) - desiredRestrictions

        restrictionsToAdd.forEach(port::addUserRestriction)
        restrictionsToClear.forEach(port::clearUserRestriction)

        // --- self-protection ---
        // Tied to the same switch: if the device is not locked down, the app must
        // remain removable, or a test install becomes permanent.
        port.setUninstallBlocked(selfPackage, blocked = policy.tamperProtection)

        return ReconcileResult(
            applied = true,
            policyVersion = enforcement.policyVersion,
            suspended = toSuspend - failed,
            unsuspended = toUnsuspend - failed,
            hidden = toHide - failed,
            unhidden = toUnhide - failed,
            failed = failed,
            restrictionsAdded = restrictionsToAdd,
            restrictionsCleared = restrictionsToClear,
            invariantViolations = violations,
        )
    }
}
