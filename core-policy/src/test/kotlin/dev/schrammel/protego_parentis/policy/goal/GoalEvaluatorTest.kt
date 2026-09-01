package dev.schrammel.protego_parentis.policy.goal

import dev.schrammel.protego_parentis.model.DailyGoal
import dev.schrammel.protego_parentis.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class GoalEvaluatorTest {

    private val monday: LocalDate = LocalDate.parse("2026-08-17")

    private fun goal(
        enabled: Boolean = true,
        maxUsage: Duration = 1.hours,
        prize: String = "Cinema on Saturday",
        effectiveFrom: LocalDate = monday,
    ) = DailyGoal(enabled, maxUsage, prize, effectiveFrom)

    private fun day(date: LocalDate, used: Duration) =
        UsageSnapshot(date, used, mapOf())

    // ---------- the basic rule ----------

    @Test
    fun `staying under the target earns the prize`() {
        val achievement = GoalEvaluator.evaluate(goal(), day(monday, 45.minutes))

        assertNotNull(achievement)
        assertEquals(monday, achievement?.day)
        assertEquals(45.minutes, achievement?.used)
        assertEquals(1.hours, achievement?.target)
        assertEquals("Cinema on Saturday", achievement?.prize)
    }

    @Test
    fun `going over the target earns nothing`() {
        assertNull(GoalEvaluator.evaluate(goal(), day(monday, 90.minutes)))
    }

    @Test
    fun `hitting the target exactly is not under it`() {
        // "Use less than an hour" is not satisfied by using exactly an hour.
        assertNull(GoalEvaluator.evaluate(goal(maxUsage = 1.hours), day(monday, 1.hours)))
    }

    @Test
    fun `a day with no usage at all counts`() {
        assertNotNull(GoalEvaluator.evaluate(goal(), day(monday, Duration.ZERO)))
    }

    @Test
    fun `a disabled goal never awards anything`() {
        assertNull(GoalEvaluator.evaluate(goal(enabled = false), day(monday, 1.minutes)))
    }

    // ---------- the backdating trap ----------

    @Test
    fun `days before the goal existed do not earn it`() {
        // Setting a goal today must not immediately award last week's quiet days,
        // including the ones backfilled from Android at install time.
        val lastWeek = monday.minusDays(4)

        assertNull(GoalEvaluator.evaluate(goal(effectiveFrom = monday), day(lastWeek, 5.minutes)))
    }

    @Test
    fun `the day the goal was set does count`() {
        assertNotNull(GoalEvaluator.evaluate(goal(effectiveFrom = monday), day(monday, 5.minutes)))
    }

    // ---------- collecting new achievements ----------

    @Test
    fun `only uncelebrated days are returned, oldest first`() {
        val days = listOf(
            day(monday, 30.minutes),
            day(monday.plusDays(1), 20.minutes),
            day(monday.plusDays(2), 5.hours),
            day(monday.plusDays(3), 10.minutes),
        )

        val fresh = GoalEvaluator.newAchievements(
            goal = goal(),
            days = days,
            alreadyCelebrated = setOf(monday),
        )

        assertEquals(
            listOf(monday.plusDays(1), monday.plusDays(3)),
            fresh.map { it.day },
        )
    }

    @Test
    fun `a prize is never awarded twice for the same day`() {
        val days = listOf(day(monday, 30.minutes))

        val first = GoalEvaluator.newAchievements(goal(), days, alreadyCelebrated = emptySet())
        val second = GoalEvaluator.newAchievements(goal(), days, alreadyCelebrated = setOf(monday))

        assertEquals(1, first.size)
        assertEquals(emptyList<Any>(), second)
    }

    @Test
    fun `nothing is earned when the goal is off, however good the week was`() {
        val days = (0..6).map { day(monday.plusDays(it.toLong()), 1.minutes) }

        assertEquals(
            emptyList<Any>(),
            GoalEvaluator.newAchievements(goal(enabled = false), days, emptySet()),
        )
    }

    @Test
    fun `an empty history earns nothing`() {
        assertEquals(
            emptyList<Any>(),
            GoalEvaluator.newAchievements(goal(), emptyList(), emptySet()),
        )
    }
}
