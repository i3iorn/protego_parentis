package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.os.Looper
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import kotlin.time.Duration.Companion.minutes

/**
 * [CategoryEditActivity]'s create/edit/delete round trip through
 * [LocalPolicyStore.categories] — untested before #161. The budget half of
 * that round trip moved to [ContextRuleStore] afterwards; the tests below
 * pin the new shape.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryEditActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun store() = LocalPolicyStore(context)
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

    private fun activity(id: String? = null) = Robolectric.buildActivity(
        CategoryEditActivity::class.java,
        CategoryEditActivity.intent(context, id),
    ).setup().get()

    @Test
    fun `saving a new category with a name creates it`() {
        val activity = activity()
        nameField(activity)?.setText("Games")

        clickButton(activity, "Save")

        val saved = store().categories.single()
        assertEquals("Games", saved.name)
    }

    @Test
    fun `a blank name is refused rather than saving an unnamed category`() {
        val activity = activity()
        nameField(activity)?.setText("   ")

        clickButton(activity, "Save")

        assertTrue(store().categories.isEmpty())
    }

    @Test
    fun `editing an existing category preserves its id`() {
        store().categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 60, packages = emptySet()),
        )

        val activity = activity(id = "games-id")
        nameField(activity)?.setText("Games (renamed)")
        clickButton(activity, "Save")

        val saved = store().categories.single()
        assertEquals("games-id", saved.id)
        assertEquals("Games (renamed)", saved.name)
    }

    @Test
    fun `deleting an existing category removes it`() {
        store().categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 60, packages = emptySet()),
        )

        val activity = activity(id = "games-id")
        clickButton(activity, "Delete category")
        answerLatestDialog(android.content.DialogInterface.BUTTON_POSITIVE)

        assertTrue(store().categories.isEmpty())
    }

    @Test
    fun `cancelling the delete confirmation keeps the category`() {
        store().categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 60, packages = emptySet()),
        )

        val activity = activity(id = "games-id")
        clickButton(activity, "Delete category")

        assertEquals("nothing is deleted before the question is answered", 1, store().categories.size)
        answerLatestDialog(android.content.DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, store().categories.size)
    }

    @Test
    fun `a fresh new-category screen offers no delete button`() {
        val activity = activity()

        assertNull(findButton(activity, "Delete category"))
    }

    // ---------- the budget as a ContextRule ----------

    @Test
    fun `saving a category writes a SetBudget rule at its well-known id`() {
        val activity = activity()
        nameField(activity)?.setText("Games")

        clickButton(activity, "Save")

        val saved = store().categories.single()
        val rule = rules().rules.singleOrNull { it.id == "migrated-category-budget-${saved.id}" }
        assertEquals(Condition.Always, rule?.condition)
        assertEquals(RuleEffect.SetBudget(WeeklyBudget.flat(Defaults.CATEGORY_LIMIT)), rule?.effect)
        assertEquals(RuleTarget.Categories(setOf(CategoryId(saved.id))), rule?.target)
    }

    @Test
    fun `the stored category's own limitMinutes is no longer the source of truth`() {
        val activity = activity()
        nameField(activity)?.setText("Games")

        clickButton(activity, "Save")

        assertEquals(0, store().categories.single().limitMinutes)
    }

    @Test
    fun `editing loads the limit from its rule rather than a stale stored field`() {
        store().categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 999, packages = emptySet()),
        )
        rules().upsert(
            ContextRule(
                id = "migrated-category-budget-games-id",
                name = "Games",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.SetBudget(WeeklyBudget.flat(30.minutes)),
                target = RuleTarget.Categories(setOf(CategoryId("games-id"))),
            ),
        )

        // Re-saving without touching the slider preserves whatever onCreate
        // loaded into it — the only way to observe that private state from
        // outside without exposing it just for this test.
        val activity = activity(id = "games-id")
        clickButton(activity, "Save")

        val rule = rules().rules.single { it.id == "migrated-category-budget-games-id" }
        assertEquals(RuleEffect.SetBudget(WeeklyBudget.flat(30.minutes)), rule.effect)
    }

    @Test
    fun `deleting a category also deletes its budget rule`() {
        store().categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 60, packages = emptySet()),
        )
        rules().upsert(
            ContextRule(
                id = "migrated-category-budget-games-id",
                name = "Games",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.SetBudget(WeeklyBudget.flat(60.minutes)),
                target = RuleTarget.Categories(setOf(CategoryId("games-id"))),
            ),
        )

        val activity = activity(id = "games-id")
        clickButton(activity, "Delete category")
        answerLatestDialog(android.content.DialogInterface.BUTTON_POSITIVE)

        assertTrue(rules().rules.none { it.id == "migrated-category-budget-games-id" })
    }

    private fun nameField(activity: CategoryEditActivity): EditText? =
        findAll(activity.window.decorView) { it is EditText }.filterIsInstance<EditText>().firstOrNull()

    private fun answerLatestDialog(which: Int) {
        // confirmDialog builds an appcompat AlertDialog, which ShadowAlertDialog
        // does not track; ShadowDialog sees every Dialog.
        val dialog = ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
        requireNotNull(dialog) { "expected a delete confirmation" }.getButton(which).performClick()
        // The click dispatches the dialog's listener through a Handler message
        // on the main looper — performClick() returns before onConfirm() runs,
        // so the assertions must wait for the queue to drain first.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickButton(activity: CategoryEditActivity, text: String) {
        requireNotNull(findButton(activity, text)) { "no button titled \"$text\" was rendered" }.performClick()
    }

    private fun findButton(activity: CategoryEditActivity, text: String): Button? =
        findAll(activity.window.decorView) { it is Button && it.text.toString() == text }
            .filterIsInstance<Button>()
            .firstOrNull()

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
