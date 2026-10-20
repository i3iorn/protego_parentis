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

    /**
     * The two ways a request for more time goes - asking, and the standing
     * answers that need no asking - belong next to each other. They are the two
     * sides of one thing, and a reader looking for one should not have to
     * wonder whether the other is in the next group.
     */
    @Test
    fun `the requests and the automatic answers sit next to each other`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")

        val rows = homeSections(DeviceRole.CHILD) { true }.flatMap { it.rows }
        val inbox = rows.indexOfFirst { it.destination == HomeDestination.REQUEST_INBOX }
        val auto = rows.indexOfFirst { it.destination == HomeDestination.AUTO_APPROVE }

        assertTrue("the request inbox must be in the menu", inbox >= 0)
        assertTrue("the automatic answers must be in the menu", auto >= 0)
        assertEquals(
            "the requests and the automatic answers belong side by side",
            inbox + 1,
            auto,
        )
    }

    @Test
    fun `a parent's menu carries the inbox and the automatic answers together`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")

        val rows = homeSections(DeviceRole.PARENT) { true }.flatMap { it.rows }
        val destinations = rows.map { it.destination }
        assertTrue("the inbox is in the menu", HomeDestination.INBOX in destinations)
        assertTrue("the automatic answers are in the menu", HomeDestination.AUTO_APPROVE in destinations)
        assertEquals(
            "the requests and the automatic answers belong side by side",
            rows.indexOfFirst { it.destination == HomeDestination.INBOX } + 1,
            rows.indexOfFirst { it.destination == HomeDestination.AUTO_APPROVE },
        )

        RulesEntry.launchControl(activity, HomeDestination.AUTO_APPROVE)

        assertEquals(
            AutoApprovalActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `on the managed phone itself, both the request inbox and the automatic answers are offered`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")

        val destinations = homeSections(DeviceRole.CHILD) { true }
            .flatMap { it.rows }
            .map { it.destination }
        assertTrue(HomeDestination.REQUEST_INBOX in destinations)
        assertTrue(HomeDestination.AUTO_APPROVE in destinations)
    }
}
