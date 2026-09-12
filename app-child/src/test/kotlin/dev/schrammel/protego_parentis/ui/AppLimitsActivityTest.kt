package dev.schrammel.protego_parentis.ui

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.minutes

/**
 * [AppLimitsActivity] no longer owns its own storage — a limit it sets is an
 * ordinary [ContextRule] at a well-known per-app id, the same shape
 * [dev.schrammel.protego_parentis.rules.LocalPolicyStore.migrateAppRulesToUnifiedRules]
 * produces from an old-format limit. These pin [AppLimitsActivity.limitsFrom]'s
 * reverse translation directly, without driving the slider dialog or the
 * screen's async app-catalog load — [Background.load] runs on a real thread
 * Robolectric does not control, so a full screen test for this one is left
 * for whoever can verify it against a real build.
 */
@RunWith(RobolectricTestRunner::class)
class AppLimitsActivityTest {

    private val activity get() = Robolectric.buildActivity(AppLimitsActivity::class.java).get()

    private val pkg = PackageName("com.example.game")

    private fun budgetRule(id: String, minutes: Int, target: PackageName = pkg) = ContextRule(
        id = id,
        name = "App limit",
        enabled = true,
        condition = Condition.Always,
        effect = RuleEffect.SetBudget(WeeklyBudget.flat(minutes.minutes)),
        target = RuleTarget.Apps(setOf(target)),
    )

    @Test
    fun `a rule at this app's well-known id reads back as its minutes`() {
        val rule = budgetRule(activity.ruleId(pkg), minutes = 25)

        assertEquals(mapOf(pkg.value to 25), activity.limitsFrom(listOf(rule)))
    }

    @Test
    fun `a SetBudget rule under a different id is not this screen's own limit`() {
        // Same target, same effect, but not the id this screen writes to —
        // a hand-authored ContextRuleEditActivity rule that happens to name
        // the same app must not be mistaken for the slider's own row.
        val rule = budgetRule(id = "some-other-rule", minutes = 25)

        assertTrue(activity.limitsFrom(listOf(rule)).isEmpty())
    }

    @Test
    fun `a block rule at the same id is not read as a budget`() {
        val block = ContextRule(
            id = activity.ruleId(pkg),
            name = "Blocked",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block(),
            target = RuleTarget.Apps(setOf(pkg)),
        )

        assertTrue(activity.limitsFrom(listOf(block)).isEmpty())
    }

    @Test
    fun `a budget targeting more than one app is not read as a per-app limit`() {
        val other = PackageName("com.example.other")
        val rule = ContextRule(
            id = activity.ruleId(pkg),
            name = "Multi-app",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.SetBudget(WeeklyBudget.flat(25.minutes)),
            target = RuleTarget.Apps(setOf(pkg, other)),
        )

        assertTrue(activity.limitsFrom(listOf(rule)).isEmpty())
    }

    @Test
    fun `each app's rule id is stable and distinct`() {
        val other = PackageName("com.example.other")

        assertEquals(activity.ruleId(pkg), activity.ruleId(pkg))
        assertTrue(activity.ruleId(pkg) != activity.ruleId(other))
    }
}
