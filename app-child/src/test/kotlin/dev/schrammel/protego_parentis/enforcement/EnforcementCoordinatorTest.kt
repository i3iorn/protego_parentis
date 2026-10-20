package dev.schrammel.protego_parentis.enforcement

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RestrictionValue
import dev.schrammel.protego_parentis.policy.reconcile.DevicePolicyPort
import dev.schrammel.protego_parentis.policy.reconcile.SupportMessages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [EnforcementCoordinator] used to `new` every collaborator itself, including
 * `AndroidDevicePolicyPort` — so the branch of [EnforcementCoordinator.evaluate]
 * that only runs once a device is actually device-owner-provisioned could never
 * be exercised anywhere but a real device. It now takes each collaborator as a
 * constructor parameter with a production default, so this test can supply a
 * fake [DevicePolicyPort] and reach both branches of the "read the rules,
 * measure usage, ask the engine, apply the answer" sequence directly.
 */
@RunWith(RobolectricTestRunner::class)
class EnforcementCoordinatorTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Usage access is an app-op, not a normal permission — see
     * [dev.schrammel.protego_parentis.usage.UsageAccess]'s own doc comment for
     * why there is no other way to grant it than flipping the op Robolectric
     * shadows underneath it.
     */
    private fun grantUsageAccess() {
        val appOps = context.getSystemService(AppOpsManager::class.java)!!
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_ALLOWED,
        )
    }

    /**
     * The shadow treats an unset op as allowed, so the test that exercises the
     * "no usage access" branch has to state that denial explicitly rather than
     * rely on the shadow's default staying changed from some other test.
     */
    private fun denyUsageAccess() {
        val appOps = context.getSystemService(AppOpsManager::class.java)!!
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_ERRORED,
        )
    }

    @Test
    fun `evaluate returns null without usage access`() {
        denyUsageAccess()
        val coordinator = EnforcementCoordinator(context, port = FakeDevicePolicyPort(owner = false))

        assertNull(coordinator.evaluate())
    }

    @Test
    fun `evaluate is monitor-only when the device is not device owner`() {
        grantUsageAccess()
        val coordinator = EnforcementCoordinator(context, port = FakeDevicePolicyPort(owner = false))

        val snapshot = coordinator.evaluate()

        assertNotNull(snapshot)
        assertTrue(snapshot!!.monitorOnly)
        assertNull(snapshot.reconcile)
    }

    @Test
    fun `evaluate reconciles through the port when the device is device owner`() {
        grantUsageAccess()
        val port = FakeDevicePolicyPort(owner = true)
        val coordinator = EnforcementCoordinator(context, port = port)

        val snapshot = coordinator.evaluate()

        assertNotNull(snapshot)
        assertFalse(snapshot!!.monitorOnly)
        assertNotNull(snapshot.reconcile)
    }

    /**
     * The one real provisioning path — `adb shell dpm set-device-owner`,
     * run against an app that is already installed and whose service is
     * already running — grants ownership to a live coordinator. It used to
     * remember the first answer for the life of the process, so a service
     * that started before provisioning stayed monitor-only until something
     * happened to restart it.
     */
    @Test
    fun `ownership granted while running is enforced on the next pass`() {
        grantUsageAccess()
        val port = FakeDevicePolicyPort(owner = false)
        val coordinator = EnforcementCoordinator(context, port = port)

        assertTrue(coordinator.evaluate()!!.monitorOnly)

        port.owner = true
        val afterProvisioning = coordinator.evaluate()!!

        assertFalse(afterProvisioning.monitorOnly)
        assertNotNull(afterProvisioning.reconcile)
    }

    /** A stand-in for `DevicePolicyManager` that does nothing but remember the answer it was told to give. */
    private class FakeDevicePolicyPort(var owner: Boolean) : DevicePolicyPort {
        private var supportMessages = SupportMessages(short = null, long = null)
        private var networkLogging = false

        override fun isDeviceOwner(): Boolean = owner
        override fun currentlySuspended(): Set<PackageName> = emptySet()
        override fun currentlyHidden(): Set<PackageName> = emptySet()
        override fun setSuspended(packages: Set<PackageName>, suspended: Boolean): Set<PackageName> = emptySet()
        override fun setHidden(pkg: PackageName, hidden: Boolean): Boolean = true
        override fun currentUserRestrictions(): Set<String> = emptySet()
        override fun addUserRestriction(key: String) = Unit
        override fun clearUserRestriction(key: String) = Unit
        override fun setUninstallBlocked(pkg: PackageName, blocked: Boolean) = Unit
        override fun currentPrivateDnsHost(): String? = null
        override fun setPrivateDnsHost(host: String?): Boolean = true
        override fun setApplicationRestrictions(pkg: PackageName, restrictions: Map<String, RestrictionValue>) = Unit
        override fun lockScreen() = Unit
        override fun hasSecureLockScreen(): Boolean = true
        override fun isNetworkLoggingEnabled(): Boolean = networkLogging
        override fun setNetworkLoggingEnabled(enabled: Boolean): Boolean {
            networkLogging = enabled
            return true
        }
        override fun currentAlwaysOnVpn(): String? = null
        override fun setAlwaysOnVpn(packageName: String?): Boolean = true
        override fun currentSupportMessages(): SupportMessages = supportMessages
        override fun setSupportMessages(messages: SupportMessages) {
            supportMessages = messages
        }
        override fun clearDeviceOwner() = Unit
    }
}
