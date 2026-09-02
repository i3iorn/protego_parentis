package dev.schrammel.protego_parentis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * [ScheduleBlock.appliesTo] — the one place the two readings of `packages` are
 * told apart.
 */
class ScheduleModeTest {

    private val game = PackageName("com.example.game")
    private val clock = PackageName("com.example.clock")
    private val notes = PackageName("com.example.notes")

    private fun schedule(
        packages: Set<PackageName>,
        mode: ScheduleBlock.Mode,
    ) = ScheduleBlock(
        id = ScheduleId("s"),
        name = "Window",
        days = setOf(DayOfWeek.MONDAY),
        start = LocalTime.of(22, 0),
        end = LocalTime.of(7, 0),
        packages = packages,
        mode = mode,
    )

    /** Bedtime: the phone winds down, the alarm clock survives. */
    @Test
    fun `excluding pauses everything but the named apps`() {
        val bedtime = schedule(setOf(clock), ScheduleBlock.Mode.EXCLUSIVE)

        assertFalse(bedtime.appliesTo(clock))
        assertTrue(bedtime.appliesTo(game))
        assertTrue(bedtime.appliesTo(notes))
    }

    /** Homework: the games go, everything else carries on. */
    @Test
    fun `including pauses only the named apps`() {
        val homework = schedule(setOf(game), ScheduleBlock.Mode.INCLUSIVE)

        assertTrue(homework.appliesTo(game))
        assertFalse(homework.appliesTo(clock))
        assertFalse(homework.appliesTo(notes))
    }

    /** The two modes are exact opposites over the same set. */
    @Test
    fun `the modes disagree about every app`() {
        val packages = setOf(clock)
        val excluding = schedule(packages, ScheduleBlock.Mode.EXCLUSIVE)
        val including = schedule(packages, ScheduleBlock.Mode.INCLUSIVE)

        listOf(game, clock, notes).forEach { pkg ->
            assertEquals(excluding.appliesTo(pkg), !including.appliesTo(pkg))
        }
    }

    /** Excluding nothing pauses everything. This is what bedtime is. */
    @Test
    fun `excluding an empty set pauses everything`() {
        val bedtime = schedule(emptySet(), ScheduleBlock.Mode.EXCLUSIVE)

        assertTrue(bedtime.appliesTo(game))
        assertTrue(bedtime.appliesTo(clock))
    }

    /** Including nothing pauses nothing. The editor refuses to save one. */
    @Test
    fun `including an empty set pauses nothing`() {
        val empty = schedule(emptySet(), ScheduleBlock.Mode.INCLUSIVE)

        assertFalse(empty.appliesTo(game))
        assertFalse(empty.appliesTo(clock))
    }

    /**
     * Every schedule saved before modes existed was written as excluding. A
     * default of including would silently invert all of them.
     */
    @Test
    fun `the default mode is exclusive`() {
        val defaulted = ScheduleBlock(
            id = ScheduleId("s"),
            name = "Window",
            days = setOf(DayOfWeek.MONDAY),
            start = LocalTime.of(22, 0),
            end = LocalTime.of(7, 0),
            packages = setOf(clock),
        )

        assertEquals(ScheduleBlock.Mode.EXCLUSIVE, defaulted.mode)
        assertTrue(defaulted.appliesTo(game))
        assertFalse(defaulted.appliesTo(clock))
    }
}
