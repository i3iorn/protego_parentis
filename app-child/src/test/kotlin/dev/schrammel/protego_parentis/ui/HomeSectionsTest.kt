package dev.schrammel.protego_parentis.ui

import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.FeatureFlags
import dev.schrammel.protego_parentis.security.DeviceRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Both home screens used to be designed separately, with "Family" naming a
 * different set of rows depending which one you read it on (#78). These pin
 * the one shape [homeSections] now gives both: same group labels, same group
 * order, and only the rows inside differing by role.
 *
 * [FeatureFlags] reads real `SharedPreferences`, so this needs Robolectric —
 * unlike [SettingsRowsTest], which took no dependency on Android at all.
 */
@RunWith(RobolectricTestRunner::class)
class HomeSectionsTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun allOn() = FeatureFlags(context).also { flags ->
        Feature.entries.forEach { flags.setEnabled(it, true) }
    }

    @After
    fun tearDown() {
        Feature.entries.forEach { FeatureFlags(context).setEnabled(it, true) }
    }

    private fun labelsFor(role: DeviceRole) = homeSections(role, allOn()::isEnabled).map { it.label }

    /**
     * The two home screens are read by two different people, so they say
     * different things: a child's menu is about the phone in their hand, a
     * parent's about the child they are standing in for.
     */
    @Test
    fun `each role's menu is grouped around what its screens are about`() {
        assertEquals(
            listOf(
                R.string.home_group_own_time,
                R.string.home_group_own_limits,
                R.string.home_group_own_family,
                R.string.home_group_this_phone,
            ),
            labelsFor(DeviceRole.CHILD),
        )
        assertEquals(
            listOf(
                null,
                R.string.home_group_child_time,
                R.string.home_group_child_limits,
                R.string.home_group_child_family,
                R.string.home_group_this_phone,
            ),
            labelsFor(DeviceRole.PARENT),
        )
    }

    /**
     * The unlabelled group is whatever this phone was opened to do - on a
     * parent's phone, the two things they can do to theirs in the moment.
     */
    @Test
    fun `the parent's unlabelled group is the actions they can take right now`() {
        val first = homeSections(DeviceRole.PARENT, allOn()::isEnabled).first()
        assertEquals(null, first.label)
        assertEquals(
            listOf(HomeDestination.LOCK_NOW, HomeDestination.LOCATE),
            first.rows.map { it.destination },
        )
    }

    /**
     * A screen that reads *this device's* own state is only ever offered on
     * the device it describes — see [homeSections]' own doc comment for why
     * [HomeDestination.PRIVACY] and [HomeDestination.THE_OTHER_PHONE] each stay
     * on one side.
     */
    @Test
    fun `device-specific rows never cross to the other role`() {
        val childDestinations = destinationsFor(DeviceRole.CHILD)
        val parentDestinations = destinationsFor(DeviceRole.PARENT)

        assertTrue(HomeDestination.PRIVACY in childDestinations)
        assertFalse(HomeDestination.PRIVACY in parentDestinations)

        assertTrue(HomeDestination.THE_OTHER_PHONE in parentDestinations)
        assertFalse(HomeDestination.THE_OTHER_PHONE in childDestinations)

        assertTrue(HomeDestination.LOCK_NOW in parentDestinations)
        assertFalse(HomeDestination.LOCK_NOW in childDestinations)
    }

    /** The shared family record, read from either phone — see the same doc comment. */
    @Test
    fun `audit and settings appear on both`() {
        assertTrue(HomeDestination.AUDIT in destinationsFor(DeviceRole.CHILD))
        assertTrue(HomeDestination.AUDIT in destinationsFor(DeviceRole.PARENT))
        assertTrue(HomeDestination.SETTINGS in destinationsFor(DeviceRole.CHILD))
        assertTrue(HomeDestination.SETTINGS in destinationsFor(DeviceRole.PARENT))
    }

    @Test
    fun `a feature switched off drops its row on both roles`() {
        val off = FeatureFlags(context).apply {
            setEnabled(Feature.REQUESTS, false)
            setEnabled(Feature.CHAT, false)
        }

        assertFalse(HomeDestination.REQUESTS in destinations(DeviceRole.CHILD, off))
        assertFalse(HomeDestination.CHAT in destinations(DeviceRole.CHILD, off))
        assertFalse(HomeDestination.INBOX in destinations(DeviceRole.PARENT, off))
        assertFalse(HomeDestination.CHAT in destinations(DeviceRole.PARENT, off))
    }

    @Test
    fun `no section is offered without rows in it`() {
        val off = FeatureFlags(context)
        DeviceRole.entries.forEach { role ->
            homeSections(role, off::isEnabled).forEach { section ->
                assertTrue("$role has an empty section", section.rows.isNotEmpty())
            }
        }
    }

    @Test
    fun `every row carries a title and a detail`() {
        DeviceRole.entries.forEach { role ->
            homeSections(role, allOn()::isEnabled).flatMap { it.rows }.forEach { entry ->
                assertTrue("${entry.destination} has no title", entry.title != 0)
                assertTrue("${entry.destination} has no detail", entry.detail != 0)
            }
        }
    }

    /**
     * "Lock now" and "Locate" run a local action rather than opening a screen,
     * so the row carries no chevron — see [HomeDestination.isAction].
     */
    @Test
    fun `only lock and locate are actions, everything else is a screen`() {
        assertTrue(HomeDestination.LOCK_NOW.isAction)
        assertTrue(HomeDestination.LOCATE.isAction)

        HomeDestination.entries
            .filter { it != HomeDestination.LOCK_NOW && it != HomeDestination.LOCATE }
            .forEach { assertFalse("$it should be a screen, not an action", it.isAction) }
    }

    /**
     * The dashboard carries the two things a child comes here to do - ask for
     * more time, see their week - so the menu does not also list them. The
     * rules themselves are on the dashboard too, under their own row.
     */
    @Test
    fun `the menu leaves out the rows the dashboard carries`() {
        val menu = homeSections(DeviceRole.CHILD, allOn()::isEnabled)
            .flatMap { it.rows }
            .map { it.destination }

        assertFalse("ask for more time stays on the dashboard", HomeDestination.REQUESTS in menu)
        assertFalse("see my week stays on the dashboard", HomeDestination.WEEK in menu)
    }

    @Test
    fun `the parent's menu keeps everything its dashboard no longer lists`() {
        val menu = homeSections(DeviceRole.PARENT, allOn()::isEnabled)
            .flatMap { it.rows }
            .map { it.destination }

        assertTrue("the inbox is in the menu", HomeDestination.INBOX in menu)
        assertTrue("locking the phone now is in the menu", HomeDestination.LOCK_NOW in menu)
        assertTrue("the settings are in the menu", HomeDestination.SETTINGS in menu)
    }

    /**
     * The controls that used to sit behind a "More controls" hub are in the
     * menu now. The hub was a level with no meaning of its own between the
     * reader and the thing they came to change; a destination belongs in a
     * menu, and the dashboard is where the menu lives.
     */
    @Test
    fun `the controls the hub used to hold are in the menu`() {
        val menu = homeSections(DeviceRole.CHILD) { true }
            .flatMap { it.rows }
            .map { it.destination }

        listOf(
            HomeDestination.CATEGORIES,
            HomeDestination.APP_LIMITS,
            HomeDestination.APP_CONTROL,
            HomeDestination.WEB_FILTER,
            HomeDestination.ARRIVALS,
            HomeDestination.NIGHTSCOUT,
            HomeDestination.GOAL,
            HomeDestination.BONUS,
            HomeDestination.REQUEST_INBOX,
            HomeDestination.AUTO_APPROVE,
        ).forEach { assertTrue("$it must be in the menu", it in menu) }
    }

    /**
     * A parent's phone holds no rules of its own, so the controls are all about
     * a child's phone - and the ones that only ever meant anything on this
     * device (the web filter) are not among them.
     */
    @Test
    fun `the parent's menu carries the child's controls and not this phone's`() {
        val menu = homeSections(DeviceRole.PARENT) { true }
            .flatMap { it.rows }
            .map { it.destination }

        assertTrue(HomeDestination.CATEGORIES in menu)
        assertTrue(HomeDestination.PLACES in menu)
        assertTrue(HomeDestination.BONUS in menu)
        assertFalse(HomeDestination.WEB_FILTER in menu)
        assertFalse(HomeDestination.REQUEST_INBOX in menu)
    }

    private fun destinationsFor(role: DeviceRole) = destinations(role, allOn())

    private fun destinations(role: DeviceRole, flags: FeatureFlags) =
        homeSections(role, flags::isEnabled).flatMap { it.rows }.map { it.destination }


}
