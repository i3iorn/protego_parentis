package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.RuleAction
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
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
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
        RosterStore(context).forgetAll()
        rules().rules = emptyList()
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
     *
     * Waits for the screen's title specifically, not for "any TextView":
     * [ParentGatedActivity] pins its Lock button in the corner as soon as the
     * activity opens, and a [Button] is a [TextView], so "any TextView" is
     * already true before [render] has drawn anything — the check returned
     * instantly and let assertions race the background read. The title only
     * appears once [render] runs, so it is the signal that actually means
     * "drawn".
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
        val title = context.getString(R.string.context_title)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (findText(activity, title) != null) return
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
        assertTrue(created?.effect is RuleEffect.SetBudget)
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
                effect = RuleEffect.SetBudget(WeeklyBudget.flat(90.minutes)),
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
        assertEquals(RuleEffect.Block(), created?.effect)
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
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = activity()

        assertNull(
            "starters should be hidden once the family has any rule of their own",
            findText(activity, context.getString(R.string.rules_bedtime_name)),
        )
    }

    @Test
    fun `a rule with an exception and several actions reads as a card`() {
        rules().upsert(
            ContextRule(
                id = "v2",
                name = "School",
                enabled = true,
                condition = Condition.OnWifi("School"),
                actions = listOf(
                    RuleAction(RuleEffect.Block(), RuleTarget.Everything),
                    RuleAction(RuleEffect.SetBudget(WeeklyBudget.flat(30.minutes)), RuleTarget.Everything),
                ),
                exceptWhen = Condition.BatteryBelow(10),
            ),
        )

        val activity = activity()

        // The whole rule lives in the card's single contentDescription; the eye's
        // labelled rows sit out of the reader's path.
        val card = findAll(activity.window.decorView) {
            it.contentDescription?.toString()?.startsWith("School.") == true
        }.singleOrNull()
        requireNotNull(card) { "no card carrying the rule read as one item was rendered" }
        val sentence = card.contentDescription.toString()
        assertTrue(sentence, sentence.contains(context.getString(R.string.context_effect_block)))
        assertTrue(sentence, sentence.contains(context.getString(R.string.context_join_actions).trim()))
        assertTrue(sentence, sentence.contains("10"))
        // The labelled rows, not one sentence to unpick.
        assertNotNull(findText(activity, context.getString(R.string.context_card_then)))
        val texts = findAll(activity.window.decorView) { it is TextView }
            .map { (it as TextView).text.toString() }
        assertEquals(
            "the exception is its own row, labelled like the editor's section",
            1,
            texts.count { it == context.getString(R.string.context_except_header) },
        )
    }

    // ---------- accessibility (rules-UX phase F) ----------

    @Test
    fun `a rule row reads as one item for a screen reader`() {
        rules().upsert(
            ContextRule(
                id = "v2",
                name = "School",
                enabled = true,
                condition = Condition.OnWifi("School"),
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = activity()

        val row = findAll(activity.window.decorView) {
            it.contentDescription?.toString()?.startsWith("School.") == true
        }.singleOrNull()
        requireNotNull(row) { "no rule row carrying a single description was rendered" }
        assertTrue(
            "the description should read the whole rule, status included",
            row.contentDescription.toString().contains(context.getString(R.string.rules_status_on)),
        )
    }

    @Test
    fun `the screen title and section headings are accessibility headings`() {
        rules().upsert(
            ContextRule(
                id = "v2",
                name = "School",
                enabled = true,
                condition = Condition.OnWifi("School"),
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = activity()

        assertTrue(
            "the screen title should be an accessibility heading",
            findText(activity, context.getString(R.string.context_title))?.isAccessibilityHeading == true,
        )
        assertTrue(
            "a section heading should be an accessibility heading",
            findText(activity, context.getString(R.string.rules_section_where))?.isAccessibilityHeading == true,
        )
    }

    /**
     * The controls that used to sit behind a "More controls" hub are in the
     * home screen's menu now, so the rules list carries no door to a level of
     * its own - it is the rules, and the menu is one step back.
     */
    @Test
    fun `the rules list carries no door to a controls hub`() {
        val activity = activity()

        assertNull(
            "the menu belongs to the home screen, not to the rules list",
            findText(activity, context.getString(R.string.home_menu)),
        )
    }

    /**
     * The card's switch is the whole point of the card: on/off without
     * entering an editor (rules-UX phase C). Nothing here starts
     * [ContextRuleEditActivity] — the toggle writes the store and the screen
     * re-reads, which also moves the rule into or out of "Paused" in place.
     */
    @Test
    fun `a rule card's switch pauses and resumes without opening the editor`() {
        rules().upsert(
            ContextRule(
                id = "school",
                name = "School",
                enabled = true,
                condition = Condition.OnWifi("School"),
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )
        val activity = activity()

        val turnOff = context.getString(R.string.context_card_turn_off, "School")
        val turnOn = context.getString(R.string.context_card_turn_on, "School")

        findDescriptionAfterRerender(activity, turnOff).performClick()

        assertEquals(
            "pausing must reach the store immediately",
            false,
            rules().rules.single { it.id == "school" }.enabled,
        )
        assertNull(
            "the editor must not open for a toggle",
            shadowOf(activity).nextStartedActivity,
        )

        findDescriptionAfterRerender(activity, turnOn).performClick()

        assertEquals(
            "resuming must reach the store too",
            true,
            rules().rules.single { it.id == "school" }.enabled,
        )
    }

    /** [toggleEnabled] rewrites the screen on a background thread; wait for it. */
    private fun findDescriptionAfterRerender(activity: android.app.Activity, description: String): View {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            findDescription(activity, description)?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no view described \"$description\" appeared after the screen re-read")
    }

    private fun findDescription(activity: android.app.Activity, description: String): View? =
        findAll(activity.window.decorView) { it.contentDescription == description }.firstOrNull()

    /**
     * The per-rule half of the mixed-version warning (rules-UX §G, seen to in
     * phase C): the card of a rule older phones cannot read names them, and the
     * card of a rule they can read does not — even with the same family of
     * phones behind the banner.
     */
    @Test
    fun `a card that older phones cannot read says so, and affected-only`() {
        enrolParent(version = "1.1.10")
        rules().upsert(
            ContextRule(
                id = "v2",
                name = "School",
                enabled = true,
                condition = Condition.OnWifi("School"),
                actions = listOf(RuleAction(RuleEffect.Block(), RuleTarget.Everything)),
                exceptWhen = Condition.BatteryBelow(10),
            ),
        )
        rules().upsert(
            ContextRule(
                id = "v1",
                name = "Plain",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = activity()

        val risk = context.getString(R.string.context_card_version_risk, "Mum's phone")
        assertEquals(
            "exactly the affected card carries the risk line",
            1,
            findAll(activity.window.decorView) { it is TextView && it.text == risk }.size,
        )
    }

    // ---------- mixed app versions ----------

    private fun enrolParent(version: String?) {
        val roster = RosterStore(context)
        roster.enrol(Vouch(id = DeviceId("PARENT1"), name = "Mum's phone", role = SyncRole.PARENT, publicKey = "key"))
        version?.let { roster.observed(DeviceId("PARENT1"), name = null, at = java.time.Instant.now(), appVersion = it) }
    }

    @Test
    fun `a family with an older phone is warned on the rules screen`() {
        enrolParent(version = "1.1.10")

        val activity = activity()

        assertNotNull(findText(activity, context.getString(R.string.rules_version_mixed_title)))
    }

    @Test
    fun `the warning turns urgent once a rule needs the newer version`() {
        enrolParent(version = null)
        rules().upsert(
            ContextRule(
                id = "v2",
                name = "School",
                enabled = true,
                condition = Condition.OnWifi("School"),
                actions = listOf(RuleAction(RuleEffect.Block(), RuleTarget.Everything)),
                exceptWhen = Condition.BatteryBelow(10),
            ),
        )

        val activity = activity()

        assertNotNull(findText(activity, context.getString(R.string.rules_version_risk_title)))
    }

    @Test
    fun `no warning once every phone reports the newer version`() {
        enrolParent(version = "1.2.0")

        val activity = activity()

        assertNull(findText(activity, context.getString(R.string.rules_version_mixed_title)))
        assertNull(findText(activity, context.getString(R.string.rules_version_risk_title)))
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
