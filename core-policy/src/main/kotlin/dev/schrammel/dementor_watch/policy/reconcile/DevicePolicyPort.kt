package dev.schrammel.dementor_watch.policy.reconcile

import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.RestrictionValue

/**
 * Everything the reconciler needs from `DevicePolicyManager`.
 *
 * Declared here, in pure Kotlin, so the reconciliation logic can be exercised
 * against a fake. The Android implementation is a thin adapter in `:app-child`
 * and is expected to contain no decisions of its own.
 */
interface DevicePolicyPort {

    /** False when provisioning has not happened, or the app was removed as owner. */
    fun isDeviceOwner(): Boolean

    fun currentlySuspended(): Set<PackageName>

    fun currentlyHidden(): Set<PackageName>

    /**
     * Suspends or unsuspends [packages].
     *
     * Mirrors `DevicePolicyManager.setPackagesSuspended`, which returns the packages
     * it could not act on rather than throwing — system apps and unknown packages
     * come back here instead of failing the whole batch.
     */
    fun setSuspended(packages: Set<PackageName>, suspended: Boolean): Set<PackageName>

    /** Returns false when the package could not be hidden. */
    fun setHidden(pkg: PackageName, hidden: Boolean): Boolean

    fun currentUserRestrictions(): Set<String>

    fun addUserRestriction(key: String)

    fun clearUserRestriction(key: String)

    fun setUninstallBlocked(pkg: PackageName, blocked: Boolean)

    /** The hostname the device's private DNS is currently pinned to, or null. */
    fun currentPrivateDnsHost(): String?

    /**
     * Points the device at [host], or back to the network's own resolver when null.
     *
     * Returns false when the host could not be set — most often because it does
     * not answer DNS-over-TLS, which Android checks before accepting it. A silent
     * failure here would leave a family believing the phone was filtered.
     */
    fun setPrivateDnsHost(host: String?): Boolean

    /**
     * Pushes managed configuration to [pkg], replacing whatever was there.
     *
     * An empty map clears it. Only ever called for packages this app has decided
     * to manage, so clearing cannot reach configuration set by anyone else.
     */
    fun setApplicationRestrictions(pkg: PackageName, restrictions: Map<String, RestrictionValue>)

    /**
     * Relinquishes device ownership. Irreversible without a factory reset, and
     * every policy-clearing call must already have happened — see [DeviceReleaser].
     */
    fun clearDeviceOwner()
}

/**
 * The `UserManager` restriction keys that implement TR-1.
 *
 * String literals rather than framework constants so this module stays free of
 * Android; the adapter passes them straight through, and
 * [dev.schrammel.dementor_watch.policy.reconcile] tests assert the exact values.
 */
object TamperRestrictions {

    /** Closes the main escape hatch: wiping the device to drop the DPC. */
    const val NO_FACTORY_RESET = "no_factory_reset"

    /** Without this, moving the system clock defeats every schedule and budget. */
    const val NO_CONFIG_DATE_TIME = "no_config_date_time"

    /** Safe mode would otherwise boot without third-party apps. */
    const val NO_SAFE_BOOT = "no_safe_boot"

    /** ADB would otherwise allow packages to be unsuspended by hand. */
    const val NO_DEBUGGING_FEATURES = "no_debugging_features"

    /**
     * Stops the teen pointing DNS somewhere unfiltered (WF-1).
     *
     * Applied whenever a filter is configured, not only under lockdown: a filter
     * that can be turned off from Settings in three taps is decoration.
     */
    const val NO_CONFIG_PRIVATE_DNS = "no_config_private_dns"

    /** Stops a third-party VPN routing around DNS filtering. */
    const val NO_CONFIG_VPN = "no_config_vpn"

    /** A second user would otherwise be an unmanaged phone. */
    const val NO_ADD_USER = "no_add_user"

    /**
     * Stops the app's own data being cleared from Settings.
     *
     * Without it the whole product is four taps from gone: clearing storage takes
     * the PIN, the rules, the shared history and the record of which apps were
     * already here, and leaves an app that looks installed and enforces nothing.
     * It also blocks force-stop, which is the other way to silence the service.
     */
    const val NO_CONTROL_APPS = "no_control_apps"

    /** Applied only when the family has chosen gated installs (AC-3). */
    const val NO_INSTALL_APPS = "no_install_apps"

    /** Always applied on a provisioned device. */
    val BASELINE: Set<String> = setOf(
        NO_FACTORY_RESET,
        NO_CONFIG_DATE_TIME,
        NO_SAFE_BOOT,
        NO_DEBUGGING_FEATURES,
        NO_CONFIG_PRIVATE_DNS,
        NO_CONFIG_VPN,
        NO_ADD_USER,
        NO_CONTROL_APPS,
    )

    /** Restrictions this app owns. Anything outside this set is left alone. */
    val MANAGED: Set<String> = BASELINE + NO_INSTALL_APPS
}
