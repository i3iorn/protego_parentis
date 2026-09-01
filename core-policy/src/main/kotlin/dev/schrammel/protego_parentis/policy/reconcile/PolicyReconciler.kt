package dev.schrammel.protego_parentis.policy.reconcile

import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RestrictionValue
import dev.schrammel.protego_parentis.model.Policy

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

    /**
     * Packages given managed configuration by the last pass.
     *
     * Held here rather than read back from the device because the device reports
     * what this app last wrote, which cannot distinguish "we set nothing" from
     * "someone else set nothing". Losing it across a process restart costs one
     * redundant clear, which is harmless.
     */
    private var managedLast: Map<PackageName, Map<String, RestrictionValue>> = emptyMap()

    /**
     * Forgets what was pushed, so the next pass pushes it again.
     *
     * Called when a package is installed or replaced: a browser that has just been
     * reinstalled may have lost the configuration it was given, and nothing else
     * would notice, because the desired configuration has not changed.
     */
    fun forgetAppliedRestrictions() {
        managedLast = emptyMap()
    }

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
            // Independent of lockdown: a filter that can be switched off from
            // Settings in three taps is not a filter. The VPN lock goes with it,
            // because a VPN carries DNS with it and would route straight past.
            if (enforcement.dnsHost != null) {
                add(TamperRestrictions.NO_CONFIG_PRIVATE_DNS)
                add(TamperRestrictions.NO_CONFIG_VPN)
            }
            // Whether the gate is shut right now is the engine's call, not ours —
            // it depends on the clock, and this class deliberately has none.
            if (enforcement.installsBlocked) add(TamperRestrictions.NO_INSTALL_APPS)
        }
        val actualRestrictions = port.currentUserRestrictions()
        val restrictionsToAdd = desiredRestrictions - actualRestrictions
        // Only ever clear restrictions this app owns; anything else on the device
        // was put there by someone else and is none of our business.
        val restrictionsToClear =
            (actualRestrictions intersect TamperRestrictions.MANAGED) - desiredRestrictions

        restrictionsToAdd.forEach(port::addUserRestriction)
        restrictionsToClear.forEach(port::clearUserRestriction)

        // --- web filtering ---
        var dnsChanged = false
        if (port.currentPrivateDnsHost() != enforcement.dnsHost) {
            if (port.setPrivateDnsHost(enforcement.dnsHost)) {
                dnsChanged = true
            } else {
                // Almost always a host that does not answer DNS-over-TLS. Loud,
                // because a family that believes the phone is filtered and finds
                // out later that it never was has been actively misled.
                violations += "could not point private DNS at ${enforcement.dnsHost}"
            }
        }

        // Diffed against what this pass last pushed rather than against the
        // device: reading it back reports what this app set, which cannot tell
        // "we set nothing" from "nobody set anything". Pushing every tick instead
        // would mean a binder call every fifteen seconds while a limit is close.
        enforcement.appRestrictions.forEach { (pkg, restrictions) ->
            if (managedLast[pkg] != restrictions) {
                port.setApplicationRestrictions(pkg, restrictions)
            }
        }
        // A package that was managed and no longer is has its configuration
        // cleared, or yesterday's blocklist would outlive the rule that set it.
        (managedLast.keys - enforcement.appRestrictions.keys).forEach { pkg ->
            port.setApplicationRestrictions(pkg, emptyMap())
        }
        managedLast = enforcement.appRestrictions

        // --- per-app network blocking (WF-4) ---
        // The tunnel captures only the blocked apps, so everything else routes
        // exactly as it did. Torn down entirely when the list empties, rather than
        // left running empty: an always-on VPN nobody needs is a permanent status
        // icon and a standing question from whoever holds the phone.
        val wantsVpn = enforcement.networkBlocked.isNotEmpty()
        val desiredVpn = if (wantsVpn) selfPackage.value else null
        var vpnChanged = false
        if (port.currentAlwaysOnVpn() != desiredVpn) {
            if (port.setAlwaysOnVpn(desiredVpn)) {
                vpnChanged = true
            } else {
                violations += "could not set the always-on VPN to $desiredVpn"
            }
        }

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
            dnsChanged = dnsChanged,
            vpnChanged = vpnChanged,
            invariantViolations = violations,
        )
    }
}
