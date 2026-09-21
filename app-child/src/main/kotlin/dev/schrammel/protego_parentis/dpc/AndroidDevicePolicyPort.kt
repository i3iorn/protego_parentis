package dev.schrammel.protego_parentis.dpc

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RestrictionValue
import dev.schrammel.protego_parentis.policy.reconcile.DevicePolicyPort
import dev.schrammel.protego_parentis.policy.reconcile.SupportMessages

/**
 * The Android implementation of [DevicePolicyPort].
 *
 * Deliberately dumb. Every decision about *what* to suspend belongs to
 * `PolicyReconciler`; this class only translates. If a conditional that looks
 * like policy ever appears in here, it is in the wrong place.
 */
class AndroidDevicePolicyPort(context: Context) : DevicePolicyPort {

    private val appContext = context.applicationContext

    private val dpm = checkNotNull(appContext.getSystemService(DevicePolicyManager::class.java)) {
        "DevicePolicyManager is unavailable on this device"
    }

    private val admin = DementorDeviceAdminReceiver.componentName(appContext)

    override fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(appContext.packageName)

    override fun currentlySuspended(): Set<PackageName> =
        installedPackages().filterTo(mutableSetOf(), ::isSuspended)

    override fun currentlyHidden(): Set<PackageName> =
        installedPackages().filterTo(mutableSetOf()) { dpm.isApplicationHidden(admin, it.value) }

    override fun setSuspended(packages: Set<PackageName>, suspended: Boolean): Set<PackageName> {
        if (packages.isEmpty()) return emptySet()
        val names = packages.map(PackageName::value).toTypedArray()
        // Returns the packages it could not act on rather than throwing, so a
        // single unsuspendable system app does not fail the whole batch.
        val failures = dpm.setPackagesSuspended(admin, names, suspended)
        if (failures.isNotEmpty()) {
            // Package names go to logcat only, never to the shareable log.
            Log.w(TAG, "Could not set suspended=$suspended on ${failures.joinToString()}")
        }
        return failures.mapTo(mutableSetOf(), ::PackageName)
    }

    override fun setHidden(pkg: PackageName, hidden: Boolean): Boolean =
        dpm.setApplicationHidden(admin, pkg.value, hidden)

    override fun currentUserRestrictions(): Set<String> {
        val restrictions = dpm.getUserRestrictions(admin)
        return restrictions.keySet().filterTo(mutableSetOf(), restrictions::getBoolean)
    }

    override fun addUserRestriction(key: String) {
        dpm.addUserRestriction(admin, key)
    }

    override fun clearUserRestriction(key: String) {
        dpm.clearUserRestriction(admin, key)
    }

    override fun setUninstallBlocked(pkg: PackageName, blocked: Boolean) {
        dpm.setUninstallBlocked(admin, pkg.value, blocked)
    }

    override fun currentPrivateDnsHost(): String? =
        // Only meaningful in the "specified host" mode; in the other modes the
        // getter returns a stale value that was never applied.
        if (dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME) {
            dpm.getGlobalPrivateDnsHost(admin)
        } else {
            null
        }

    override fun setPrivateDnsHost(host: String?): Boolean {
        if (host == null) {
            // Opportunistic rather than off: encrypted DNS where the network
            // offers it is strictly better than plain text, and it is what the
            // device would have done without us.
            //
            // Any global DevicePolicyManager call can throw SecurityException
            // "operation not allowed when device has unaffiliated users" — a
            // platform restriction that fires for the device's whole lifetime
            // once any additional, unaffiliated user or profile exists on it,
            // regardless of anything this app does. Wrapped the same way
            // setGlobalPrivateDnsModeSpecifiedHost already is just below: the
            // caller already treats "false" as "could not apply DNS filtering"
            // and reports it as a loud, parent-visible violation rather than
            // silently doing nothing — which is what should happen here too,
            // instead of this exception taking every other rule down with it.
            val result = runCatching { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
                .getOrElse { error ->
                    Log.w(TAG, "Could not set opportunistic private DNS", error)
                    DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                }
            return result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
        }

        // Android checks the host actually answers DNS-over-TLS before accepting
        // it, which is why this can fail on a perfectly well-typed hostname.
        val result = runCatching { dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host) }
            .getOrElse { error ->
                Log.w(TAG, "Private DNS host refused", error)
                DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
            }
        return result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
    }

    override fun currentSupportMessages(): SupportMessages = SupportMessages(
        short = dpm.getShortSupportMessage(admin)?.toString(),
        long = dpm.getLongSupportMessage(admin)?.toString(),
    )

    override fun setSupportMessages(messages: SupportMessages) {
        runCatching {
            dpm.setShortSupportMessage(admin, messages.short)
            dpm.setLongSupportMessage(admin, messages.long)
        }.onFailure { Log.w(TAG, "Could not set the support messages", it) }
    }

    override fun setApplicationRestrictions(
        pkg: PackageName,
        restrictions: Map<String, RestrictionValue>,
    ) {
        val bundle = Bundle().apply {
            restrictions.forEach { (key, value) ->
                when (value) {
                    is RestrictionValue.Flag -> putBoolean(key, value.value)
                    is RestrictionValue.Number -> putInt(key, value.value)
                    is RestrictionValue.Strings ->
                        putStringArray(key, value.value.toTypedArray())
                }
            }
        }
        runCatching { dpm.setApplicationRestrictions(admin, pkg.value, bundle) }
            .onFailure {
                // Not the package name — Diagnostics.kt's own rule, applied
                // here too: this log records what the app did, not which app
                // it did it to.
                Log.w(TAG, "Could not set managed configuration", it)
                Diagnostics.record(appContext, DiagnosticEvent.MANAGED_CONFIG_FAILED)
            }
    }

    /**
     * Locks the screen.
     *
     * The no-argument overload deliberately. The flags version exists only to
     * evict the credential-encryption key, which is a managed-profile mechanism,
     * throws on a device without file-based encryption, and would make the child
     * re-enter their credential for reasons that have nothing to do with a parent
     * wanting the screen off.
     *
     * Requires the force-lock policy, which `res/xml/device_admin.xml` declares.
     */
    override fun lockScreen() {
        runCatching { dpm.lockNow() }
            .onFailure { Log.w(TAG, "Could not lock the screen", it) }
    }

    /**
     * Whether the phone has a real lock rather than a swipe.
     *
     * Without one, locking only puts the screen to sleep — it swipes straight back
     * open. A parent told "locked" in that case has been told something untrue.
     */
    override fun hasSecureLockScreen(): Boolean =
        appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun isNetworkLoggingEnabled(): Boolean =
        runCatching { dpm.isNetworkLoggingEnabled(admin) }.getOrDefault(false)

    override fun setNetworkLoggingEnabled(enabled: Boolean): Boolean = runCatching {
        dpm.setNetworkLoggingEnabled(admin, enabled)
        true
    }.getOrElse { error ->
        // Refused on a device with more than one user, among other reasons. The
        // caller reports it rather than leaving a screen claiming to be recording.
        Log.w(TAG, "Could not change network logging", error)
        false
    }

    override fun currentAlwaysOnVpn(): String? =
        runCatching { dpm.getAlwaysOnVpnPackage(admin) }.getOrNull()

    /**
     * Sets this app as the always-on VPN, or clears it.
     *
     * Lockdown is deliberately off. With it on, every app on the phone loses the
     * network whenever the tunnel is down — for a feature meant to block three
     * apps, that would be a phone with no internet whenever this service is
     * restarting. Off, the blocked apps briefly get through instead, which is the
     * far smaller wrong.
     *
     * A device owner setting this is granted VPN consent implicitly, so no dialog
     * appears and nothing has to be tapped on the child's phone.
     */
    override fun setAlwaysOnVpn(packageName: String?): Boolean = runCatching {
        dpm.setAlwaysOnVpnPackage(admin, packageName, false)
        true
    }.getOrElse { error ->
        Log.w(TAG, "Could not set the always-on VPN", error)
        false
    }

    override fun clearDeviceOwner() {
        // Irreversible without a factory reset. DeviceReleaser guarantees every
        // restriction has already been lifted before this runs.
        Log.i(TAG, "Relinquishing device ownership")
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(appContext.packageName)
    }

    private fun installedPackages(): List<PackageName> =
        appContext.packageManager
            .getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            .map { PackageName(it.packageName) }

    private fun isSuspended(pkg: PackageName): Boolean = try {
        dpm.isPackageSuspended(admin, pkg.value)
    } catch (e: PackageManager.NameNotFoundException) {
        // Uninstalled between listing and querying. Treat as not suspended; the
        // next reconcile pass sees the shorter package list. Not logged with
        // the package name — an ordinary, expected race, not a fault worth a
        // trace, and Diagnostics.kt's own rule applies here too regardless.
        Log.d(TAG, "Package went away while reading suspend state", e)
        false
    }

    private companion object {
        const val TAG = "DementorPort"
    }
}
