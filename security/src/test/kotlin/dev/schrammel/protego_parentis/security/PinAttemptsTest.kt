package dev.schrammel.protego_parentis.security

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration

/**
 * [PinAttempts] — the persisted half of the lockout, as opposed to [PinBackoff]'s
 * pure curve. No existing test exercised the boot-marker/elapsed-time plumbing at
 * all before this.
 */
@RunWith(RobolectricTestRunner::class)
class PinAttemptsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun attempts() = PinAttempts(context)

    @Test
    fun `no failures yet is not locked out`() {
        assertFalse(attempts().isLockedOut)
        assertEquals(Duration.ZERO, attempts().lockoutRemaining())
    }

    @Test
    fun `a few failures within the free allowance are not locked out`() {
        val attempts = attempts()
        repeat(PinBackoff.FREE_ATTEMPTS) { attempts.recordFailure() }

        assertFalse(attempts.isLockedOut)
    }

    @Test
    fun `enough failures locks it out immediately`() {
        val attempts = attempts()
        repeat(PinBackoff.FREE_ATTEMPTS + 1) { attempts.recordFailure() }

        assertTrue(attempts.isLockedOut)
        // Recorded a moment ago, so almost none of the lockout has elapsed yet —
        // the remaining time must be close to the full curve value, not zero.
        val full = PinBackoff.lockoutAfter(PinBackoff.FREE_ATTEMPTS + 1)
        assertTrue(attempts.lockoutRemaining() > Duration.ZERO)
        assertTrue(attempts.lockoutRemaining() <= full)
    }

    @Test
    fun `a fresh instance sees the same persisted failures`() {
        attempts().let { first -> repeat(PinBackoff.FREE_ATTEMPTS + 1) { first.recordFailure() } }

        // Counting in memory would lose this the moment the app were killed —
        // a new PinAttempts(context) here stands in for that restart.
        assertTrue(attempts().isLockedOut)
    }

    @Test
    fun `success clears the lockout entirely`() {
        val attempts = attempts()
        repeat(PinBackoff.FREE_ATTEMPTS + 3) { attempts.recordFailure() }
        assertTrue(attempts.isLockedOut)

        attempts.recordSuccess()

        assertFalse(attempts.isLockedOut)
        assertEquals(Duration.ZERO, attempts.lockoutRemaining())
    }

    @Test
    fun `failures accumulate rather than resetting on each call`() {
        val attempts = attempts()
        repeat(PinBackoff.FREE_ATTEMPTS + 1) { attempts.recordFailure() }
        val afterFirstBatch = attempts.lockoutRemaining()

        attempts.recordFailure()

        assertTrue(
            "one more failure past the cap must not shrink the lockout",
            attempts.lockoutRemaining() >= afterFirstBatch,
        )
    }
}
