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

    /**
     * A pinned session lasts until the last pinned screen closes, not for
     * [LIFETIME]: "stay unlocked for this visit" is for the run of rule edits
     * a parent sits down to do, and ends when they are done with them.
     */
    private var pinned: Boolean = false

    val isUnlocked: Boolean
        get() = pinned || remaining() > Duration.ZERO

    fun unlock() {
        // Elapsed realtime, not wall clock: the clock is settable by the person
        // we are locking out.
        unlockedAtElapsed = SystemClock.elapsedRealtime()
    }

    /** Pins the session until [unpin]. A pinned session does not time out. */
    fun pinForThisVisit() {
        pinned = true
        unlock()
    }

    /**
     * Ends the pin. The person closing the last pinned screen was, seconds
     * ago, using an unlocked screen — so the ordinary window restarts from
     * that moment rather than from whenever they entered the PIN, which could
     * be long past. Without this, a long edit locks the phone the instant it
     * is left, and the user re-types a PIN they just proved they know.
     */
    fun unpin() {
        pinned = false
        unlock()
    }

    /** Extends the session. Called as a parent moves between gated screens. */
    fun touch() {
        if (isUnlocked) unlock()
    }

    fun lock() {
        unlockedAtElapsed = null
        pinned = false
    }

    private fun remaining(): Duration {
        val since = unlockedAtElapsed ?: return Duration.ZERO
        val elapsed = (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L).milliseconds
        return (LIFETIME - elapsed).coerceAtLeast(Duration.ZERO)
    }
}
