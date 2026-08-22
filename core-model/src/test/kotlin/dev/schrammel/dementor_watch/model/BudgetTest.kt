package dev.schrammel.dementor_watch.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class WeeklyBudgetTest {

    @Test
    fun `a flat budget is the same every day`() {
        val budget = WeeklyBudget.flat(2.hours)

        DayOfWeek.entries.forEach { day ->
            assertEquals(2.hours, budget.forDay(day))
        }
    }

    @Test
    fun `an override wins over the default for that day only`() {
        val budget = WeeklyBudget(
            default = 1.hours,
            overrides = mapOf(DayOfWeek.SATURDAY to 3.hours),
        )

        assertEquals(3.hours, budget.forDay(DayOfWeek.SATURDAY))
        assertEquals(1.hours, budget.forDay(DayOfWeek.SUNDAY))
    }

    @Test
    fun `the school week shape is generous from friday through sunday`() {
        val budget = WeeklyBudget.schoolWeek(schoolNight = 1.hours, weekend = 3.hours)

        listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
            .forEach { assertEquals("$it should be a school night", 1.hours, budget.forDay(it)) }

        listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            .forEach { assertEquals("$it should be a weekend", 3.hours, budget.forDay(it)) }
    }

    @Test
    fun `unlimited reports itself as unlimited on every day`() {
        assertTrue(WeeklyBudget.UNLIMITED.isUnlimited)
        assertEquals(Duration.INFINITE, WeeklyBudget.UNLIMITED.forDay(DayOfWeek.MONDAY))
    }

    @Test
    fun `a budget with any finite day is not unlimited`() {
        val budget = WeeklyBudget(
            default = Duration.INFINITE,
            overrides = mapOf(DayOfWeek.MONDAY to 30.minutes),
        )

        assertFalse(budget.isUnlimited)
    }

    @Test
    fun `a negative budget is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WeeklyBudget(default = (-5).minutes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WeeklyBudget(default = 1.hours, overrides = mapOf(DayOfWeek.MONDAY to (-1).minutes))
        }
    }

    @Test
    fun `a zero budget is allowed and means no time at all`() {
        // Distinct from "no limit": zero is how a parent says "not today".
        val budget = WeeklyBudget.flat(Duration.ZERO)

        assertEquals(Duration.ZERO, budget.forDay(DayOfWeek.MONDAY))
        assertFalse(budget.isUnlimited)
    }
}
