package dev.schrammel.dementor_watch.dpc

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.RestrictionValue
import dev.schrammel.dementor_watch.policy.reconcile.DevicePolicyPort

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
            val result = dpm.setGlobalPrivateDnsModeOpportunistic(admin)
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
            .onFailure { Log.w(TAG, "Could not set managed configuration for ${pkg.value}", it) }
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
        // next reconcile pass sees the shorter package list.
        Log.d(TAG, "Package went away while reading suspend state: ${pkg.value}", e)
        false
    }

    private companion object {
        const val TAG = "DementorPort"
    }
}
