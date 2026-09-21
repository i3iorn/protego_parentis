package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextRuleAuthoring.looksLikeTotalBlackout] draws its blackout line
 * differently for an [RuleEffect.AlwaysBlock] rule than for a plain
 * [RuleEffect.Block]: an [RuleEffect.Allow] rule saves the latter but not
 * the former, so an override-everything rule needs its own carve-out
 * (only [RuleEffect.SafetyExempt] counts) to stop the warning.
 *
 * A plain JVM test, with no Activity or store behind it: the function only
 * ever sees the rule being saved and the rest of the ruleset it would join,
 * both passed in directly.
 */
class ContextRuleAuthoringTest {

    private val overrideEverything = ContextRule(
        id = "override-everything",
        name = "Lockdown",
        enabled = true,
        condition = Condition.Always,
        effect = RuleEffect.AlwaysBlock(),
        target = RuleTarget.Everything,
    )

    @Test
    fun `a brand-new rule that blocks everything with no exception is a blackout`() {
        val freshLockdown = ContextRule(
            id = "fresh-lockdown",
            name = "Lockdown",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block(),
            target = RuleTarget.Everything,
        )

        assertTrue(ContextRuleAuthoring.looksLikeTotalBlackout(freshLockdown, otherRules = emptyList()))
    }

    @Test
    fun `a total-block rule is not a blackout once an Allow rule exists`() {
        val lockdown = ContextRule(
            id = "lockdown-except-homework",
            name = "Lockdown except homework",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block(),
            target = RuleTarget.Everything,
        )
        val allowRule = ContextRule(
            id = "an-allow-rule",
            name = "Except homework app",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Allow,
            target = RuleTarget.Apps(setOf(PackageName("com.example.homework"))),
        )

        assertEquals(
            false,
            ContextRuleAuthoring.looksLikeTotalBlackout(lockdown, otherRules = listOf(allowRule)),
        )
    }

    @Test
    fun `an always-block-everything rule is a blackout even with an allow rule, but not with a safety-exempt one`() {
        assertTrue(
            "with no carve-out at all, this is a blackout",
            ContextRuleAuthoring.looksLikeTotalBlackout(overrideEverything, otherRules = emptyList()),
        )

        val allowRule = ContextRule(
            id = "an-allow-rule",
            name = "Except homework app",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Allow,
            target = RuleTarget.Apps(setOf(PackageName("com.example.homework"))),
        )
        assertTrue(
            "an allow rule cannot save anything from an always-block override",
            ContextRuleAuthoring.looksLikeTotalBlackout(overrideEverything, otherRules = listOf(allowRule)),
        )

        val exemptRule = ContextRule(
            id = "an-exempt-rule",
            name = "Exempt homework app",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.SafetyExempt,
            target = RuleTarget.Apps(setOf(PackageName("com.example.homework"))),
        )
        assertEquals(
            "only a safety-exempt rule counts as this override's own carve-out",
            false,
            ContextRuleAuthoring.looksLikeTotalBlackout(overrideEverything, otherRules = listOf(allowRule, exemptRule)),
        )
    }
}
