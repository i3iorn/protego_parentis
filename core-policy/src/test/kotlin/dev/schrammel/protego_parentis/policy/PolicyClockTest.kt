package dev.schrammel.protego_parentis.policy

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

    // ---------- daylight saving (Europe/Vienna: 29 March 02:00 -> 03:00, 25 October 03:00 -> 02:00) ----------

    /**
     * The boundary is a wall-clock hour — "the same hour the next morning" —
     * not a number of hours after midnight. Adding four elapsed hours to a
     * midnight that is followed by a skipped hour lands on 05:00.
     */
    @Test
    fun `on the day clocks go forward, the day still starts at the boundary hour`() {
        val day = LocalDate.parse("2026-03-29")

        assertEquals(at("2026-03-29", "04:00"), PolicyClock.dayStart(day, ZONE, 4))
        assertEquals(day, PolicyClock.policyDay(at("2026-03-29", "04:30"), ZONE, 4))
    }

    @Test
    fun `on the day clocks go back, the day still starts at the boundary hour`() {
        val day = LocalDate.parse("2026-10-25")

        assertEquals(at("2026-10-25", "04:00"), PolicyClock.dayStart(day, ZONE, 4))
        assertEquals(day.minusDays(1), PolicyClock.policyDay(at("2026-10-25", "03:30"), ZONE, 4))
    }

    @Test
    fun `a boundary hour the clocks skip starts the day when the clocks resume`() {
        val day = LocalDate.parse("2026-03-29")

        assertEquals(at("2026-03-29", "03:00"), PolicyClock.dayStart(day, ZONE, 2))
        assertEquals(day, PolicyClock.policyDay(at("2026-03-29", "03:10"), ZONE, 2))
    }

    @Test
    fun `every instant of a daylight-saving day maps back to that day`() {
        listOf("2026-03-28", "2026-03-29", "2026-10-24", "2026-10-25").map(LocalDate::parse).forEach { day ->
            listOf(0, 2, 4).forEach { boundary ->
                val start = PolicyClock.dayStart(day, ZONE, boundary)
                val end = PolicyClock.dayEnd(day, ZONE, boundary)

                assertEquals("$day/$boundary start", day, PolicyClock.policyDay(start, ZONE, boundary))
                assertEquals("$day/$boundary end", day, PolicyClock.policyDay(end.minusSeconds(1), ZONE, boundary))
                assertEquals("$day/$boundary next", day.plusDays(1), PolicyClock.policyDay(end, ZONE, boundary))
            }
        }
    }
}
