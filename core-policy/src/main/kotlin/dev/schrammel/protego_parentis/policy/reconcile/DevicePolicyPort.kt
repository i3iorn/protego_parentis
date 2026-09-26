package dev.schrammel.protego_parentis.policy.reconcile

import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RestrictionValue

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
     * Locks the screen immediately.
     *
     * A command, not desired state: there is nothing to diff it against, so it is
     * never called by the reconciler. See [DeviceLocker].
     */
    fun lockScreen()

    /**
     * Whether the phone actually has a lock — a PIN, pattern or password.
     *
     * Without one, locking the screen only puts it to sleep. A parent pressing
     * "lock now" and getting a screen that swipes straight back open has been told
     * something untrue, so the caller checks first and says so.
     */
    fun hasSecureLockScreen(): Boolean

    /** Whether the device is currently recording DNS lookups and connections. */
    fun isNetworkLoggingEnabled(): Boolean

    /**
     * Starts or stops network logging (WF-3).
     *
     * The platform shows the user a permanent notice while this is on, which is
     * not a side effect to be worked around — it is the transparency this feature
     * is only acceptable with.
     */
    fun setNetworkLoggingEnabled(enabled: Boolean): Boolean

    /** The package currently set as the device's always-on VPN, or null. */
    fun currentAlwaysOnVpn(): String?

    /**
     * Sets, or clears, the always-on VPN package (WF-4).
     *
     * Returns false when the package cannot serve as one. Lockdown is deliberately
     * not exposed: with it on, every app loses the network whenever the VPN is
     * down, which for a feature meant to block three apps would be a phone with no
     * internet.
     */
    fun setAlwaysOnVpn(packageName: String?): Boolean

    /**
     * The support messages currently registered with the platform, or nulls.
     *
     * Read before writing so the messages are only set when they differ, which is
     * what lets the caller re-assert them on every pass and pick up a language
     * change for free.
     */
    fun currentSupportMessages(): SupportMessages

    /**
     * Replaces the text Android shows in place of its own enterprise wording.
     *
     * Android's defaults talk about work policies and IT departments, which is
     * wrong twice over on a family phone: it names the wrong relationship, and it
     * points a child at a support desk that does not exist. These say who actually
     * set the rule and where to go about it.
     *
     * Not policy — the same two sentences whatever the rules say — so the
     * reconciler never touches them. See [SupportMessages] for what each one is
     * shown for.
     */
    fun setSupportMessages(messages: SupportMessages)

    /**
     * Relinquishes device ownership. Irreversible without a factory reset, and
     * every policy-clearing call must already have happened — see [DeviceReleaser].
     */
    fun clearDeviceOwner()
}

/**
 * The two admin support messages, as the platform models them.
 *
 * They are shown in different places and are not interchangeable:
 *
 * - [short] appears when someone taps a setting this app has disabled. It is the
 *   one a child actually meets. Android truncates it past 200 characters.
 * - [long] appears only in Settings, under the device admin's own entry, for
 *   somebody who went looking for what is managing the phone.
 *
 * Nullable because that is what the platform returns before either has ever been
 * set, and "never set" has to be distinguishable from "set to something" for the
 * caller to know whether to write.
 */
data class SupportMessages(val short: String?, val long: String?) {
    companion object {
        /** Android's own limit; past this it truncates rather than refusing. */
        const val SHORT_LIMIT = 200
    }
}

/**
 * The `UserManager` restriction keys that implement TR-1.
 *
 * String literals rather than framework constants so this module stays free of
 * Android; the adapter passes them straight through, and
 * [dev.schrammel.protego_parentis.policy.reconcile] tests assert the exact values.
 *
 * Internal: a caller outside this module wants [DeviceLocker]/[DeviceReleaser]/
 * [PolicyReconciler], never these raw keys directly.
 */
internal object TamperRestrictions {

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
     * Stops the default dialer and SMS app being changed (#293).
     *
     * Whatever holds those roles is on the safety allowlist, exempt from every
     * rule, so choosing an app as the default SMS app would otherwise exempt
     * it. `UserManager.DISALLOW_CONFIG_DEFAULT_APPS`, API 34; older platforms
     * ignore an unknown restriction, which is why the allowlist also only
     * accepts system apps for those roles.
     */
    const val NO_CONFIG_DEFAULT_APPS = "no_config_default_apps"

    /**
     * Stops the app's own data being cleared from Settings.
     *
     * Without it the whole product is four taps from gone: clearing storage takes
     * the PIN, the rules, the shared history and the record of which apps were
     * already here, and leaves an app that looks installed and enforces nothing.
     * It also blocks force-stop, which is the other way to silence the service.
     *
     * It is worse than "enforces nothing", which is why this is in [ALWAYS] and
     * not in [LOCKDOWN]. The roster is cleared with everything else, and a phone
     * with no trusted parents is the bootstrap state — so the app reopens in
     * first-run setup, on a device that is still device owner, in the hands of
     * the person the rules are about. They pick the role, choose the PIN, and
     * enrol their own phone as the founding parent. Losing enforcement is
     * recoverable and visible; handing over the authority is neither.
     */
    const val NO_CONTROL_APPS = "no_control_apps"

    /** Applied only when the family has chosen gated installs (AC-3). */
    const val NO_INSTALL_APPS = "no_install_apps"

    /**
     * Applied on every provisioned device, whether or not the family has asked
     * for lockdown.
     *
     * Only one restriction is in here, and it is the one that protects this app
     * from being emptied rather than the one that restricts the phone. The
     * distinction matters: [LOCKDOWN] takes things away from the person holding
     * the device, and a family may reasonably decline all of it. This does not.
     * It is what makes every other setting mean anything, because an app whose
     * data can be cleared has no rules, no PIN, no history and — the part that
     * took a while to notice — no roster, which hands the person being managed
     * the founding-parent path. See the note on [NO_CONTROL_APPS].
     */
    val ALWAYS: Set<String> = setOf(NO_CONTROL_APPS)

    /** Added when the family has explicitly asked to lock the device down. */
    val LOCKDOWN: Set<String> = setOf(
        NO_FACTORY_RESET,
        NO_CONFIG_DATE_TIME,
        NO_SAFE_BOOT,
        NO_DEBUGGING_FEATURES,
        NO_CONFIG_PRIVATE_DNS,
        NO_CONFIG_VPN,
        NO_ADD_USER,
        NO_CONFIG_DEFAULT_APPS,
    )

    /** Everything a locked-down provisioned device carries. */
    val BASELINE: Set<String> = ALWAYS + LOCKDOWN

    /** Restrictions this app owns. Anything outside this set is left alone. */
    val MANAGED: Set<String> = BASELINE + NO_INSTALL_APPS
}
