package dev.schrammel.dementor_watch.policy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PolicyClockTest {

    @Test
    fun `an evening session belongs to the day it started on`() {
        assertEquals(
            LocalDate.parse("2026-08-21"),
            PolicyClock.policyDay(at("2026-08-21", "23:50"), ZONE, boundaryHour = 4),
        )
    }

    @Test
    fun `the small hours still belong to the previous day`() {
        assertEquals(
            LocalDate.parse("2026-08-21"),
            PolicyClock.policyDay(at("2026-08-22", "03:59"), ZONE, boundaryHour = 4),
        )
    }

    @Test
    fun `the boundary hour itself starts the new day`() {
        assertEquals(
            LocalDate.parse("2026-08-22"),
            PolicyClock.policyDay(at("2026-08-22", "04:00"), ZONE, boundaryHour = 4),
        )
    }

    @Test
    fun `a midnight boundary behaves like the calendar day`() {
        assertEquals(
            LocalDate.parse("2026-08-22"),
            PolicyClock.policyDay(at("2026-08-22", "00:01"), ZONE, boundaryHour = 0),
        )
    }

    @Test
    fun `day start and end bracket the day`() {
        val day = LocalDate.parse("2026-08-21")

        assertEquals(at("2026-08-21", "04:00"), PolicyClock.dayStart(day, ZONE, 4))
        assertEquals(at("2026-08-22", "04:00"), PolicyClock.dayEnd(day, ZONE, 4))
    }

    @Test
    fun `every instant in a day maps back to that day`() {
        val day = LocalDate.parse("2026-08-21")
        val start = PolicyClock.dayStart(day, ZONE, 4)
        val end = PolicyClock.dayEnd(day, ZONE, 4)

        assertEquals(day, PolicyClock.policyDay(start, ZONE, 4))
        assertEquals(day, PolicyClock.policyDay(end.minusSeconds(1), ZONE, 4))
        assertEquals(day.plusDays(1), PolicyClock.policyDay(end, ZONE, 4))
    }
}
