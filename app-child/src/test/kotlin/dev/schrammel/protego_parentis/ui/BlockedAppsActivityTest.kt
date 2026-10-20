package dev.schrammel.protego_parentis.ui

import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementSnapshot
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.BudgetStatus
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.usage.CollectedUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
 * The blocked list, on a screen of its own.
 *
 * The dashboard says how many apps are blocked; the names and the reasons are
 * what a reader actually wants when the number is not zero, and they are a
 * screen here so the list can be the whole list rather than four rows and a
 * "and N more" line.
 */
@RunWith(RobolectricTestRunner::class)
class BlockedAppsActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun activity() = Robolectric.buildActivity(
        BlockedAppsActivity::class.java,
        BlockedAppsActivity.intent(context),
    ).create().get()

    @Test
    fun `a blocked row shows reason and lift time as labelled lines`() {
        // WHAT is blocked is the app's name; WHY and UNTIL are labelled lines
        // beneath it - a row that answers, not a label plus a hint to tap
        // somewhere else for one.
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

        val texts = textsIn(activity)
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
    fun `the whole list is here, not a sample of it`() {
        // The dashboard capped its list at four rows and offered the rest
        // behind a line. This screen exists to be scrolled, so a cap here would
        // be the same cap all over again.
        val blocked = (1..12).associate { index ->
            PackageName("com.example.app$index") to Reason.ContextRuleActive(
                ruleId = "rule-$index",
                ruleName = "School hours",
                liftsAt = ZonedDateTime.now(zone).plusHours(2).toInstant(),
            )
        }
        val activity = activity()
        activity.show(snapshot(suspend = blocked))

        val names = textsIn(activity).filter { it.contains("School hours") }
        assertEquals(
            "every blocked app must be on the list",
            12,
            names.size,
        )
    }

    @Test
    fun `nothing blocked says so rather than showing an empty screen`() {
        val activity = activity()
        activity.show(snapshot())

        val texts = textsIn(activity)
        assertTrue(
            "the empty case must say so: $texts",
            texts.any { it.contains(activity.getString(R.string.home_nothing_blocked)) },
        )
    }

    // ---------- fixtures ----------

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

    private fun textsIn(activity: BlockedAppsActivity): List<String> = buildList {
        fun walk(view: android.view.View) {
            if (view is TextView && view.text.isNotEmpty()) add(view.text.toString())
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(activity.window.decorView)
    }
}
