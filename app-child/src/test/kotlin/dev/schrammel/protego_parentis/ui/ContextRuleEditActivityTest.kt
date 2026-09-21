package dev.schrammel.protego_parentis.ui

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes

/**
 * This screen's own pickers used to be the only way in the app to build a
 * [Condition.DuringWindow], [Condition.AllowanceSpent] or
 * [RuleTarget.EverythingExcept] rule — they simply had no option for any of
 * the three. That mattered the moment #105's schedule migration started
 * producing rules shaped exactly like that: opening one here, editing
 * something unrelated (its name) and saving had no way to keep the real
 * condition or target, because the "when"/"to" pickers this screen already
 * shipped could only ever rebuild one of the types they did offer.
 *
 * These pin two things: that a rule carrying one of these three shapes
 * round-trips through an edit that never touches the "when"/"to" row, and
 * that the picker now offers each shape directly instead of silently
 * dropping it the moment somebody does touch that row.
 */
@RunWith(RobolectricTestRunner::class)
class ContextRuleEditActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun store() = ContextRuleStore(context)

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

    private val window = RecurringWindow(
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        start = LocalTime.of(22, 30),
        end = LocalTime.of(7, 0),
    )

    @Test
    fun `a window rule survives an unrelated edit`() {
        store().upsert(
            ContextRule(
                id = "migrated-schedule-bedtime",
                name = "Bedtime",
                enabled = true,
                condition = Condition.DuringWindow(window),
                effect = RuleEffect.Block(),
                target = RuleTarget.EverythingExcept(setOf(PackageName("com.google.android.deskclock"))),
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, "migrated-schedule-bedtime"),
        ).setup().get()

        // The rendered "when"/"to" rows must describe the real shape, not the
        // generic "always"/"everything" fallback this screen used to show for
        // anything its pickers could not build.
        assertTrue(findAllText(activity.window.decorView).any { it.contains("Bedtime") || it.contains("22:30") })

        renameField(activity)?.setText("Bedtime (renamed)")
        clickButton(activity, "Save rule")

        val saved = store().rules.single { it.id == "migrated-schedule-bedtime" }
        assertEquals("Bedtime (renamed)", saved.name)
        assertEquals(Condition.DuringWindow(window), saved.condition)
        assertEquals(
            RuleTarget.EverythingExcept(setOf(PackageName("com.google.android.deskclock"))),
            saved.target,
        )
    }

    // ---------- well-known single-app ids (#223) ----------

    /**
     * AppLimitsActivity.limitsFrom only recognizes a migrated per-app budget
     * rule while its target is still exactly the one package the id was
     * derived from (RuleStores.appBudgetLimits) — there is no well-known id
     * a second package could ever be recognized under. A rule already
     * widened past that shape (by whatever means) must refuse to save
     * unrelated edits too, rather than let the screen quietly compound the
     * damage.
     */
    @Test
    fun `saving an already-widened well-known-id rule is refused`() {
        val budgetRuleId = "migrated-app-budget-com.example.game"
        store().upsert(
            ContextRule(
                id = budgetRuleId,
                name = "Game limit",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.SetBudget(dev.schrammel.protego_parentis.model.WeeklyBudget.flat(30.minutes)),
                target = RuleTarget.Apps(setOf(PackageName("com.example.game"), PackageName("com.example.other"))),
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, budgetRuleId),
        ).setup().get()

        renameField(activity)?.setText("Game limit (renamed)")
        clickButton(activity, "Save rule")

        assertEquals(
            context.getString(R.string.context_well_known_target_locked),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
        )
        assertTrue("the screen must not finish on a refused save", !activity.isFinishing)
        assertEquals(
            "the already-stored rule must be untouched, not silently rewritten",
            "Game limit",
            store().rules.single { it.id == budgetRuleId }.name,
        )
    }

    /** The mirror: a well-known-id rule still in its one-app shape saves normally. */
    @Test
    fun `an unwidened well-known-id rule still saves its other edits`() {
        val blockRuleId = "migrated-app-block-com.example.game"
        store().upsert(
            ContextRule(
                id = blockRuleId,
                name = "Game block",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.Block(),
                target = RuleTarget.Apps(setOf(PackageName("com.example.game"))),
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, blockRuleId),
        ).setup().get()

        renameField(activity)?.setText("Game block (renamed)")
        clickButton(activity, "Save rule")

        assertEquals("Game block (renamed)", store().rules.single { it.id == blockRuleId }.name)
    }

    @Test
    fun `an allowance-spent rule survives an unrelated edit`() {
        store().upsert(
            ContextRule(
                id = "custom-allowance",
                name = "Homework only after an hour",
                enabled = true,
                condition = Condition.AllowanceSpent(60),
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, "custom-allowance"),
        ).setup().get()

        clickButton(activity, "Save rule")

        val saved = store().rules.single { it.id == "custom-allowance" }
        assertEquals(Condition.AllowanceSpent(60), saved.condition)
    }

    @Test
    fun `a negated extra condition describes itself instead of reading as Always`() {
        store().upsert(
            ContextRule(
                id = "nested-condition",
                name = "Not on school wifi, unless charged",
                enabled = true,
                condition = Condition.AllOf(
                    listOf(
                        Condition.OnWifi("School"),
                        Condition.Not(Condition.BatteryBelow(20)),
                    ),
                ),
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, "nested-condition"),
        ).setup().get()

        // The second condition (Not(BatteryBelow(20))) must describe what it
        // actually is, not silently fall back to "Always" the way an
        // unhandled Not/AllOf/AnyOf used to.
        val texts = findAllText(activity.window.decorView)
        assertTrue(texts.any { it.contains("20") })
        assertTrue(texts.none { it == context.getString(R.string.context_when_always) })
    }

    @Test
    fun `an AllOf rule survives an unrelated edit, still combined with all`() {
        store().upsert(
            ContextRule(
                id = "all-of-rule",
                name = "School and low battery",
                enabled = true,
                condition = Condition.AllOf(
                    listOf(Condition.OnWifi("School"), Condition.BatteryBelow(20)),
                ),
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, "all-of-rule"),
        ).setup().get()

        clickButton(activity, "Save rule")

        val saved = store().rules.single { it.id == "all-of-rule" }
        assertEquals(
            Condition.AllOf(listOf(Condition.OnWifi("School"), Condition.BatteryBelow(20))),
            saved.condition,
        )
    }

    @Test
    fun `an AnyOf rule survives an unrelated edit, still combined with any`() {
        store().upsert(
            ContextRule(
                id = "any-of-rule",
                name = "School or low battery",
                enabled = true,
                condition = Condition.AnyOf(
                    listOf(Condition.OnWifi("School"), Condition.BatteryBelow(20)),
                ),
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, "any-of-rule"),
        ).setup().get()

        clickButton(activity, "Save rule")

        val saved = store().rules.single { it.id == "any-of-rule" }
        assertEquals(
            Condition.AnyOf(listOf(Condition.OnWifi("School"), Condition.BatteryBelow(20))),
            saved.condition,
        )
    }

    @Test
    fun `a negated combined rule survives an unrelated edit, still negated`() {
        store().upsert(
            ContextRule(
                id = "negated-combined-rule",
                name = "Not (school and low battery)",
                enabled = true,
                condition = Condition.Not(
                    Condition.AllOf(listOf(Condition.OnWifi("School"), Condition.BatteryBelow(20))),
                ),
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, "negated-combined-rule"),
        ).setup().get()

        clickButton(activity, "Save rule")

        val saved = store().rules.single { it.id == "negated-combined-rule" }
        assertEquals(
            Condition.Not(Condition.AllOf(listOf(Condition.OnWifi("School"), Condition.BatteryBelow(20)))),
            saved.condition,
        )
    }

    @Test
    fun `the when, do and to headings each carry a working info button`() {
        store().upsert(
            ContextRule(
                id = "plain-rule",
                name = "Plain",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )

        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context, "plain-rule"),
        ).setup().get()

        listOf(
            R.string.context_when_info_title,
            R.string.context_do_info_title,
            R.string.context_to_info_title,
        ).forEach { titleRes ->
            val label = context.getString(titleRes)
            val button = findAll(activity.window.decorView) { it.contentDescription == label }.firstOrNull()
            requireNotNull(button) { "no info button described \"$label\" was rendered" }
            // Tapping it must not crash the screen — the dialog itself is
            // AlertDialog.Builder, the same toolkit every other picker here
            // already uses.
            button.performClick()
        }
    }

    @Test
    fun `saving a new rule that blocks everything with no exception asks for confirmation first`() {
        // A brand-new rule's defaults are exactly this shape (Always, Everything,
        // Block) — the accidental case #226 worried about, not a contrived one.
        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context),
        ).setup().get()

        renameField(activity)?.setText("Lockdown")
        clickButton(activity, "Save rule")

        // Not written yet — the confirmation dialog has to be answered first.
        assertTrue(store().rules.none { it.name == "Lockdown" })

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        requireNotNull(dialog) { "expected a confirmation dialog before saving a total blackout rule" }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertTrue(store().rules.any { it.name == "Lockdown" })
    }

    @Test
    fun `saving a total-block rule does not ask again once an Allow rule exists`() {
        store().upsert(
            ContextRule(
                id = "an-allow-rule",
                name = "Except homework app",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.Allow,
                target = RuleTarget.Apps(setOf(PackageName("com.example.homework"))),
            ),
        )
        val activity = Robolectric.buildActivity(
            ContextRuleEditActivity::class.java,
            ContextRuleEditActivity.intent(context),
        ).setup().get()

        renameField(activity)?.setText("Lockdown except homework")
        clickButton(activity, "Save rule")

        assertTrue(store().rules.any { it.name == "Lockdown except homework" })
    }

    private fun renameField(activity: ContextRuleEditActivity): android.widget.EditText? =
        findAll(activity.window.decorView) { it is android.widget.EditText }
            .filterIsInstance<android.widget.EditText>()
            .firstOrNull()

    private fun clickButton(activity: ContextRuleEditActivity, text: String) {
        val button = findAll(activity.window.decorView) { it is Button && it.text.toString() == text }
            .filterIsInstance<Button>()
            .firstOrNull()
        requireNotNull(button) { "no button titled \"$text\" was rendered" }.performClick()
    }

    private fun findAllText(view: View): List<String> =
        findAll(view) { it is android.widget.TextView }
            .filterIsInstance<android.widget.TextView>()
            .map { it.text.toString() }

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
