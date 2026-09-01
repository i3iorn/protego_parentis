package dev.schrammel.protego_parentis.security

import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Whether a parent is currently authenticated.
 *
 * Held in memory only, deliberately. Persisting it would let the session survive
 * a force-stop, and there is no case where a parent needs to stay authenticated
 * across app restarts — but there is an obvious case where a child benefits.
 *
 * This exists because gating a single screen was not enough. Every parent-only
 * screen used to be reachable from the rules screen and nowhere else, which looked
 * safe until you remember the recents list: a parent who opened the app-limits
 * screen and walked away left it resumable, PIN and all bypassed. The session is
 * checked by every one of those screens now, not just the entry point.
 */
object ParentSession {

    /**
     * How long authentication lasts. Long enough to move between the rules
     * screens without re-entering the PIN, short enough that a phone left on a
     * table is not an open door.
     */
    private val LIFETIME: Duration = 5.minutes

    private var unlockedAtElapsed: Long? = null

    val isUnlocked: Boolean
        get() = remaining() > Duration.ZERO

    fun unlock() {
        // Elapsed realtime, not wall clock: the clock is settable by the person
        // we are locking out.
        unlockedAtElapsed = SystemClock.elapsedRealtime()
    }

    /** Extends the session. Called as a parent moves between gated screens. */
    fun touch() {
        if (isUnlocked) unlock()
    }

    fun lock() {
        unlockedAtElapsed = null
    }

    private fun remaining(): Duration {
        val since = unlockedAtElapsed ?: return Duration.ZERO
        val elapsed = (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L).milliseconds
        return (LIFETIME - elapsed).coerceAtLeast(Duration.ZERO)
    }
}
