package dev.schrammel.protego_parentis.ui

import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.ParentSession
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.PendingDevice
import dev.schrammel.protego_parentis.sync.core.SyncRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Instant

/**
 * A second managed phone is admitted on each parent's own approval: the
 * phone that approves it trusts it at once, with no proposal left waiting
 * for the rest of the family.
 */
@RunWith(RobolectricTestRunner::class)
class FamilyDevicesActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val childId = DeviceId("SECONDCHILD")

    @Before
    fun setUp() {
        RoleStore(context).choose(DeviceRole.PARENT)
        ParentSession.unlock()
    }

    @After
    fun tearDown() {
        ParentSession.lock()
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    @Test
    fun `approving a second managed phone trusts it on this phone straight away`() {
        RosterStore(context).notice(pending(SyncRole.CHILD))
        val activity = Robolectric.buildActivity(FamilyDevicesActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        val approve = requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.devices_approve))) {
            "a pending child phone should offer Approve"
        }
        approve.performClick()
        clickDialog(DialogInterface.BUTTON_POSITIVE)

        val roster = RosterStore(context)
        assertEquals(listOf(SyncRole.CHILD), roster.devices.filter { it.id == childId }.map { it.role })
        assertTrue(roster.pending.none { it.id == childId })
    }

    @Test
    fun `a candidate that re-announced as a parent is not enrolled by the child dialog`() {
        val roster = RosterStore(context)
        roster.notice(pending(SyncRole.CHILD))
        val activity = Robolectric.buildActivity(FamilyDevicesActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.devices_approve))).performClick()
        // Announces again as a parent while the child dialog is open.
        roster.notice(pending(SyncRole.PARENT))
        clickDialog(DialogInterface.BUTTON_POSITIVE)

        assertTrue(RosterStore(context).devices.none { it.id == childId })
    }

    private fun pending(role: SyncRole) = PendingDevice(
        id = childId,
        name = "Second phone",
        role = role,
        publicKey = "key",
        firstHeardAt = Instant.ofEpochSecond(1_700_000_000),
    )

    private fun clickDialog(which: Int) {
        // appcompat AlertDialogs aren't tracked by ShadowAlertDialog; ShadowDialog sees every Dialog.
        val dialog = ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
        requireNotNull(dialog) { "expected the approval dialog" }.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }
}
