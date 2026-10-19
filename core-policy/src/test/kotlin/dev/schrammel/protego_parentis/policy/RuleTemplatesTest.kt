package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class RuleTemplatesTest {

    @Test
    fun `the gallery is the four windows a family usually wants`() {
        assertEquals(
            listOf(
                RuleTemplates.Kind.SCHOOL,
                RuleTemplates.Kind.HOMEWORK,
                RuleTemplates.Kind.DINNER,
                RuleTemplates.Kind.BEDTIME,
            ),
            RuleTemplates.all(),
        )
    }

    @Test
    fun `school and homework are weekdays, dinner and bedtime every day`() {
        val weekdays = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )

        assertEquals(weekdays, RuleTemplates.window(RuleTemplates.Kind.SCHOOL).days)
        assertEquals(weekdays, RuleTemplates.window(RuleTemplates.Kind.HOMEWORK).days)
        assertEquals(DayOfWeek.entries.toSet(), RuleTemplates.window(RuleTemplates.Kind.DINNER).days)
        assertEquals(DayOfWeek.entries.toSet(), RuleTemplates.window(RuleTemplates.Kind.BEDTIME).days)
    }

    @Test
    fun `the school window is the hours a school day runs`() {
        val window = RuleTemplates.window(RuleTemplates.Kind.SCHOOL)

        assertEquals(LocalTime.of(8, 30), window.start)
        assertEquals(LocalTime.of(15, 0), window.end)
    }

    @Test
    fun `bedtime uses the product's own bedtime hours`() {
        val window = RuleTemplates.window(RuleTemplates.Kind.BEDTIME)

        assertEquals(Defaults.BEDTIME_START, window.start)
        assertEquals(Defaults.BEDTIME_END, window.end)
    }

    @Test
    fun `every template blocks everything but the essentials during its window`() {
        RuleTemplates.all().forEach { kind ->
            val spec = RuleTemplates.spec(kind)

            assertEquals(RuleEffect.Block(), spec.effect)
            assertEquals(Condition.DuringWindow(RuleTemplates.window(kind)), spec.condition)
            assertEquals(RuleTarget.EverythingExcept(emptySet()), spec.target)
            assertTrue("a starter should be a complete rule", spec.target != null)
        }
    }
}
