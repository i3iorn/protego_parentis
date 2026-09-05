package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.model.ScheduleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleResolverTest {

    private fun schoolHours(startingOn: String) = ScheduleBlock(
        id = ScheduleId("school"),
        name = "School hours",
        days = setOf(weekdayOf(startingOn)),
        start = LocalTime.parse("08:00"),
        end = LocalTime.parse("15:00"),
        packages = setOf(HOMEWORK),
        kind = ScheduleBlock.Kind.SCHOOL,
    )

    @Test
    fun `a same-day window does not cross midnight`() {
        assertFalse(schoolHours("2026-08-21").crossesMidnight)
        assertTrue(bedtime(startingOn = "2026-08-21").crossesMidnight)
    }

    @Test
    fun `a same-day window is active inside it and not outside`() {
        val block = schoolHours("2026-08-21")

        assertFalse(ScheduleResolver.isActive(block, zdt("2026-08-21", "07:59")))
        assertTrue(ScheduleResolver.isActive(block, zdt("2026-08-21", "08:00")))
        assertTrue(ScheduleResolver.isActive(block, zdt("2026-08-21", "14:59")))
        assertFalse(ScheduleResolver.isActive(block, zdt("2026-08-21", "15:00")))
    }

    @Test
    fun `a window is inactive on days it does not run`() {
        val block = schoolHours("2026-08-21")

        assertFalse(ScheduleResolver.isActive(block, zdt("2026-08-22", "10:00")))
    }

    @Test
    fun `a midnight-crossing window stays active into the next day`() {
        val block = bedtime(startingOn = "2026-08-21")

        assertFalse(ScheduleResolver.isActive(block, zdt("2026-08-21", "22:29")))
        assertTrue(ScheduleResolver.isActive(block, zdt("2026-08-21", "22:30")))
        assertTrue(ScheduleResolver.isActive(block, zdt("2026-08-21", "23:59")))
        assertTrue(ScheduleResolver.isActive(block, zdt("2026-08-22", "00:00")))
        assertTrue(ScheduleResolver.isActive(block, zdt("2026-08-22", "06:59")))
        assertFalse(ScheduleResolver.isActive(block, zdt("2026-08-22", "07:00")))
    }

    @Test
    fun `the end of the active window is reported in the next calendar day`() {
        val block = bedtime(startingOn = "2026-08-21")

        assertEquals(
            at("2026-08-22", "07:00"),
            ScheduleResolver.endOfActiveWindow(block, zdt("2026-08-22", "02:00")),
        )
    }

    @Test
    fun `no active window means no end`() {
        val block = bedtime(startingOn = "2026-08-21")

        assertNull(ScheduleResolver.endOfActiveWindow(block, zdt("2026-08-21", "12:00")))
    }

    @Test
    fun `the next edge before a window starts is its start`() {
        val block = bedtime(startingOn = "2026-08-21")

        assertEquals(
            at("2026-08-21", "22:30"),
            ScheduleResolver.nextEdgeAfter(listOf(block), zdt("2026-08-21", "21:00")),
        )
    }

    @Test
    fun `the next edge inside a window is its end`() {
        val block = bedtime(startingOn = "2026-08-21")

        assertEquals(
            at("2026-08-22", "07:00"),
            ScheduleResolver.nextEdgeAfter(listOf(block), zdt("2026-08-22", "02:00")),
        )
    }

    @Test
    fun `the next edge across several blocks is the earliest of them`() {
        val blocks = listOf(bedtime(startingOn = "2026-08-21"), schoolHours("2026-08-21"))

        assertEquals(
            at("2026-08-21", "15:00"),
            ScheduleResolver.nextEdgeAfter(blocks, zdt("2026-08-21", "10:00")),
        )
    }

    @Test
    fun `a weekly block still has an edge a week out`() {
        val block = schoolHours("2026-08-21")

        assertEquals(
            at("2026-08-28", "08:00"),
            ScheduleResolver.nextEdgeAfter(listOf(block), zdt("2026-08-21", "16:00")),
        )
    }

    @Test
    fun `no blocks means no edge`() {
        assertNull(ScheduleResolver.nextEdgeAfter(emptyList(), zdt("2026-08-21", "10:00")))
    }

    // ---------- week parity (#83) ----------

    @Test
    fun `a fortnightly block is active on its anchor week`() {
        val block = schoolHours("2026-08-21").copy(parityAnchor = LocalDate.parse("2026-08-21"))

        assertTrue(ScheduleResolver.isActive(block, zdt("2026-08-21", "10:00")))
    }

    @Test
    fun `a fortnightly block skips the week right after its anchor`() {
        val block = schoolHours("2026-08-21").copy(parityAnchor = LocalDate.parse("2026-08-21"))

        assertFalse(ScheduleResolver.isActive(block, zdt("2026-08-28", "10:00")))
    }

    @Test
    fun `a fortnightly block resumes two weeks after its anchor`() {
        val block = schoolHours("2026-08-21").copy(parityAnchor = LocalDate.parse("2026-08-21"))

        assertTrue(ScheduleResolver.isActive(block, zdt("2026-09-04", "10:00")))
    }

    @Test
    fun `a fortnightly block also skips the week right before its anchor`() {
        // Parity holds looking backward too — an anchor is just one point on an
        // alternating pattern, not where the pattern is deemed to begin.
        val block = schoolHours("2026-08-21").copy(parityAnchor = LocalDate.parse("2026-08-21"))

        assertFalse(ScheduleResolver.isActive(block, zdt("2026-08-14", "10:00")))
    }

    @Test
    fun `a fortnightly block still has its next edge two weeks out, not one`() {
        val block = schoolHours("2026-08-21").copy(parityAnchor = LocalDate.parse("2026-08-21"))

        assertEquals(
            at("2026-09-04", "08:00"),
            ScheduleResolver.nextEdgeAfter(listOf(block), zdt("2026-08-21", "16:00")),
        )
    }
}
