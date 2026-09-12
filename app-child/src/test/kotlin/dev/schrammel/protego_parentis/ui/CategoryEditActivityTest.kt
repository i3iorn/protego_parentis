package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import dev.schrammel.protego_parentis.R
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

/**
 * [CategoryEditActivity]'s create/edit/delete round trip through
 * [LocalPolicyStore.categories] — untested before #161.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryEditActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun store() = LocalPolicyStore(context)

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

        assertTrue(store().categories.isEmpty())
    }

    @Test
    fun `a fresh new-category screen offers no delete button`() {
        val activity = activity()

        assertNull(findButton(activity, "Delete category"))
    }

    private fun nameField(activity: CategoryEditActivity): EditText? =
        findAll(activity.window.decorView) { it is EditText }.filterIsInstance<EditText>().firstOrNull()

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
