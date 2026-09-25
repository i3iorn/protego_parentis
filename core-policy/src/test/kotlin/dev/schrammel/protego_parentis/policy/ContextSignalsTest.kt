package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * [ContextSignals.readsSurroundings] decides whether the enforcement tick may
 * idle at its long interval. A false "no" means a rule like "block games at
 * school" applies minutes late; a false "yes" only costs battery.
 */
class ContextSignalsTest {

    private fun rule(condition: Condition, enabled: Boolean = true) = ContextRule(
        id = "r",
        name = "Rule",
        enabled = enabled,
        condition = condition,
        effect = RuleEffect.Block(),
        target = RuleTarget.Everything,
    )

    private val window = Condition.DuringWindow(
        RecurringWindow(days = setOf(DayOfWeek.MONDAY), start = LocalTime.of(8, 0), end = LocalTime.of(15, 0)),
    )

    @Test
    fun `each surroundings condition counts`() {
        listOf(
            Condition.OnWifi("School"),
            Condition.NearDevice(DeviceTag("Mum's phone")),
            Condition.AtPlace(PlaceId("school")),
            Condition.BatteryBelow(20),
        ).forEach { condition ->
            assertTrue("$condition", ContextSignals.readsSurroundings(listOf(rule(condition))))
        }
    }

    @Test
    fun `clock-driven conditions do not`() {
        assertFalse(
            ContextSignals.readsSurroundings(
                listOf(rule(Condition.Always), rule(window), rule(Condition.AllowanceSpent(30))),
            ),
        )
    }

    @Test
    fun `a surroundings condition nested inside a combination still counts`() {
        val nested = Condition.AllOf(
            listOf(window, Condition.Not(Condition.AnyOf(listOf(Condition.AtPlace(PlaceId("home")))))),
        )

        assertTrue(ContextSignals.readsSurroundings(listOf(rule(nested))))
    }

    @Test
    fun `a disabled rule does not keep the tick awake`() {
        assertFalse(
            ContextSignals.readsSurroundings(listOf(rule(Condition.OnWifi("School"), enabled = false))),
        )
    }

    @Test
    fun `no rules at all reads nothing`() {
        assertFalse(ContextSignals.readsSurroundings(emptyList()))
    }
}
