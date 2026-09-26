package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleAction
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

    // ---------- places (#290) ----------

    @Test
    fun `a place rule, however nested, reads places`() {
        val nested = Condition.AllOf(listOf(window, Condition.Not(Condition.AtPlace(PlaceId("home")))))

        assertTrue(ContextSignals.readsPlaces(listOf(rule(nested))))
    }

    @Test
    fun `other surroundings do not read places`() {
        assertFalse(
            ContextSignals.readsPlaces(
                listOf(rule(Condition.OnWifi("School")), rule(Condition.BatteryBelow(20)), rule(window)),
            ),
        )
    }

    @Test
    fun `a disabled place rule does not read places`() {
        assertFalse(ContextSignals.readsPlaces(listOf(rule(Condition.AtPlace(PlaceId("school")), enabled = false))))
    }

    @Test
    fun `a place only in the exception, or in an at-least group, still counts`() {
        val except = ContextRule(
            id = "r-except",
            name = "Rule",
            enabled = true,
            condition = Condition.Always,
            actions = listOf(RuleAction(RuleEffect.Block(), RuleTarget.Everything)),
            exceptWhen = Condition.AtPlace(PlaceId("home")),
        )
        assertTrue(ContextSignals.readsPlaces(listOf(except)))
        assertTrue(ContextSignals.readsSurroundings(listOf(except)))

        val atLeast = Condition.AtLeast(1, listOf(Condition.AtPlace(PlaceId("school"))))
        assertTrue(ContextSignals.readsPlaces(listOf(rule(atLeast))))
    }
}
