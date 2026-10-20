package dev.schrammel.protego_parentis.ui

import android.app.Activity
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #89: standing answers to requests were the one rule kind only ever offered
 * on the device being edited, even though every store underneath it
 * (replication, push/pull on enrolment, sync receipt) already treated it like
 * any other rule. Answering a request in the moment is still own-device-only,
 * but managing *when* a standing yes fires is not.
 *
 * Both live in the home screen's menu now, where every other destination
 * lives.
 */
@RunWith(RobolectricTestRunner::class)
class AutoApprovalRemoteTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** The menu's rows are opened from a screen, not from the application. */
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

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

        val destinations = homeMenuSections(DeviceRole.PARENT) { true }
            .flatMap { it.rows }
            .map { it.destination }
        assertFalse(
            "the requests inbox is this phone's own",
            HomeDestination.REQUEST_INBOX in destinations,
        )
        assertTrue(
            "the auto-approval row should be offered for a remote target",
            HomeDestination.AUTO_APPROVE in destinations,
        )

        RulesEntry.launchControl(activity, HomeDestination.AUTO_APPROVE)

        assertEquals(
            AutoApprovalActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `on the managed phone itself, both requests and auto-approval are offered`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")

        val destinations = homeMenuSections(DeviceRole.CHILD) { true }
            .flatMap { it.rows }
            .map { it.destination }
        assertTrue(HomeDestination.REQUEST_INBOX in destinations)
        assertTrue(HomeDestination.AUTO_APPROVE in destinations)
    }
}
