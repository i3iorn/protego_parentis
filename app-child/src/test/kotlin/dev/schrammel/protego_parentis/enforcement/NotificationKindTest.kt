package dev.schrammel.protego_parentis.enforcement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which notifications may never be silenced.
 *
 * Three are the ones a person with something to hide would most want gone: the
 * notice that somebody looked for the child, the notice that somebody locked
 * their phone, and the call for help. The other three — a device joining the
 * family, one leaving it, and the family ending outright — are not about being
 * watched; each is a trust boundary changing, most often with nobody looking,
 * which is exactly why none of them can be the one thing left to notice by
 * accident. If a later change makes any of these six optional, that should fail
 * a test rather than ship quietly.
 */
class NotificationKindTest {

    @Test
    fun `being watched, being locked, asking for help, and family membership changing are never silenced`() {
        assertEquals(
            setOf(
                NotificationKind.LOCATED,
                NotificationKind.LOCKED,
                NotificationKind.EMERGENCY,
                NotificationKind.DEVICE_ADDED,
                NotificationKind.DEVICE_REMOVED,
                NotificationKind.FAMILY_DISBANDED,
            ),
            NotificationKind.entries.filterNot { it.optional }.toSet(),
        )
    }

    @Test
    fun `the ordinary ones are a preference`() {
        assertEquals(
            "a new notification should be silenceable unless it tells somebody they are being " +
                "watched, or that the family's trust boundary just changed",
            NotificationKind.entries.size - 6,
            NotificationKind.entries.count { it.optional },
        )
    }
}
