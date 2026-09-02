package dev.schrammel.protego_parentis.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [versionsDiffer] — what counts as worth telling the family about.
 *
 * The interesting cases are the silences. A device enrolled before versions were
 * announced reports nothing, and reading that as a mismatch would make every
 * family see a warning during the rollout that introduced the warning.
 */
class AppVersionTest {

    @Test
    fun `different versions differ`() {
        assertTrue(versionsDiffer("1.1.1", "1.1.0"))
    }

    @Test
    fun `the same version does not`() {
        assertFalse(versionsDiffer("1.1.1", "1.1.1"))
    }

    @Test
    fun `a device that has never announced is not a mismatch`() {
        assertFalse(versionsDiffer("1.1.1", null))
    }

    @Test
    fun `an empty reported version is not a mismatch`() {
        assertFalse(versionsDiffer("1.1.1", ""))
    }

    /** Our own version unreadable means we cannot compare, not that we differ. */
    @Test
    fun `an unknown local version is not a mismatch`() {
        assertFalse(versionsDiffer("", "1.1.0"))
        assertFalse(versionsDiffer("", null))
    }
}
