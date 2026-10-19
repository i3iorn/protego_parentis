package dev.schrammel.protego_parentis.ui

import android.app.Activity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pins the [countdown] wording the lock-status card carries (HOME-UX phase 1):
 * a reader gets both the remaining time and the clock time it ends at, so the
 * answer is complete without arithmetic.
 */
@RunWith(RobolectricTestRunner::class)
class LockStatusCardTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private val zone: ZoneId = ZoneId.systemDefault()

    @Test
    fun countdownShowsRemainingTimeAndClockTime() {
        // The shape of the answer: "Opens again in 7 h 40 m — at 06:00". The
        // duration is the plan; the clock time is the confirmation a child
        // checks against the wall clock.
        val at = ZonedDateTime.now(zone).plusHours(7).plusMinutes(40).toInstant()
        val phrase = activity.countdown(at)
        assertTrue("countdown must carry both halves: $phrase", phrase.contains("—"))
    }

    @Test
    fun countdownForAPastMomentSaysTheClockTimeOnly() {
        // Already lifted: there is no remaining time to name, so the duration
        // half is omitted rather than showing a wrong "opens in -3 m".
        val at = ZonedDateTime.now(zone).minusMinutes(3).toInstant()
        val phrase = activity.countdown(at)
        assertTrue("past moment must use the clock-time form: $phrase", phrase.startsWith(activity.getString(dev.schrammel.protego_parentis.R.string.lock_status_opens_at, formatTime(at)).substringBefore(" —").take(3)))
    }

    @Test
    fun countdownForNowSaysInZeroMinutes() {
        // "Right now" is a future moment of zero minutes, not a past one: the
        // card shows the duration form rather than flipping to a clock time
        // that would read as if the restriction had already lifted.
        val at = Instant.now()
        val phrase = activity.countdown(at)
        assertTrue(phrase.contains("0 min"))
    }
}
