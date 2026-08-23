package dev.schrammel.dementor_watch.dpc

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.schrammel.dementor_watch.model.PackageName
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
