package dev.schrammel.protego_parentis.ui.rules

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The rules list's grouping — see [ruleGroups]. Pure, so no Android here.
 */
class RuleSectionsTest {

    private fun rule(name: String, enabled: Boolean = true, condition: Condition) = ContextRule(
        id = name,
        name = name,
        enabled = enabled,
        condition = condition,
        effect = RuleEffect.Block(),
        target = RuleTarget.Everything,
    )

    private fun window() = Condition.DuringWindow(
        RecurringWindow(
            days = setOf(DayOfWeek.MONDAY),
            start = LocalTime.of(8, 30),
            end = LocalTime.of(15, 0),
        ),
    )

    @Test
    fun `a disabled rule is paused whatever its condition`() {
        assertEquals(
            RuleSection.PAUSED,
            sectionOf(rule("x", enabled = false, condition = Condition.OnWifi("Home"))),
        )
        assertEquals(
            RuleSection.PAUSED,
            sectionOf(rule("x", enabled = false, condition = window())),
        )
    }

    @Test
    fun `a time window is a schedule`() {
        assertEquals(RuleSection.SCHEDULES, sectionOf(rule("School", condition = window())))
    }

    @Test
    fun `somewhere the phone is or is near is a where`() {
        assertEquals(RuleSection.WHERE, sectionOf(rule("Home", condition = Condition.OnWifi("Home"))))
    }

    @Test
    fun `a window that also needs a place is a where, not a schedule`() {
        val both = Condition.AllOf(listOf(window(), Condition.OnWifi("School")))

        assertEquals(RuleSection.WHERE, sectionOf(rule("At school", condition = both)))
    }

    @Test
    fun `a glucose rule is health`() {
        assertEquals(RuleSection.HEALTH, sectionOf(rule("Low", condition = Condition.GlucoseBelow(70))))
    }

    @Test
    fun `an always-on rule with nothing contextual is other`() {
        assertEquals(RuleSection.OTHER, sectionOf(rule("Block games", condition = Condition.Always)))
    }

    @Test
    fun `groups are ordered by kind, empty ones omitted, names sorted inside`() {
        val rules = listOf(
            rule("Zeta", condition = Condition.OnWifi("Home")),
            rule("Alpha", condition = window()),
            rule("Beta", enabled = false, condition = Condition.Always),
            rule("Gamma", condition = Condition.OnWifi("School")),
        )

        val groups = ruleGroups(rules)

        assertEquals(
            listOf(RuleSection.SCHEDULES, RuleSection.WHERE, RuleSection.PAUSED),
            groups.map { it.section },
        )
        assertEquals(listOf("Alpha"), groups.first { it.section == RuleSection.SCHEDULES }.rules.map { it.name })
        assertEquals(listOf("Gamma", "Zeta"), groups.first { it.section == RuleSection.WHERE }.rules.map { it.name })
    }

    @Test
    fun `the daily limit is its own group, first, even when switched off`() {
        val daily = ContextRule(
            id = LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID,
            name = "Daily limit",
            enabled = false,
            condition = Condition.Always,
            effect = RuleEffect.Block(),
            target = RuleTarget.Everything,
        )

        val groups = ruleGroups(listOf(rule("School", condition = window()), daily))

        assertEquals(
            listOf(RuleSection.DAILY_LIMIT, RuleSection.SCHEDULES),
            groups.map { it.section },
        )
        assertEquals(listOf("Daily limit"), groups.first().rules.map { it.name })
    }
}
