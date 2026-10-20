package dev.schrammel.protego_parentis.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/**
 * [dev.schrammel.protego_parentis.format.DurationFormatTest] covers the duration
 * thresholds now that duration formatting itself lives outside `ui`; this is
 * what is left that is specific to this module.
 */
class FormatTest {

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
        listOf(0, 5, 30, 120, 240).forEach { minutes ->
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
    fun `the slider spans zero to four hours`() {
        assertEquals(0, LimitSlider.minutesFor(0))
        assertEquals(4 * 60, LimitSlider.minutesFor(LimitSlider.MAX_STEPS))
    }
}
