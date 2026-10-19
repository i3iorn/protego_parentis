package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleWizardTest {

    private val block = RuleEffect.Block()
    private val always = Condition.Always
    private val everything = RuleTarget.Everything

    @Test
    fun `a fresh wizard starts at the gallery and cannot advance`() {
        val wizard = RuleWizard()

        assertEquals(RuleWizard.Step.Start, wizard.step)
        assertFalse(wizard.canAdvance)
        assertFalse(wizard.canGoBack)
        assertSame(wizard, wizard.next())
    }

    @Test
    fun `building your own asks the three questions in order`() {
        var wizard = RuleWizard().buildYourOwn()
        assertEquals(RuleWizard.Step.Effect, wizard.step)

        // Nothing chosen yet: next() is a no-op rather than skipping ahead.
        assertFalse(wizard.canAdvance)
        assertSame(wizard, wizard.next())

        wizard = wizard.chooseEffect(block).next()
        assertEquals(RuleWizard.Step.Condition, wizard.step)

        wizard = wizard.chooseCondition(always).next()
        assertEquals(RuleWizard.Step.Target, wizard.step)

        wizard = wizard.chooseTarget(everything).next()
        assertEquals(RuleWizard.Step.Review, wizard.step)
        assertTrue(wizard.isComplete)
    }

    @Test
    fun `back retraces the questions and stops at the gallery`() {
        var wizard = RuleWizard().buildYourOwn()
            .chooseEffect(block).next()
            .chooseCondition(always).next()

        assertEquals(RuleWizard.Step.Target, wizard.step)

        wizard = wizard.back()
        assertEquals(RuleWizard.Step.Condition, wizard.step)

        wizard = wizard.back()
        assertEquals(RuleWizard.Step.Effect, wizard.step)
        // Going back does not discard an answer.
        assertEquals(block, wizard.effect)

        assertEquals(RuleWizard.Step.Start, wizard.back().step)
        assertFalse(wizard.back().back().canGoBack)
    }

    @Test
    fun `a template that names its target goes straight to review`() {
        val wizard = RuleWizard().applyTemplate(
            RuleTemplateSpec(block, Condition.DuringWindow(RuleTemplates.window(RuleTemplates.Kind.SCHOOL)), everything),
        )

        assertEquals(RuleWizard.Step.Review, wizard.step)
        assertEquals(block, wizard.effect)
        assertTrue(wizard.isComplete)
    }

    @Test
    fun `a template that leaves its target open still asks to what`() {
        val wizard = RuleWizard().applyTemplate(
            RuleTemplateSpec(block, always, target = null),
        )

        assertEquals(RuleWizard.Step.Target, wizard.step)
        assertFalse(wizard.isComplete)
        assertNull(wizard.target)
    }

    @Test
    fun `toRule builds the rule once every answer and a name are there`() {
        val wizard = RuleWizard().applyTemplate(RuleTemplateSpec(block, always, everything))

        val rule = wizard.toRule("id-1", "  Lockdown  ".trim(), enabled = false)
        requireNotNull(rule)
        assertEquals("id-1", rule.id)
        assertEquals("Lockdown", rule.name)
        assertFalse(rule.enabled)
        assertEquals(always, rule.condition)
        assertEquals(listOf(RuleAction(block, everything)), rule.actions)
        assertNull(rule.exceptWhen)
    }

    @Test
    fun `toRule refuses a half-built wizard or a blank name`() {
        assertNull(RuleWizard().toRule("id", "name", enabled = true))

        val unnamed = RuleWizard()
            .applyTemplate(RuleTemplateSpec(block, always, RuleTarget.Apps(setOf(PackageName("com.example.game")))))
        assertNull(unnamed.toRule("id", "   ", enabled = true))
    }
}
