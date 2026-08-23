package dev.schrammel.dementor_watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pure formatting, so it runs as a plain JVM test with no Android framework.
 *
 * Worth covering because these strings are the entire product surface for a teen
 * checking how long they have left — "0m" or "1h 0m" would both read as broken.
 */
class FormatTest {

    @Test
    fun `hours and minutes are shown together`() {
        assertEquals("1h 30m", Format.duration(90.minutes))
        assertEquals("2h 5m", Format.duration(125.minutes))
    }

    @Test
    fun `a whole number of hours omits the minutes`() {
        assertEquals("1h", Format.duration(60.minutes))
        assertEquals("3h", Format.duration(3.hours))
    }

    @Test
    fun `under an hour shows minutes only`() {
        assertEquals("45m", Format.duration(45.minutes))
        assertEquals("1m", Format.duration(1.minutes))
    }

    @Test
    fun `part of a minute is not rounded down to zero`() {
        // "0m left" would read as "you have none" when a little remains.
        assertEquals("under a minute", Format.duration(30.seconds))
        assertEquals("under a minute", Format.duration(Duration.ZERO))
    }

    @Test
    fun `an infinite duration reads as no limit`() {
        assertEquals("no limit", Format.duration(Duration.INFINITE))
    }

    @Test
    fun `a zero limit setting reads as no limit`() {
        // Zero on the slider means "unlimited", which is the opposite of a zero
        // duration elsewhere — the slider has no other way to express it.
        assertEquals("No limit", Format.limit(0))
        assertEquals("No limit", Format.limit(-10))
    }

    @Test
    fun `a limit in minutes is formatted like any other duration`() {
        assertEquals("30m", Format.limit(30))
        assertEquals("2h", Format.limit(120))
        assertEquals("1h 15m", Format.limit(75))
    }

    @Test
    fun `times are zero padded`() {
        assertEquals("07:00", Format.time(LocalTime.of(7, 0)))
        assertEquals("22:30", Format.time(LocalTime.of(22, 30)))
        assertEquals("00:05", Format.time(LocalTime.of(0, 5)))
    }
}

class LimitSliderTest {

    @Test
    fun `progress and minutes round-trip on step boundaries`() {
        listOf(0, 5, 30, 120, 480).forEach { minutes ->
            assertEquals(minutes, LimitSlider.minutesFor(LimitSlider.progressFor(minutes)))
        }
    }

    @Test
    fun `a value between steps rounds down to the step`() {
        assertEquals(5, LimitSlider.minutesFor(LimitSlider.progressFor(7)))
    }

    @Test
    fun `progress is clamped to the slider range`() {
        assertEquals(0, LimitSlider.progressFor(-30))
        assertEquals(LimitSlider.MAX_STEPS, LimitSlider.progressFor(100_000))
    }

    @Test
    fun `the slider spans zero to eight hours`() {
        assertEquals(0, LimitSlider.minutesFor(0))
        assertEquals(8 * 60, LimitSlider.minutesFor(LimitSlider.MAX_STEPS))
    }
}
