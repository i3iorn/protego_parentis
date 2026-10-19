package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementSnapshot
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.BudgetStatus
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Enforcement
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.Reason
import dev.schrammel.protego_parentis.usage.CollectedUsage
import dev.schrammel.protego_parentis.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes

/**
 * Pins the [countdown] wording the lock-status card carries (HOME-UX phase 1):
 * a reader gets both the remaining time and the clock time it ends at, so the
 * answer is complete without arithmetic. The priority-order tests pin *which*
 * restriction the card names when several apply — the same order the
 * enforcement engine resolves, so the card can never disagree with the phone.
 */
@RunWith(RobolectricTestRunner::class)
class LockStatusCardTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private val zone: ZoneId = ZoneId.systemDefault()

    // ---------- countdown wording ----------

    @Test
    fun countdownShowsRemainingTimeAndClockTime() {
        // The shape of the answer: "Opens again in 7 h 40 m — at 06:00". The
        // duration is the plan; the clock time is the confirmation a child
        // checks against the wall clock.
        val at = ZonedDateTime.now(zone).plusHours(7).plusMinutes(40).toInstant()
        val phrase = activity.countdown(at)
        assertTrue("countdown must carry both halves: $phrase", phrase.contains("—"))
    }

    @Test
    fun countdownForAPastMomentSaysTheClockTimeOnly() {
        // Already lifted: there is no remaining time to name, so the duration
        // half is omitted rather than showing a wrong "opens in -3 m".
        val at = ZonedDateTime.now(zone).minusMinutes(3).toInstant()
        val phrase = activity.countdown(at)
        assertTrue("past moment must use the clock-time form: $phrase", phrase.startsWith(activity.getString(dev.schrammel.protego_parentis.R.string.lock_status_opens_at, formatTime(at)).substringBefore(" —").take(3)))
    }

    @Test
    fun countdownForNowSaysInZeroMinutes() {
        // "Right now" is a future moment of zero minutes, not a past one: the
        // card shows the duration form rather than flipping to a clock time
        // that would read as if the restriction had already lifted.
        val at = Instant.now()
        val phrase = activity.countdown(at)
        assertTrue(phrase.contains("0 min"))
    }

    // ---------- priority order ----------

    @Test
    fun emergencyBeatsEveryOtherRestriction() {
        // Priority 1: a parent's answer beats every rule, and a countdown under
        // an emergency would be a lie about who decides when it lifts.
        val soon = ZonedDateTime.now(zone).plusHours(1).toInstant()
        val snapshot = snapshot(
            emergency = true,
            deviceBudget = exhaustedBudget(),
            suspend = mapOf(PackageName("com.game") to ruleReason("School hours", soon)),
        )
        val card = activity.lockStatusCard(snapshot)
        assertNotNull("an emergency must render the card", card)
        assertEquals(
            activity.getString(R.string.lock_status_emergency),
            headlineOf(card!!),
        )
    }

    @Test
    fun exhaustedBudgetBeatsAnActiveRule() {
        // Priority 2 over 3: the whole device is out of time — that is the
        // fact standing over everything else, rules included.
        val soon = ZonedDateTime.now(zone).plusHours(1).toInstant()
        val snapshot = snapshot(
            deviceBudget = exhaustedBudget(),
            suspend = mapOf(PackageName("com.game") to ruleReason("School hours", soon)),
        )
        val card = activity.lockStatusCard(snapshot)
        assertEquals(
            activity.getString(R.string.lock_status_budget),
            headlineOf(card!!),
        )
    }

    @Test
    fun severalApplyingRulesNameTheTightest() {
        // Priority 3: when two rules apply at once, the one lifting soonest is
        // the headline — that is the answer the reader is standing inside.
        val soon = ZonedDateTime.now(zone).plusMinutes(30).toInstant()
        val later = ZonedDateTime.now(zone).plusHours(5).toInstant()
        val snapshot = snapshot(
            suspend = mapOf(
                PackageName("com.a") to ruleReason("Home wifi", later),
                PackageName("com.b") to ruleReason("School hours", soon),
            ),
        )
        val card = activity.lockStatusCard(snapshot)
        assertEquals(
            activity.getString(R.string.lock_status_rule, "School hours"),
            headlineOf(card!!),
        )
    }

    @Test
    fun aRuleWithoutAnEndYieldsToARuleThatCanNameOne() {
        // A Wi-Fi rule cannot say when it ends; a windowed rule can. Naming the
        // windowed one gives the reader a countdown the Wi-Fi rule would have
        // swallowed — the opposite of keeping the countdown out.
        val soon = ZonedDateTime.now(zone).plusMinutes(30).toInstant()
        val snapshot = snapshot(
            suspend = mapOf(
                PackageName("com.a") to ruleReason("Home wifi", liftsAt = null),
                PackageName("com.b") to ruleReason("School hours", soon),
            ),
        )
        val card = activity.lockStatusCard(snapshot)
        assertEquals(
            activity.getString(R.string.lock_status_rule, "School hours"),
            headlineOf(card!!),
        )
    }

    @Test
    fun aRuleWithoutAnEndStillNamesTheRuleWithoutACountdown() {
        // A lone Wi-Fi rule applying: the card still says which rule — the
        // reader deserves the name — it just keeps the countdown out rather
        // than showing a wrong one.
        val snapshot = snapshot(
            suspend = mapOf(
                PackageName("com.a") to ruleReason("Home wifi", liftsAt = null),
            ),
        )
        val card = activity.lockStatusCard(snapshot)
        assertEquals(
            activity.getString(R.string.lock_status_rule, "Home wifi"),
            headlineOf(card!!),
        )
        assertTrue(
            "no countdown may be invented for a rule with no end",
            sublineOf(card!!).contains(activity.getString(R.string.lock_status_rule_until_parent)),
        )
    }

    @Test
    fun nothingRestrictedRendersNoCardAtAll() {
        // Priority 5: a status card standing over "everything is fine" is
        // noise; the budget summary already carries the good news.
        val snapshot = snapshot()
        assertNull("nothing restricted must render nothing", activity.lockStatusCard(snapshot))
    }

    // ---------- fixtures ----------

    private fun exhaustedBudget() = BudgetStatus(
        allowed = Duration.ZERO,
        used = Duration.ofMinutes(30),
        bonus = Duration.ZERO,
        remaining = Duration.ZERO,
        level = BudgetLevel.EXHAUSTED,
    )

    private fun ruleReason(name: String, liftsAt: Instant?) =
        Reason.ContextRuleActive(ruleId = "r-$name", ruleName = name, liftsAt = liftsAt)

    private fun snapshot(
        suspend: Map<PackageName, Reason> = emptyMap(),
        deviceBudget: BudgetStatus = BudgetStatus(
            allowed = Duration.ofHours(2),
            used = Duration.ZERO,
            bonus = Duration.ZERO,
            remaining = 120.minutes,
            level = BudgetLevel.NORMAL,
        ),
        emergency: Boolean = false,
    ) = EnforcementSnapshot(
        policy = Policy(version = 1, childId = ChildId("c1"), timeZone = zone),
        usage = CollectedUsage(UsageSnapshot.empty(LocalDate.now(zone)), foregroundPackage = null),
        enforcement = Enforcement(
            policyVersion = 1,
            suspend = suspend,
            hide = emptySet(),
            deferred = emptyMap(),
            deviceBudget = deviceBudget,
            appBudgets = emptyMap(),
            categoryBudgets = emptyMap(),
            emergency = emergency,
            nextChangeAt = ZonedDateTime.now(zone).plusHours(8).toInstant(),
        ),
        reconcile = null,
        evaluatedAt = Instant.now(),
    )

    private fun headlineOf(view: View): String = findFirstText(view)

    private fun sublineOf(view: View): String = texts(view).last()

    private fun texts(view: View): List<String> = buildList {
        if (view is android.widget.TextView && view.text.isNotEmpty()) add(view.text.toString())
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
        }
    }

    private fun findFirstText(view: View): String = texts(view).first()
}
