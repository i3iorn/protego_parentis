package dev.schrammel.protego_parentis.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PinBackoffTest {

    @Test
    fun `a parent mistyping once or twice is not delayed`() {
        (0..PinBackoff.FREE_ATTEMPTS).forEach { failures ->
            assertEquals(
                "should not lock out after $failures failures",
                Duration.ZERO,
                PinBackoff.lockoutAfter(failures),
            )
        }
    }

    @Test
    fun `the first lockout starts small`() {
        assertEquals(30.seconds, PinBackoff.lockoutAfter(PinBackoff.FREE_ATTEMPTS + 1))
    }

    @Test
    fun `each further failure doubles the wait`() {
        assertEquals(30.seconds, PinBackoff.lockoutAfter(4))
        assertEquals(60.seconds, PinBackoff.lockoutAfter(5))
        assertEquals(120.seconds, PinBackoff.lockoutAfter(6))
        assertEquals(240.seconds, PinBackoff.lockoutAfter(7))
    }

    @Test
    fun `the wait is capped so a forgetful parent is not locked out all evening`() {
        assertEquals(15.minutes, PinBackoff.lockoutAfter(20))
        assertEquals(15.minutes, PinBackoff.lockoutAfter(100))
    }

    @Test
    fun `a very long attack does not overflow back to a short lockout`() {
        // The doubling is a bit shift; without clamping the exponent this wraps
        // and hands the attacker an unlocked device.
        listOf(60, 64, 65, 100, 1_000, Int.MAX_VALUE).forEach { failures ->
            assertEquals(
                "lockout collapsed at $failures failures",
                15.minutes,
                PinBackoff.lockoutAfter(failures),
            )
        }
    }

    @Test
    fun `the lockout never decreases as failures mount`() {
        var previous = Duration.ZERO
        (0..40).forEach { failures ->
            val lockout = PinBackoff.lockoutAfter(failures)
            assertTrue(
                "lockout went backwards at $failures failures",
                lockout >= previous,
            )
            previous = lockout
        }
    }

    @Test
    fun `brute forcing the whole keyspace is not feasible`() {
        // Ten thousand attempts against a four-digit PIN, once the cap is reached,
        // is measured in months rather than the twenty minutes of unthrottled
        // tapping it would otherwise take.
        val attemptsAtCap = 10_000 - PinBackoff.FREE_ATTEMPTS
        val total = PinBackoff.lockoutAfter(50) * attemptsAtCap

        assertTrue(total.inWholeDays > 30)
    }
}
