package dev.schrammel.dementor_watch.policy.reconcile

import dev.schrammel.dementor_watch.model.PackageName

/** What releasing the device actually undid. */
data class ReleaseResult(
    val released: Boolean,
    val unsuspended: Set<PackageName> = emptySet(),
    val unhidden: Set<PackageName> = emptySet(),
    val restrictionsCleared: Set<String> = emptySet(),
    val skippedReason: String? = null,
)

/**
 * Hands the device back.
 *
 * This is TR-6, "graduation", and it is also the escape hatch during development.
 * A parental control app that cannot cleanly remove itself is one factory reset
 * away from a one-star review, and on a test device it is simply a trap.
 *
 * **Order is the whole point.** Every restriction has to be lifted while we are
 * still device owner, because the moment ownership is relinquished we lose the
 * authority to lift anything. Getting this backwards leaves a device permanently
 * locked with no app able to unlock it — which is exactly why the sequence lives
 * here, under test, rather than inline in an Android activity.
 */
class DeviceReleaser(
    private val port: DevicePolicyPort,
    private val selfPackage: PackageName,
) {

    fun release(): ReleaseResult {
        if (!port.isDeviceOwner()) {
            return ReleaseResult(released = false, skippedReason = "not device owner")
        }

        // 1. Let every app run again.
        val suspended = port.currentlySuspended()
        if (suspended.isNotEmpty()) port.setSuspended(suspended, suspended = false)

        val hidden = port.currentlyHidden()
        hidden.forEach { port.setHidden(it, hidden = false) }

        // 2. Drop only the restrictions we own; anything else was not ours to set.
        val ourRestrictions = port.currentUserRestrictions() intersect TamperRestrictions.MANAGED
        ourRestrictions.forEach(port::clearUserRestriction)

        // 3. Make the app removable again, before we lose the right to say so.
        port.setUninstallBlocked(selfPackage, blocked = false)

        // 4. Only now give up ownership. Nothing above is possible after this.
        port.clearDeviceOwner()

        return ReleaseResult(
            released = true,
            unsuspended = suspended,
            unhidden = hidden,
            restrictionsCleared = ourRestrictions,
        )
    }
}
