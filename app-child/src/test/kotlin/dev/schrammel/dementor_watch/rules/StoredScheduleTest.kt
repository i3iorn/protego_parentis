package dev.schrammel.dementor_watch.rules

import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.ScheduleBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The boundary between an editor's half-finished state and a schedule the engine
 * will accept (SC-2).
 */
class StoredScheduleTest {

    @Test
    fun `a complete schedule becomes a domain block`() {
        val block = schedule().toDomain()

        assertEquals(SCHEDULE_ID, block?.id?.value)
        assertEquals("Homework", block?.name)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), block?.days)
        assertEquals(LocalTime.of(16, 30), block?.start)
        assertEquals(LocalTime.of(18, 0), block?.end)
        assertEquals(ScheduleBlock.Kind.CUSTOM, block?.kind)
    }

    @Test
    fun `carries the apps that stay usable`() {
        val block = schedule(allowed = setOf("com.example.notes")).toDomain()

        assertEquals(setOf(PackageName("com.example.notes")), block?.allowedPackages)
    }

    /**
     * The domain type throws on any of these. An editor holding a half-typed
     * schedule is normal, so the conversion has to refuse rather than explode.
     */
    @Test
    fun `refuses a schedule the engine would reject`() {
        assertNull(schedule(name = "").toDomain())
        assertNull(schedule(name = "   ").toDomain())
        assertNull(schedule(days = emptySet()).toDomain())
        assertNull(schedule(start = 600, end = 600).toDomain())
    }

    @Test
    fun `reports whether it is complete`() {
        assertTrue(schedule().isComplete)
        assertFalse(schedule(name = "").isComplete)
        assertFalse(schedule(days = emptySet()).isComplete)
        assertFalse(schedule(start = 600, end = 600).isComplete)
    }

    /** A stored file could name day 9. Dropping it must not produce an empty set. */
    @Test
    fun `refuses a schedule whose days are all unrecognised`() {
        assertNull(schedule(days = setOf(0, 9)).toDomain())
    }

    @Test
    fun `keeps only the days it recognises`() {
        val block = schedule(days = setOf(1, 9)).toDomain()

        assertEquals(setOf(DayOfWeek.MONDAY), block?.days)
    }

    @Test
    fun `trims the name that reaches the block screen`() {
        assertEquals("Homework", schedule(name = "  Homework  ").toDomain()?.name)
    }

    /** A window ending before it starts runs into the next day — bedtime's shape. */
    @Test
    fun `keeps a window that crosses midnight`() {
        val block = schedule(start = 22 * 60, end = 7 * 60).toDomain()

        assertEquals(true, block?.crossesMidnight)
    }

    @Test
    fun `round-trips a time to a minute of the day`() {
        assertEquals(16 * 60 + 30, StoredSchedule.minuteOfDay(LocalTime.of(16, 30)))
        assertEquals(LocalTime.of(16, 30), schedule(start = 16 * 60 + 30).start)
    }

    private fun schedule(
        name: String = "Homework",
        days: Set<Int> = setOf(1, 3),
        start: Int = 16 * 60 + 30,
        end: Int = 18 * 60,
        allowed: Set<String> = emptySet(),
    ) = StoredSchedule(
        id = SCHEDULE_ID,
        name = name,
        days = days,
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        allowedPackages = allowed,
    )

    private companion object {
        const val SCHEDULE_ID = "homework"
    }
}
