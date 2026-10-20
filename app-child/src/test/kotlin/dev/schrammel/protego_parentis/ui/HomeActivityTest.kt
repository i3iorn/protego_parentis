package dev.schrammel.protego_parentis.ui

import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementSnapshot
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.BudgetStatus
import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.usage.CollectedUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * work that fetches the snapshot.
 *
 * The dashboard answers one question and offers the rest on demand: one status
 * card, the child's own allowances, a count of what is blocked, and the two
 * things they can do from here. The rules, the history and the settings live
 * in the menu, and the blocked list lives on a screen of its own.
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
        // A card standing over "everything is fine" is noise — the budget
        // summary already carries the good news.
        val activity = activity()
        activity.show(snapshot())

        assertEquals(
            "no restriction means no status card",
            0,
            activity.lockStatusCardHolder.childCount,
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

    @Test
    fun `each category with a limit earns a line, and one without earns none`() {
        // The dashboard's overview content: what is left in each of the
        // buckets they spend from. A category with no limit has no allowance
        // and no line.
        val activity = activity()
        activity.show(
            snapshot(
                categories = listOf(
                    Category(id = CategoryId("games"), name = "Games", budget = WeeklyBudget.UNLIMITED),
                    Category(id = CategoryId("school"), name = "School", budget = WeeklyBudget.UNLIMITED),
                ),
                categoryBudgets = mapOf(
                    CategoryId("games") to BudgetStatus(
                        allowed = 60.minutes,
                        used = 20.minutes,
                        bonus = kotlin.time.Duration.ZERO,
                        remaining = 40.minutes,
                        level = BudgetLevel.NORMAL,
                    ),
                ),
            ),
        )

        val texts = textsIn(activity.allowancesHolder)
        assertTrue(
            "the category with a limit must have a line: $texts",
            texts.any { it.contains("Games") },
        )
        assertFalse(
            "a category with no limit earns no line: $texts",
            texts.any { it.contains("School") },
        )
    }

    @Test
    fun `how much is blocked is a count, not a list`() {
        // The count is the glance; the names and the reasons are a visit, and
        // the list is a screen of its own so it can be the whole list.
        val activity = activity()
        activity.show(
            snapshot(
                suspend = mapOf(
                    PackageName("com.example.game") to Reason.ContextRuleActive(
                        ruleId = "rule-1",
                        ruleName = "School hours",
                        liftsAt = ZonedDateTime.now(zone).plusHours(2).toInstant(),
                    ),
                ),
            ),
        )

        val texts = textsIn(activity.blockedCountHolder)
        assertTrue(
            "the blocked count must be on the dashboard: $texts",
            texts.any { it.contains("1") && it.contains("blocked") },
        )
    }

    @Test
    fun `nothing blocked means no count at all`() {
        val activity = activity()
        activity.show(snapshot())

        assertEquals(
            "no blocked apps means no count on the dashboard",
            0,
            activity.blockedCountHolder.childCount,
        )
    }

    // ---------- fixtures ----------

    private fun activity(): HomeActivity =
        Robolectric.buildActivity(HomeActivity::class.java).create().get()

    private fun snapshot(
        suspend: Map<PackageName, Reason> = emptyMap(),
        categories: List<Category> = emptyList(),
        categoryBudgets: Map<CategoryId, BudgetStatus> = emptyMap(),
    ) = EnforcementSnapshot(
        policy = Policy(version = 1, childId = ChildId("c1"), timeZone = zone, categories = categories),
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
            categoryBudgets = categoryBudgets,
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
}
