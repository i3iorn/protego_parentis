package dev.schrammel.protego_parentis.security

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long to refuse PIN attempts after repeated failures.
 *
 * Without this the PIN is not a lock. A four-digit PIN is ten thousand
 * combinations, and the attacker here is a motivated teenager holding the device
 * with unlimited time and no need for tooling — they can simply tap. Hashing cost
 * alone does not save us: at a hundred milliseconds an attempt, the whole keyspace
 * falls in under twenty minutes of thumb work.
 *
 * The curve is deliberately forgiving at first. A parent mistyping their own PIN
 * twice should notice nothing at all; only a sustained run of wrong answers starts
 * costing real time.
 *
 * Internal: [PinAttempts] is this module's PIN-lockout entry point and already
 * exposes the curve's effect ([PinAttempts.lockoutRemaining]) as a plain
 * [Duration] — a caller outside this module has no reason to reach the curve
 * itself.
 */
internal object PinBackoff {

    /** Failures allowed before any delay is imposed. */
    const val FREE_ATTEMPTS = 3

    private val FIRST_LOCKOUT = 30.seconds

    /** The ceiling. Long enough to make brute force hopeless, short enough that a
     *  parent who genuinely forgot is not locked out for the evening. */
    private val MAX_LOCKOUT = 15.minutes

    /**
     * The lockout imposed after [consecutiveFailures] wrong attempts.
     *
     * Doubles each time past the free allowance, capped at [MAX_LOCKOUT]. Ten
     * thousand attempts at this curve takes years rather than minutes.
     */
    fun lockoutAfter(consecutiveFailures: Int): Duration {
        if (consecutiveFailures <= FREE_ATTEMPTS) return Duration.ZERO

        val doublings = consecutiveFailures - FREE_ATTEMPTS - 1
        // Cap the exponent before shifting so a long-running attack cannot
        // overflow the multiplier and wrap around to a short lockout.
        val multiplier = 1 shl doublings.coerceIn(0, MAX_DOUBLINGS)
        return (FIRST_LOCKOUT * multiplier).coerceAtMost(MAX_LOCKOUT)
    }

    /** Enough doublings to exceed the cap; more would risk overflowing the shift. */
    private const val MAX_DOUBLINGS = 20
}
