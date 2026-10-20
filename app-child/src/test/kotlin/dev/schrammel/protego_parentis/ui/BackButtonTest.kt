package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.security.RoleStore
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

/**
 * Every page offers a way back, and the roots do not.
 *
 * The gesture bar covers the whole left edge, which is invisible to somebody
 * who arrived by tapping a row, and a screen whose only way out is a gesture it
 * does not know reads as a dead end. A root is the other case: there is nothing
 * behind it, so a back affordance there would only offer to leave the app.
 */
@RunWith(RobolectricTestRunner::class)
class BackButtonTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        LocalDeviceId.useInTests("THISPHONE")
        ParentSession.unlock()
    }

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        ParentSession.lock()
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    @Test
    fun `the child's dashboard is a root and offers nothing to go back to`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val activity = build(HomeActivity::class.java)

        assertFalse("a root has nothing behind it", hasBackRow(activity))
    }

    @Test
    fun `the parent's dashboard is a root and offers nothing to go back to`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val activity = build(ParentHomeActivity::class.java)

        assertFalse("a root has nothing behind it", hasBackRow(activity))
    }

    @Test
    fun `a screen below the parent's dashboard offers a way back`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val activity = build(ParentInboxActivity::class.java)

        assertTrue(
            "a screen below a dashboard must offer a way back",
            hasBackRow(activity),
        )
    }

    @Test
    fun `a screen below the child's dashboard offers a way back`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val activity = build(CategoriesActivity::class.java)

        assertTrue(
            "a screen below a dashboard must offer a way back",
            hasBackRow(activity),
        )
    }

    @Test
    fun `the chat screen offers a way back even though it lays out its own root`() {
        // The chat composer has to stay pinned to the bottom, so this screen
        // cannot use the scrolling column the other screens build on, and its
        // back affordance has to be added by hand.
        RoleStore(context).choose(DeviceRole.CHILD)
        val activity = build(ChatActivity::class.java)

        assertTrue(
            "the chat screen must offer a way back",
            hasBackRow(activity),
        )
    }

    @Test
    fun `the emergency screen offers a way back`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val activity = build(SosActivity::class.java)

        assertTrue(
            "the emergency screen must offer a way back",
            hasBackRow(activity),
        )
    }

    @Test
    fun `the header puts the name on the left and the way back on the right`() {
        // One row, not two: the name, then the space between them, then the
        // way back. The space is there for whatever a screen wants to put
        // beside its name.
        RoleStore(context).choose(DeviceRole.CHILD)
        val activity = build(CategoriesActivity::class.java)

        val header = requireNotNull(backButtonOf(activity)).parent as ViewGroup
        val children = (0 until header.childCount).map { header.getChildAt(it) }

        assertEquals(
            "the screen's name comes first, on the left",
            activity.getString(R.string.rules_categories_header),
            (children.first() as TextView).text.toString(),
        )
        assertEquals(
            "the way back comes last, on the right",
            activity.getString(R.string.back_button_description),
            children.last().contentDescription,
        )
    }

    private fun build(screen: Class<out Activity>): Activity =
        Robolectric.buildActivity(screen, Intent(context, screen)).setup().get()

    /** Whether [activity] carries the back affordance, by what it says to a reader. */
    private fun hasBackRow(activity: Activity): Boolean = backButtonOf(activity) != null

    private fun backButtonOf(activity: Activity): View? {
        val description = activity.getString(R.string.back_button_description)

        fun walk(view: View): View? {
            if (view.contentDescription == description) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))?.let { return it }
            }
            return null
        }

        return walk(activity.window.decorView)
    }
}
