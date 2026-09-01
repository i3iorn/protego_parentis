package dev.schrammel.protego_parentis.policy.reconcile

import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.policy.GAME
import dev.schrammel.protego_parentis.policy.YOUTUBE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val SELF_PACKAGE = PackageName("dev.schrammel.protego_parentis")

/**
 * Records the order of operations as well as the outcome — for release, doing the
 * right things in the wrong order leaves a permanently locked device.
 */
private class RecordingPort(
    private val delegate: FakeDevicePolicyPort = FakeDevicePolicyPort(),
) : DevicePolicyPort by delegate {

    val calls = mutableListOf<String>()
    var ownershipCleared = false
        private set

    override fun setSuspended(packages: Set<PackageName>, suspended: Boolean): Set<PackageName> {
        calls += "setSuspended($suspended)"
        return delegate.setSuspended(packages, suspended)
    }

    override fun setHidden(pkg: PackageName, hidden: Boolean): Boolean {
        calls += "setHidden($hidden)"
        return delegate.setHidden(pkg, hidden)
    }

    override fun clearUserRestriction(key: String) {
        calls += "clearUserRestriction($key)"
        delegate.clearUserRestriction(key)
    }

    override fun setUninstallBlocked(pkg: PackageName, blocked: Boolean) {
        calls += "setUninstallBlocked($blocked)"
        delegate.setUninstallBlocked(pkg, blocked)
    }

    override fun clearDeviceOwner() {
        calls += "clearDeviceOwner"
        ownershipCleared = true
    }

    val restrictions get() = delegate.restrictions
    val suspendedPackages get() = delegate.suspendedPackages
    val hiddenPackages get() = delegate.hiddenPackages
    val uninstallBlocked get() = delegate.uninstallBlocked
}

class DeviceReleaserTest {

    @Test
    fun `releasing unsuspends every app`() {
        val port = RecordingPort()
        port.suspendedPackages += setOf(GAME, YOUTUBE)

        val result = DeviceReleaser(port, SELF_PACKAGE).release()

        assertTrue(result.released)
        assertEquals(emptySet<PackageName>(), port.suspendedPackages)
        assertEquals(setOf(GAME, YOUTUBE), result.unsuspended)
    }

    @Test
    fun `releasing restores hidden apps`() {
        val port = RecordingPort()
        port.hiddenPackages += GAME

        val result = DeviceReleaser(port, SELF_PACKAGE).release()

        assertEquals(emptySet<PackageName>(), port.hiddenPackages)
        assertEquals(setOf(GAME), result.unhidden)
    }

    @Test
    fun `releasing clears the tamper restrictions`() {
        val port = RecordingPort()
        port.restrictions += TamperRestrictions.BASELINE

        val result = DeviceReleaser(port, SELF_PACKAGE).release()

        assertEquals(emptySet<String>(), port.restrictions)
        assertEquals(TamperRestrictions.BASELINE, result.restrictionsCleared)
    }

    @Test
    fun `releasing leaves restrictions set by someone else alone`() {
        val port = RecordingPort()
        port.restrictions += TamperRestrictions.NO_FACTORY_RESET
        port.restrictions += "no_outgoing_beam"

        DeviceReleaser(port, SELF_PACKAGE).release()

        assertEquals(setOf("no_outgoing_beam"), port.restrictions)
    }

    @Test
    fun `releasing makes the app removable again`() {
        val port = RecordingPort()
        port.uninstallBlocked += SELF_PACKAGE

        DeviceReleaser(port, SELF_PACKAGE).release()

        assertFalse(SELF_PACKAGE in port.uninstallBlocked)
    }

    @Test
    fun `ownership is given up only after everything has been unlocked`() {
        // The failure this guards against is a device left locked forever: once
        // ownership is gone, nothing can lift the restrictions.
        val port = RecordingPort()
        port.suspendedPackages += GAME
        port.hiddenPackages += YOUTUBE
        port.restrictions += TamperRestrictions.BASELINE
        port.uninstallBlocked += SELF_PACKAGE

        DeviceReleaser(port, SELF_PACKAGE).release()

        val clearIndex = port.calls.indexOf("clearDeviceOwner")
        assertTrue("clearDeviceOwner must be called", clearIndex >= 0)
        assertEquals(
            "clearDeviceOwner must be the final call",
            port.calls.size - 1,
            clearIndex,
        )
        assertTrue(port.calls.contains("setUninstallBlocked(false)"))
        assertTrue(
            "uninstall must be unblocked before ownership is surrendered",
            port.calls.indexOf("setUninstallBlocked(false)") < clearIndex,
        )
        assertTrue(
            "restrictions must be cleared before ownership is surrendered",
            port.calls.indexOfFirst { it.startsWith("clearUserRestriction") } < clearIndex,
        )
    }

    @Test
    fun `releasing a device we do not own does nothing`() {
        val port = RecordingPort(FakeDevicePolicyPort(deviceOwner = false))

        val result = DeviceReleaser(port, SELF_PACKAGE).release()

        assertFalse(result.released)
        assertEquals("not device owner", result.skippedReason)
        assertFalse(port.ownershipCleared)
        assertEquals(emptyList<String>(), port.calls)
    }
}
