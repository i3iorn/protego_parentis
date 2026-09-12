package dev.schrammel.protego_parentis.ui

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [AppControlActivity]'s blocked switch no longer owns its own storage — a
 * block it sets is an ordinary [ContextRule] at a well-known per-app id, the
 * same shape [dev.schrammel.protego_parentis.rules.LocalPolicyStore.migrateAppRulesToUnifiedRules]
 * produces from an old-format block. These pin [AppControlActivity.isBlocked]'s
 * read of that rule directly, without driving the async app-catalog load.
 */
@RunWith(RobolectricTestRunner::class)
class AppControlActivityTest {

    private val activity get() = Robolectric.buildActivity(AppControlActivity::class.java).get()

    private val pkg = PackageName("com.example.game")

    @Test
    fun `an app with a block rule at its well-known id reads as blocked`() {
        val rule = ContextRule(
            id = activity.blockRuleId(pkg),
            name = "Blocked",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block(),
            target = RuleTarget.Apps(setOf(pkg)),
        )

        assertTrue(activity.isBlocked(pkg, listOf(rule)))
    }

    @Test
    fun `an app with no rule at all reads as not blocked`() {
        assertFalse(activity.isBlocked(pkg, emptyList()))
    }

    @Test
    fun `a rule under a different id is not this screen's own block`() {
        val rule = ContextRule(
            id = "some-other-rule",
            name = "Unrelated",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block(),
            target = RuleTarget.Apps(setOf(pkg)),
        )

        assertFalse(activity.isBlocked(pkg, listOf(rule)))
    }

    @Test
    fun `a budget rule at the same id is not read as a block`() {
        val rule = ContextRule(
            id = activity.blockRuleId(pkg),
            name = "Budget",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.SafetyExempt,
            target = RuleTarget.Apps(setOf(pkg)),
        )

        assertFalse(activity.isBlocked(pkg, listOf(rule)))
    }

    @Test
    fun `each app's block rule id is stable and distinct`() {
        val other = PackageName("com.example.other")

        assertTrue(activity.blockRuleId(pkg) == activity.blockRuleId(pkg))
        assertFalse(activity.blockRuleId(pkg) == activity.blockRuleId(other))
    }
}
