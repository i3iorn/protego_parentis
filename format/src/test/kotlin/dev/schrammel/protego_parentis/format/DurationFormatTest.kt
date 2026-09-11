package dev.schrammel.protego_parentis.format

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pure formatting, so it runs as a plain JVM test with no Android framework.
 *
 * Tests [shapeOf] rather than [formatDuration] directly: that needs a real
 * `Context` to look up a localized string, which this module's test setup has
 * no way to fake. [shapeOf] is where the thresholds these tests actually care
 * about live — the string lookup itself is a straight one-case-per-shape
 * mapping with nothing left to get wrong.
 *
 * Worth covering because these figures are the entire product surface for a
 * teen checking how long they have left — "0m" or "1h 0m" would both read as
 * broken.
 */
class DurationFormatTest {

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
}
