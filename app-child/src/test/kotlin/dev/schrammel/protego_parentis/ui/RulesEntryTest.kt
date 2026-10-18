package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #43, rules-UX phase A: "Rules" opens the rules list ([ContextRulesActivity])
 * itself, never the controls hub, and the picker appears only once a second
 * phone is enrolled.
 */
@RunWith(RobolectricTestRunner::class)
class RulesEntryTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val activity: Activity get() = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    @After
    fun tearDown() {
        RosterStore(context).forgetAll()
        LocalDeviceId.useInTests(null)
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    @Test
    fun `with no children, opens the rules list with no target extra`() {
        RulesEntry.launch(activity)

        assertEquals(
            ContextRulesActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun `with one child, opens the rules list directly - never the picker`() {
        enrolChild("CHILD1")

        RulesEntry.launch(activity)

        assertEquals(
            ContextRulesActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun `on the child's own phone, opens the rules list`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")

        RulesEntry.launch(activity)

        assertEquals(
            ContextRulesActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun `with two children, launches the picker instead of guessing`() {
        enrolChild("CHILD1")
        enrolChild("CHILD2")

        RulesEntry.launch(activity)

        assertEquals(
            RulesetPickerActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun `the picker opens the rules list for the chosen child`() {
        enrolChild("CHILD1")
        enrolChild("CHILD2")
        RoleStore(context).choose(DeviceRole.PARENT)

        val picker = Robolectric.buildActivity(
            RulesetPickerActivity::class.java,
            RulesetPickerActivity.intent(context),
        ).setup().get()

        clickRow(picker, "CHILD1")

        assertEquals(
            ContextRulesActivity::class.java.name,
            shadowOf(picker).nextStartedActivity.component?.className,
        )
    }

    private fun clickRow(activity: Activity, title: String) {
        val row = requireNotNull(findClickableRow(activity.window.decorView, title)) {
            "no row titled \"$title\" was rendered"
        }
        row.performClick()
    }

    private fun findClickableRow(view: View, title: String): View? {
        if (view is TextView && view.text == title) {
            var candidate = view.parent as? View
            while (candidate != null && !candidate.isClickable) candidate = candidate.parent as? View
            return candidate
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findClickableRow(view.getChildAt(index), title)?.let { return it }
            }
        }
        return null
    }
}
