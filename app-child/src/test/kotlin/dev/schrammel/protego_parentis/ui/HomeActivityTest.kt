package dev.schrammel.protego_parentis.ui

import android.widget.Switch
import android.widget.TextView
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementSnapshot
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.BudgetStatus
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.usage.CollectedUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Pins what the dashboard renders for a given [EnforcementSnapshot], driven
 * through [HomeActivity.show] — the rendering under test, not the background
 * work that fetches the snapshot (HOME-UX phases 1 and 2).
 *
 * The lock-status card must disappear when nothing is restricted (phase 1);
 * a blocked row must carry its reason and lift-time as labelled lines, and
 * the dashboard's rule cards must carry no switch (phase 2 — the parent's
 * controls stay on the parent's screens).
 */
@RunWith(RobolectricTestRunner::class)
class HomeActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        // The dashboard builds only for the child's phone; a parent's or an
        // unassigned one is routed away in onCreate.
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    @Test
    fun `the lock status card is absent when nothing is restricted`() {
        // HOME-UX phase 1, priority 5: a card standing over "everything is
        // fine" is noise — the budget summary already carries the good news.
        val activity = activity()
        activity.show(snapshot())

        assertEquals(
            "no restriction means no status card",
            0,
            activity.lockStatusCardHolder.childCount,
        )
    }

    @Test
    fun `a blocked row shows reason and lift time as labelled lines`() {
        // HOME-UX phase 2: WHAT is blocked is the app's name; WHY and UNTIL
        // are labelled lines beneath it — a row that answers, not a label
        // plus a hint to tap somewhere else for one.
        val pkg = PackageName("com.example.game")
        val lifts = ZonedDateTime.now(zone).plusHours(2).toInstant()
        val activity = activity()
        activity.show(
            snapshot(
                suspend = mapOf(
                    pkg to Reason.ContextRuleActive(
                        ruleId = "rule-1",
                        ruleName = "School hours",
                        liftsAt = lifts,
                    ),
                ),
            ),
        )

        val texts = textsIn(activity.blockedList)
        val whyLine = texts.singleOrNull { it.startsWith(activity.getString(R.string.home_blocked_why, "")) }
        assertNotNull("the row must carry a labelled WHY line: $texts", whyLine)
        assertTrue(
            "the WHY line must name the rule: $whyLine",
            whyLine!!.contains("School hours"),
        )
        val untilLine = texts.firstOrNull { it.startsWith(activity.getString(R.string.home_blocked_until, "")) }
        assertNotNull("the row must carry a labelled UNTIL line: $texts", untilLine)
        assertTrue(
            "the UNTIL line must carry the clock time: $untilLine",
            untilLine!!.contains(formatTime(lifts)),
        )
    }

    @Test
    fun `dashboard rule cards carry no switch`() {
        // HOME-UX phase 2: the child gets the parent's scannable card shape
        // without the parent's controls — on/off lives on the rules list,
        // not on a screen the child can only read.
        AppRegistry.childStores.contextRules.upsert(
            ContextRule(
                id = LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID,
                name = "Daily limit",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.SetBudget(WeeklyBudget.flat(90.minutes)),
                target = RuleTarget.Everything,
            ),
        )
        val activity = activity()
        activity.show(snapshot())

        val texts = textsIn(activity.rulesList)
        assertTrue(
            "the rule's own name must render on the dashboard: $texts",
            texts.any { it.contains("Daily limit") },
        )
        assertFalse(
            "a dashboard rule card must carry no switch",
            hasSwitch(activity.rulesList),
        )
    }

    @Test
    fun `only one status card stands on the dashboard`() {
        // The dashboard must not say the same thing twice. While a rule holds
        // the phone, the lock status card carries the reason, the countdown
        // and the day's remaining time together — so the budget summary steps
        // aside instead of standing beside it with the same numbers.
        val lifts = ZonedDateTime.now(zone).plusHours(2).toInstant()
        val activity = activity()
        activity.show(
            snapshot(
                suspend = mapOf(
                    PackageName("com.example.game") to Reason.ContextRuleActive(
                        ruleId = "rule-1",
                        ruleName = "School hours",
                        liftsAt = lifts,
                    ),
                ),
            ),
        )

        assertEquals(
            "the restriction must be shown",
            1,
            activity.lockStatusCardHolder.childCount,
        )
        assertEquals(
            "the budget summary must step aside while the phone is restricted",
            0,
            activity.summaryHolder.childCount,
        )
    }

    @Test
    fun `the budget summary stands alone when nothing is restricted`() {
        // The other half of the same rule: with no restriction the lock status
        // card has nothing to say, and the budget summary is the dashboard's
        // one status card.
        val activity = activity()
        activity.show(snapshot())

        assertEquals(
            "no restriction means no status card",
            0,
            activity.lockStatusCardHolder.childCount,
        )
        assertEquals(
            "the budget summary must carry the status on its own",
            1,
            activity.summaryHolder.childCount,
        )
    }

    // ---------- fixtures ----------

    private fun activity(): HomeActivity =
        Robolectric.buildActivity(HomeActivity::class.java).create().get()

    private fun snapshot(
        suspend: Map<PackageName, Reason> = emptyMap(),
    ) = EnforcementSnapshot(
        policy = Policy(version = 1, childId = ChildId("c1"), timeZone = zone),
        usage = CollectedUsage(UsageSnapshot.empty(LocalDate.now(zone)), foregroundPackage = null),
        enforcement = Enforcement(
            policyVersion = 1,
            suspend = suspend,
            hide = emptySet(),
            deferred = emptyMap(),
            deviceBudget = BudgetStatus(
                allowed = 2.hours,
                used = kotlin.time.Duration.ZERO,
                bonus = kotlin.time.Duration.ZERO,
                remaining = 2.hours,
                level = BudgetLevel.NORMAL,
            ),
            appBudgets = emptyMap(),
            categoryBudgets = emptyMap(),
            nextChangeAt = null,
        ),
        reconcile = null,
        evaluatedAt = Instant.now(),
    )

    private fun textsIn(view: android.view.View): List<String> = buildList {
        if (view is TextView && view.text.isNotEmpty()) add(view.text.toString())
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) addAll(textsIn(view.getChildAt(i)))
        }
    }

    private fun hasSwitch(view: android.view.View): Boolean {
        if (view is Switch) return true
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) if (hasSwitch(view.getChildAt(i))) return true
        }
        return false
    }
}
