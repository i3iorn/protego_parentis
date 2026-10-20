package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * A category limit set on a parent's phone, for a child's phone.
 *
 * The limit is a rule, so it is written to the child's ruleset and replicated
 * to the child's phone. What matters here is that the parent is told the
 * truth: a write that could not be made says so, and only a write that landed
 * is described as on its way.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryEditRemoteTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val childId = DeviceId("CHILD1")

    @Before
    fun setUp() {
        // The identity first: a phone with none of its own may not write a
        // child's ruleset, and the copy of the child's policy below is
        // written through that same door.
        LocalDeviceId.useInTests("PARENTPHONE")
        RoleStore(context).choose(DeviceRole.PARENT)
        RosterStore(context).enrol(
            Vouch(id = childId, name = "Kid", role = SyncRole.CHILD, publicKey = "key"),
        )
        ParentSession.unlock()
        // The parent's copy of the child's policy, as if it had arrived by sync.
        LocalPolicyStore(context).categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 0, packages = emptySet()),
        )
        PolicyReplication(context).write(LocalPolicyStore(context).snapshot(), childId)
    }

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        RosterStore(context).forgetAll()
        ParentSession.lock()
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    private fun editor() = Robolectric.buildActivity(
        CategoryEditActivity::class.java,
        CategoryEditActivity.intent(context, "games-id", childId),
    ).setup().get()

    @Test
    fun `a limit saved for the child's phone becomes a rule on that ruleset`() {
        val activity = editor()
        dragSliderTo(activity, 4)
        clickButton(activity, "Save")

        val rules = PolicyReplication(context).readContextRules(childId)
        val rule = rules.singleOrNull { it.id == "migrated-category-budget-games-id" }
        assertTrue("the limit must become a rule on the child's ruleset: $rules", rule != null)
        assertTrue("the rule must carry the allowance: $rule", rule?.effect is RuleEffect.SetBudget)
    }

    @Test
    fun `a save that could not be made says so instead of claiming it will sync`() {
        // A phone with no identity of its own (a keystore key invalidated by
        // a biometric change, the moments before first unlock) may not write
        // the child's rules. Nothing leaves this phone, so the screen must not
        // say the change is on its way.
        LocalDeviceId.useInTests(null)

        val activity = editor()
        dragSliderTo(activity, 4)
        clickButton(activity, "Save")

        val toast = ShadowToast.getTextOfLatestToast()
        assertEquals(
            "the screen must say the save failed, not that it will sync",
            context.getString(R.string.category_save_failed),
            toast,
        )
        assertFalse(
            "the screen must not close over a save that did not happen",
            activity.isFinishing,
        )
    }

    // ---------- driving the screen ----------

    private fun dragSliderTo(activity: CategoryEditActivity, step: Int) {
        val bar = requireNotNull(findSeekBar(activity.window.decorView)) { "no limit slider was rendered" }
        // A user drag, so the screen hears about it: the slider only reports
        // changes the user made, never its own first layout.
        shadowOf(bar).onSeekBarChangeListener!!.onProgressChanged(bar, step, true)
    }

    private fun clickButton(activity: CategoryEditActivity, text: String) {
        val button = findAll(activity.window.decorView) { it is Button }
            .filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == text }
        requireNotNull(button) { "no button titled \"$text\" was rendered" }.performClick()
    }

    private fun findAll(view: View, predicate: (View) -> Boolean): List<View> = buildList {
        if (predicate(view)) add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) addAll(findAll(view.getChildAt(i), predicate))
        }
    }

    private fun findSeekBar(view: View): SeekBar? {
        if (view is SeekBar) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findSeekBar(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
