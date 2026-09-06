package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.SyncRole
import dev.schrammel.protego_parentis.sync.Vouch
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #85: [dev.schrammel.protego_parentis.sync.SyncCoordinator.publishAppInventoryRequest]
 * and its answer path always existed — nothing anywhere ever called it. A
 * parent picking apps for a remote child's rules just saw an empty list,
 * forever, with no way to explain why or ask again. This pins that a remote
 * target with no cached apps now offers exactly that missing button, and
 * that an own-device picker (which never needs asking) does not.
 */
@RunWith(RobolectricTestRunner::class)
class AppPickerActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    @After
    fun tearDown() {
        RoleStore(context).choose(DeviceRole.CHILD)
        RosterStore(context).forgetAll()
    }

    @Test
    fun `offers to request the list when a remote child has none cached yet`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val activity = Robolectric.buildActivity(
            AppPickerActivity::class.java,
            AppPickerActivity.intent(context, "Pick apps", emptyList(), DeviceId("CHILD1")),
        ).setup().get()

        val button = findButton(activity.window.decorView, activity.getString(R.string.app_picker_request_list))
        button.performClick()
    }

    @Test
    fun `does not offer to request a list for the phone's own apps`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val activity = Robolectric.buildActivity(
            AppPickerActivity::class.java,
            AppPickerActivity.intent(context, "Pick apps", emptyList()),
        ).setup().get()

        assertNull(findButtonOrNull(activity.window.decorView, activity.getString(R.string.app_picker_request_list)))
    }

    private fun findButton(view: View, text: String): Button =
        findButtonOrNull(view, text) ?: error("no button titled \"$text\" was rendered")

    private fun findButtonOrNull(view: View, text: String): Button? {
        if (view is Button && view.text == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButtonOrNull(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }
}
