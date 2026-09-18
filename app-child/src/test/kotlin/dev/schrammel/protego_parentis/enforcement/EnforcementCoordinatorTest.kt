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

    @Test
    fun `evaluate returns null without usage access`() {
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

    /** A stand-in for `DevicePolicyManager` that does nothing but remember the answer it was told to give. */
    private class FakeDevicePolicyPort(private val owner: Boolean) : DevicePolicyPort {
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
