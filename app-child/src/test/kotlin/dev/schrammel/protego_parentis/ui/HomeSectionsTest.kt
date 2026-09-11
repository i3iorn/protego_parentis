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

    @Test
    fun `both roles see the same group labels in the same order`() {
        assertEquals(listOf(null) + REST, labelsFor(DeviceRole.CHILD))
        assertEquals(listOf(null) + REST, labelsFor(DeviceRole.PARENT))
    }

    @Test
    fun `the first group is unlabelled on both`() {
        assertEquals(null, homeSections(DeviceRole.CHILD, allOn()::isEnabled).first().label)
        assertEquals(null, homeSections(DeviceRole.PARENT, allOn()::isEnabled).first().label)
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

        assertTrue(HomeDestination.WEEK in childDestinations)
        assertFalse(HomeDestination.WEEK in parentDestinations)

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

    private fun destinationsFor(role: DeviceRole) = destinations(role, allOn())

    private fun destinations(role: DeviceRole, flags: FeatureFlags) =
        homeSections(role, flags::isEnabled).flatMap { it.rows }.map { it.destination }

    private companion object {
        val REST = listOf(
            R.string.home_group_rules,
            R.string.home_group_family,
            R.string.home_group_openness,
            R.string.home_group_settings,
        )
    }
}
