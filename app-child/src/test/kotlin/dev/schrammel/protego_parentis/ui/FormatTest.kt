package dev.schrammel.protego_parentis.ui

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
 * Tests [shapeOf] rather than [formatDuration]/[formatLimit] directly: those two
 * need a real `Context` to look up a localized string, which this module's test
 * setup has no way to fake. [shapeOf] is where the thresholds these tests
 * actually care about live — the string lookup itself is a straight one-case-per-
 * shape mapping with nothing left to get wrong.
 *
 * Worth covering because these figures are the entire product surface for a teen
 * checking how long they have left — "0m" or "1h 0m" would both read as broken.
 */
class FormatTest {

    @Test
    fun `hours and minutes are shown together`() {
        assertEquals(DurationShape.Both(1, 30), shapeOf(90.minutes))
        assertEquals(DurationShape.Both(2, 5), shapeOf(125.minutes))
    }

    @Test
    fun `a whole number of hours omits the minutes`() {
        assertEquals(DurationShape.HoursOnly(1), shapeOf(60.minutes))
        assertEquals(DurationShape.HoursOnly(3), shapeOf(3.hours))
    }

    @Test
    fun `under an hour shows minutes only`() {
        assertEquals(DurationShape.MinutesOnly(45), shapeOf(45.minutes))
        assertEquals(DurationShape.MinutesOnly(1), shapeOf(1.minutes))
    }

    @Test
    fun `part of a minute is not rounded down to zero`() {
        // "0m left" would read as "you have none" when a little remains.
        assertEquals(DurationShape.UnderAMinute, shapeOf(30.seconds))
        assertEquals(DurationShape.UnderAMinute, shapeOf(Duration.ZERO))
    }

    @Test
    fun `an infinite duration reads as no limit`() {
        assertEquals(DurationShape.NoLimit, shapeOf(Duration.INFINITE))
    }

    @Test
    fun `times are zero padded`() {
        assertEquals("07:00", formatTime(LocalTime.of(7, 0)))
        assertEquals("22:30", formatTime(LocalTime.of(22, 30)))
        assertEquals("00:05", formatTime(LocalTime.of(0, 5)))
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
