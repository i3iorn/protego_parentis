package dev.schrammel.protego_parentis.security

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [PinLock.clearIfVerified] is the only way to wipe the PIN — there is no bare
 * `clear()` a caller could reach without checking [PinLock.verify] first.
 */
@RunWith(RobolectricTestRunner::class)
class PinLockTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the wrong pin does not clear anything`() {
        val lock = PinLock(context)
        lock.set("4821")

        assertFalse(lock.clearIfVerified("0000"))
        assertTrue(lock.isSet)
    }

    @Test
    fun `the right pin clears it`() {
        val lock = PinLock(context)
        lock.set("4821")

        assertTrue(lock.clearIfVerified("4821"))
        assertFalse(lock.isSet)
    }
}
