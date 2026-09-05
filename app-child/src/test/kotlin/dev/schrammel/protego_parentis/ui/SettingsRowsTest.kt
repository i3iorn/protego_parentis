package dev.schrammel.protego_parentis.ui

import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each kind of phone is offered in settings (#78).
 *
 * Settings used to live in two screens that had drifted apart — three rows
 * behind More on the managed phone, nine behind Family devices on a parent's.
 * Merging them is only safe if nothing was quietly taken from the managed
 * phone and nothing parent-only quietly appeared on it, which is what these
 * pin. The list is plain data precisely so this can be asked without a device.
 */
class SettingsRowsTest {

    private fun destinationsFor(role: DeviceRole) =
        settingsSections(role).flatMap { it.rows }.map { it.destination }

    @Test
    fun `both phones keep the settings the managed phone always had`() {
        DeviceRole.entries.forEach { role ->
            val destinations = destinationsFor(role)
            assertTrue(
                "$role should still offer notifications",
                SettingsDestination.NOTIFICATIONS in destinations,
            )
            assertTrue(
                "$role should still offer permissions",
                SettingsDestination.PERMISSIONS in destinations,
            )
            assertTrue(
                "$role should still offer the language picker",
                SettingsDestination.LANGUAGE in destinations,
            )
        }
    }

    /**
     * The feature flags put parts of the app away, and the role switch decides
     * which phone is the managed one. Neither belongs to whoever is holding the
     * phone being limited — see [settingsSections].
     */
    @Test
    fun `the managed phone is offered neither the feature flags nor the role switch`() {
        val destinations = destinationsFor(DeviceRole.CHILD)

        assertFalse(SettingsDestination.FEATURES in destinations)
        assertFalse(SettingsDestination.ROLE_SWITCH in destinations)
    }

    @Test
    fun `a parent's phone is offered both`() {
        val destinations = destinationsFor(DeviceRole.PARENT)

        assertTrue(SettingsDestination.FEATURES in destinations)
        assertTrue(SettingsDestination.ROLE_SWITCH in destinations)
    }

    /**
     * An empty group would draw a heading over nothing — [addSection] drops one
     * for that reason, and the list this reads from should not be handing it an
     * empty section in the first place.
     */
    @Test
    fun `no section is offered without rows in it`() {
        DeviceRole.entries.forEach { role ->
            settingsSections(role).forEach { section ->
                assertTrue("$role has an empty section", section.rows.isNotEmpty())
            }
        }
    }

    /** The first group is unlabelled on every screen in this app; this one too. */
    @Test
    fun `the first group is unlabelled and holds this phone's own settings`() {
        DeviceRole.entries.forEach { role ->
            val first = settingsSections(role).first()
            assertNull(first.label)
            assertEquals(
                listOf(
                    SettingsDestination.NOTIFICATIONS,
                    SettingsDestination.PERMISSIONS,
                    SettingsDestination.LANGUAGE,
                    SettingsDestination.SECURITY_AND_CONTROL,
                ),
                first.rows.map { it.destination },
            )
        }
    }

    /** Every row names a real string, not a resource id that happens to compile. */
    @Test
    fun `every row carries a title and a detail`() {
        DeviceRole.entries.forEach { role ->
            settingsSections(role).flatMap { it.rows }.forEach { entry ->
                assertTrue("${entry.destination} has no title", entry.title != 0)
                assertTrue("${entry.destination} has no detail", entry.detail != 0)
            }
        }
    }

    /** Guards the one row whose title is built at render time, not read from here. */
    @Test
    fun `the language row still points at the language string`() {
        val language = settingsSections(DeviceRole.CHILD)
            .flatMap { it.rows }
            .single { it.destination == SettingsDestination.LANGUAGE }

        assertEquals(R.string.home_language, language.title)
    }
}
