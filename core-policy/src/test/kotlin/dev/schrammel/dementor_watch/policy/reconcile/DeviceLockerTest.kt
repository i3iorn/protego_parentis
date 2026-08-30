package dev.schrammel.dementor_watch.policy.reconcile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLockerTest {

    @Test
    fun `locking a device we do not own does nothing`() {
        val port = FakeDevicePolicyPort(deviceOwner = false)

        val result = DeviceLocker(port).lock()

        assertEquals(LockResult(locked = false, skippedReason = "not device owner"), result)
        assertEquals(0, port.locks)
    }

    @Test
    fun `locking a phone with a secure lock screen locks it and says so truthfully`() {
        val port = FakeDevicePolicyPort()
        port.secureLockScreen = true

        val result = DeviceLocker(port).lock()

        assertTrue(result.locked)
        assertFalse(result.hadNoLockScreen)
        assertEquals(1, port.locks)
    }

    @Test
    fun `locking a phone with no lock screen still locks it, but says the screen is unprotected`() {
        // The screen still goes dark — but a swipe brings it straight back, so a
        // parent told "locked" without this flag would be told something untrue.
        val port = FakeDevicePolicyPort()
        port.secureLockScreen = false

        val result = DeviceLocker(port).lock()

        assertTrue(result.locked)
        assertTrue(result.hadNoLockScreen)
        assertEquals(1, port.locks)
    }
}
