package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextRuleFormatTest {

    private val block = RuleAction(RuleEffect.Block(), RuleTarget.Everything)

    private fun rule(
        condition: Condition = Condition.Always,
        actions: List<RuleAction> = listOf(block),
        exceptWhen: Condition? = null,
    ) = ContextRule("r", "Rule", true, condition, actions, exceptWhen)

    @Test
    fun `a single action with an ordinary condition tree is v1`() {
        // Nested groups and a NOT on one condition were always enforced by
        // older builds; only their editor showed them flat.
        val nested = Condition.AnyOf(
            listOf(
                Condition.AllOf(listOf(Condition.OnWifi("School"), Condition.Not(Condition.AtPlace(PlaceId("home"))))),
                Condition.BatteryBelow(20),
            ),
        )
        assertFalse(ContextRuleFormat.needsV2(rule(condition = nested)))
    }

    @Test
    fun `several actions, an exception or an at-least group need v2`() {
        assertTrue(ContextRuleFormat.needsV2(rule(actions = listOf(block, RuleAction(RuleEffect.Allow, RuleTarget.Everything)))))
        assertTrue(ContextRuleFormat.needsV2(rule(exceptWhen = Condition.OnWifi("Home"))))
        assertTrue(
            ContextRuleFormat.needsV2(
                rule(condition = Condition.Not(Condition.AtLeast(2, listOf(Condition.Always, Condition.Always)))),
            ),
        )
    }

    @Test
    fun `versions at or after the v2 build read v2`() {
        assertTrue(ContextRuleFormat.supportsV2(ContextRuleFormat.V2_MIN_VERSION))
        assertTrue(ContextRuleFormat.supportsV2("1.2.1"))
        assertTrue(ContextRuleFormat.supportsV2("1.10.0"))
        assertTrue(ContextRuleFormat.supportsV2("2.0"))
        assertTrue(ContextRuleFormat.supportsV2("1.2.0-debug"))
    }

    @Test
    fun `older, missing or unreadable versions do not`() {
        assertFalse(ContextRuleFormat.supportsV2("1.1.10"))
        assertFalse(ContextRuleFormat.supportsV2("1.1"))
        assertFalse(ContextRuleFormat.supportsV2(null))
        assertFalse(ContextRuleFormat.supportsV2(""))
        assertFalse(ContextRuleFormat.supportsV2("dev"))
    }

    @Test
    fun `a family where every phone reads v2 has nothing to warn about`() {
        assertNull(ContextRuleFormat.mixedVersions(listOf("Kid" to "1.2.0", "Mum" to "1.3.1"), listOf(rule())))
        assertNull(ContextRuleFormat.mixedVersions(emptyList(), listOf(rule())))
    }

    @Test
    fun `older or silent phones are named, and v2 rules make it urgent`() {
        val phones = listOf("Kid" to "1.1.10", "Mum" to "1.2.0", "Dad" to null)
        val v2 = rule(exceptWhen = Condition.OnWifi("Home"))

        assertEquals(
            ContextRuleFormat.MixedVersions(listOf("Kid", "Dad"), v2RulesAtRisk = false),
            ContextRuleFormat.mixedVersions(phones, listOf(rule())),
        )
        assertEquals(true, ContextRuleFormat.mixedVersions(phones, listOf(rule(), v2))?.v2RulesAtRisk)
    }
}
