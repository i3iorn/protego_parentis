package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.sync.LocalDeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.SyncRole
import dev.schrammel.protego_parentis.sync.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #89: standing answers to requests were the one rule kind [RulesActivity]
 * only ever offered on the device being edited, even though every store
 * underneath it (replication, push/pull on enrolment, sync receipt) already
 * treated it like any other rule. Answering a request in the moment is still
 * own-device-only — that row is pinned separately in [RuleDestinationsTest]'s
 * sibling coverage — but managing *when* a standing yes fires is not.
 */
@RunWith(RobolectricTestRunner::class)
class AutoApprovalRemoteTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    private fun rulesActivity(): RulesActivity =
        Robolectric.buildActivity(RulesActivity::class.java, RulesActivity.intent(context)).setup().get()

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        RosterStore(context).forgetAll()
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    @Test
    fun `editing a child's rules remotely, the requests row is absent but auto-approval is offered`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val activity = rulesActivity()

        assertNull(findRow(activity.window.decorView, activity.getString(R.string.rules_row_requests)))
        val autoRow = requireNotNull(findRow(activity.window.decorView, activity.getString(R.string.rules_row_auto))) {
            "the auto-approval row should be offered for a remote target"
        }

        autoRow.performClick()

        assertEquals(
            AutoApprovalActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `on the managed phone itself, both requests and auto-approval are offered`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")
        val activity = rulesActivity()

        assertNotNull(findRow(activity.window.decorView, activity.getString(R.string.rules_row_requests)))
        assertNotNull(findRow(activity.window.decorView, activity.getString(R.string.rules_row_auto)))
    }

    private fun findRow(view: View, title: String): View? {
        if (view is TextView && view.text == title) {
            var candidate = view.parent as? View
            while (candidate != null && !candidate.isClickable) candidate = candidate.parent as? View
            return candidate
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findRow(view.getChildAt(i), title)?.let { return it }
            }
        }
        return null
    }
}
