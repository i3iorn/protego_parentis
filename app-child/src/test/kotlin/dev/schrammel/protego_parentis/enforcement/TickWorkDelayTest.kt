package dev.schrammel.protego_parentis.enforcement

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * [TickWork.nextDelay] decides how long the enforcement service sleeps. The
 * SOS repeat only runs when a tick does, so while an emergency is active the
 * sleep has to stay short however far away the engine's next change is —
 * the tick's maximum interval went from one minute to five, and nothing
 * else held the emergency repeat to its own cadence.
 */
class TickWorkDelayTest {

    private val now: Instant = Instant.parse("2026-09-25T12:00:00Z")

    @Test
    fun `idles at the maximum interval when nothing is coming`() {
        assertEquals(
            TickWork.MAX_INTERVAL_MILLIS,
            TickWork.nextDelay(nextChange = null, now = now, emergencyActive = false),
        )
    }

    @Test
    fun `wakes for the engine's next change, but never sooner than the minimum`() {
        assertEquals(
            90_000L,
            TickWork.nextDelay(now.plusSeconds(90), now, emergencyActive = false),
        )
        assertEquals(
            TickWork.MIN_INTERVAL_MILLIS,
            TickWork.nextDelay(now.plusSeconds(1), now, emergencyActive = false),
        )
    }

    @Test
    fun `an active emergency keeps the tick at the repeat cadence`() {
        assertEquals(
            TickWork.SOS_REPEAT_MILLIS,
            TickWork.nextDelay(nextChange = null, now = now, emergencyActive = true),
        )
        assertEquals(
            TickWork.SOS_REPEAT_MILLIS,
            TickWork.nextDelay(now.plusSeconds(3_600), now, emergencyActive = true),
        )
    }

    /**
     * Nothing broadcasts a Wi-Fi, place, Bluetooth or battery change, so a rule
     * reading one only applies when a tick runs. Held to a minute — the
     * cadence before the idle interval rose to five — while such rules exist.
     */
    @Test
    fun `rules reading the surroundings keep the tick at a minute`() {
        assertEquals(
            TickWork.SURROUNDINGS_INTERVAL_MILLIS,
            TickWork.nextDelay(null, now, emergencyActive = false, watchingSurroundings = true),
        )
        assertEquals(
            TickWork.SOS_REPEAT_MILLIS,
            TickWork.nextDelay(null, now, emergencyActive = true, watchingSurroundings = true),
        )
    }

    @Test
    fun `an emergency never makes a sooner change wait longer`() {
        assertEquals(
            20_000L,
            TickWork.nextDelay(now.plusSeconds(20), now, emergencyActive = true),
        )
    }
}
