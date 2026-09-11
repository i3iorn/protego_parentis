package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.ScheduleStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import kotlin.time.Duration.Companion.minutes

/**
 * #106: this screen is now the one place every rule kind is created and
 * edited — these pin the two things #105's own migration couldn't cover
 * because they only matter once the old dedicated screens are gone: the
 * daily limit's pinned slot at the top, and bedtime as an ordinary starter
 * rather than a switch on a screen that no longer exists.
 */
@RunWith(RobolectricTestRunner::class)
class ContextRulesActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun rules() = ContextRuleStore(context)

    @Before
    fun setUp() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")
        ParentSession.unlock()
    }

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        ParentSession.lock()
    }

    /**
     * [onGatedContentVisible] renders via [Background.load] — a real
     * background thread Robolectric does not control, posting its result
     * back through a paused main-looper message [ShadowLooper] has to be
     * told to run. Idled in a bounded retry loop rather than once, since
     * there is no signal here for exactly when that background thread
     * finishes; this is the same shape any of this app's other
     * [Background.load]-based screens would need, just not yet exercised by
     * an existing test to copy from.
     */
    private fun activity(): ContextRulesActivity {
        val activity = Robolectric.buildActivity(
            ContextRulesActivity::class.java,
            ContextRulesActivity.intent(context),
        ).setup().get()
        awaitRendered(activity)
        return activity
    }

    private fun awaitRendered(activity: ContextRulesActivity) {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (findAll(activity.window.decorView) { it is TextView }.isNotEmpty()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `with no daily limit rule, a CTA offers to create one`() {
        val activity = activity()

        val cta = findText(activity, context.getString(R.string.context_daily_limit_add))
        assertNotNull("the create-a-daily-limit row should be rendered", cta)

        clickAncestor(cta!!)

        val created = rules().rules.singleOrNull { it.id == LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID }
        assertNotNull("tapping the CTA should create the rule", created)
        assertEquals(Condition.Always, created?.condition)
        assertEquals(RuleTarget.Everything, created?.target)
        assertTrue(created?.effect is RuleEffect.SetDeviceBudget)
        assertEquals(
            ContextRuleEditActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `with a daily limit rule already there, its own row shows instead of the CTA`() {
        rules().upsert(
            ContextRule(
                id = LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID,
                name = "Daily limit",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.SetDeviceBudget(WeeklyBudget.flat(90.minutes)),
                target = RuleTarget.Everything,
            ),
        )

        val activity = activity()

        assertNull(
            "the CTA should not render once a daily-limit rule exists",
            findText(activity, context.getString(R.string.context_daily_limit_add)),
        )
        assertNotNull(findText(activity, "Daily limit"))
    }

    @Test
    fun `starters, bedtime included, show only while there is no rule besides the daily limit`() {
        val activity = activity()

        assertNotNull(
            "bedtime should be offered as a starter",
            findText(activity, context.getString(R.string.rules_bedtime_name)),
        )
    }

    @Test
    fun `tapping the bedtime starter creates a rule at the same id the schedule migration uses`() {
        val activity = activity()
        val bedtimeStarter = findText(activity, context.getString(R.string.rules_bedtime_name))
        assertNotNull(bedtimeStarter)

        clickAncestor(bedtimeStarter!!)

        val expectedId = LocalPolicyStore.MIGRATED_SCHEDULE_ID_PREFIX + ScheduleStore.BEDTIME_ID
        val created = rules().rules.singleOrNull { it.id == expectedId }
        assertNotNull("bedtime should land at the well-known migrated-schedule id", created)
        assertFalseEnabled(created)
        assertTrue(created?.condition is Condition.DuringWindow)
        assertEquals(RuleEffect.Block, created?.effect)
        assertEquals(RuleTarget.EverythingExcept(emptySet()), created?.target)
    }

    private fun assertFalseEnabled(rule: ContextRule?) =
        assertTrue("a starter must arrive switched off", rule?.enabled == false)

    @Test
    fun `starters no longer show once a non-daily-limit rule exists`() {
        rules().upsert(
            ContextRule(
                id = "custom",
                name = "Custom",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.Block,
                target = RuleTarget.Everything,
            ),
        )

        val activity = activity()

        assertNull(
            "starters should be hidden once the family has any rule of their own",
            findText(activity, context.getString(R.string.rules_bedtime_name)),
        )
    }

    private fun findText(activity: android.app.Activity, text: String): View? =
        findAll(activity.window.decorView) { (it is TextView && it.text == text) || (it is Button && it.text == text) }
            .firstOrNull()

    private fun clickAncestor(view: View) {
        var candidate: View? = view
        while (candidate != null && !candidate.isClickable) candidate = candidate.parent as? View
        requireNotNull(candidate) { "no clickable ancestor found" }.performClick()
    }

    private fun findAll(view: View, predicate: (View) -> Boolean): List<View> {
        val matches = mutableListOf<View>()
        fun walk(v: View) {
            if (predicate(v)) matches += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return matches
    }
}
