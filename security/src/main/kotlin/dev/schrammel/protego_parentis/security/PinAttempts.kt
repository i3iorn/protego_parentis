package dev.schrammel.protego_parentis.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tracks failed PIN attempts and enforces the [PinBackoff] curve.
 *
 * Two details matter for this to be a lock rather than a speed bump:
 *
 *  - **Failures persist.** Counting in memory would let the attacker force-stop
 *    the app between guesses and never see a delay.
 *  - **Timing uses [SystemClock.elapsedRealtime].** The wall clock is attacker-
 *    controlled — winding the date forward would clear any lockout measured
 *    against it. Elapsed-realtime cannot be set, only reset by a reboot, and a
 *    reboot per guess is its own deterrent.
 */
class PinAttempts(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** How long until another attempt is accepted. Zero when unlocked. */
    fun lockoutRemaining(): Duration {
        val failures = prefs.getInt(KEY_FAILURES, 0)
        val lockout = PinBackoff.lockoutAfter(failures)
        if (lockout == Duration.ZERO) return Duration.ZERO

        val since = elapsedSinceLastFailure() ?: return lockout
        return (lockout - since).coerceAtLeast(Duration.ZERO)
    }

    val isLockedOut: Boolean get() = lockoutRemaining() > Duration.ZERO

    @SuppressLint("ApplySharedPref")
    fun recordFailure() {
        prefs.edit(commit = true) {
            putInt(KEY_FAILURES, prefs.getInt(KEY_FAILURES, 0) + 1)
            putLong(KEY_LAST_FAILURE_ELAPSED, SystemClock.elapsedRealtime())
            putLong(KEY_BOOT_MARKER, bootMarker())
        }
    }

    @SuppressLint("ApplySharedPref")
    fun recordSuccess() {
        prefs.edit(commit = true) {
            remove(KEY_FAILURES)
            remove(KEY_LAST_FAILURE_ELAPSED)
            remove(KEY_BOOT_MARKER)
        }
    }

    /**
     * Null when the device has rebooted since the last failure, because
     * elapsed-realtime restarts from zero and the stored value is meaningless.
     * The failure count deliberately survives: a reboot must not reset progress
     * towards the cap, or rebooting becomes the bypass.
     */
    private fun elapsedSinceLastFailure(): Duration? {
        if (prefs.getLong(KEY_BOOT_MARKER, -1L) != bootMarker()) return null
        val last = prefs.getLong(KEY_LAST_FAILURE_ELAPSED, -1L)
        if (last < 0L) return null
        return (SystemClock.elapsedRealtime() - last).coerceAtLeast(0L).milliseconds
    }

    /**
     * Changes on every boot: wall clock minus uptime is the approximate moment the
     * device started, which is stable while running and different after a restart.
     */
    private fun bootMarker(): Long =
        (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / BOOT_MARKER_PRECISION_MS

    private companion object {
        const val FILE_NAME = "pin_attempts"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_LAST_FAILURE_ELAPSED = "last_failure_elapsed"
        const val KEY_BOOT_MARKER = "boot_marker"

        /** Coarse enough to absorb clock drift, fine enough to detect a reboot. */
        const val BOOT_MARKER_PRECISION_MS = 10_000L
    }
}
