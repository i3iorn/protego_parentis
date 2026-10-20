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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Where the controls in the home screen's menu lead.
 *
 * They used to sit behind a hub screen of their own ("More controls"), which
 * was a level with no meaning of its own between the reader and the thing they
 * came to change. They are destinations like any other now, so they are opened
 * the way any other destination is: for this phone on a child's own, and for
 * the child whose rules are being edited on a parent's.
 */
@RunWith(RobolectricTestRunner::class)
class RuleDestinationsTest {

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
    fun `on the managed phone itself, bonus time opens the local screen`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")

        RulesEntry.launchControl(activity, HomeDestination.BONUS)

        assertEquals(
            BonusTimeActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `editing a child's rules remotely, bonus time sends a message instead`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")

        RulesEntry.launchControl(activity, HomeDestination.BONUS)

        assertEquals(
            ParentBonusActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    /**
     * #86: the arrival-announcement toggle only means anything on the device
     * doing the arriving, so it stays own-device-only - but the saved places
     * themselves are the same list Contextual rules already edits remotely,
     * so a remote target gets its own direct door to them instead.
     */
    @Test
    fun `on the managed phone itself, places opens arrival announcements`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")

        RulesEntry.launchControl(activity, HomeDestination.ARRIVALS)

        assertEquals(
            PlaceAlertsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `editing a child's rules remotely, places opens place management directly`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")

        RulesEntry.launchControl(activity, HomeDestination.PLACES)

        assertEquals(
            PlacesActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `the web filter is this phone's own and never a child's`() {
        // The web filter governs browsing on the device it is set up on, so
        // there is nothing to set up on somebody else's phone.
        assertEquals(
            emptyList<HomeDestination>(),
            homeSections(DeviceRole.PARENT) { true }
                .flatMap { it.rows }
                .filter { it.destination == HomeDestination.WEB_FILTER },
        )
        assertEquals(
            listOf(HomeDestination.WEB_FILTER),
            homeSections(DeviceRole.CHILD) { true }
                .flatMap { it.rows }
                .filter { it.destination == HomeDestination.WEB_FILTER }
                .map { it.destination },
        )
    }
}
