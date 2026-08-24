package dev.schrammel.dementor_watch.enforcement

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which notifications may never be silenced.
 *
 * The three here are the ones a person with something to hide would most want
 * gone: the notice that somebody looked for the child, the notice that somebody
 * locked their phone, and the call for help. If a later change makes any of them
 * optional, that should fail a test rather than ship quietly.
 */
class NotificationKindTest {

    @Test
    fun `being watched, being locked, and asking for help are never silenced`() {
        assertEquals(
            setOf(
                NotificationKind.LOCATED,
                NotificationKind.LOCKED,
                NotificationKind.EMERGENCY,
            ),
            NotificationKind.entries.filterNot { it.optional }.toSet(),
        )
    }

    @Test
    fun `the ordinary ones are a preference`() {
        assertEquals(
            "a new notification should be silenceable unless it tells somebody they are being watched",
            NotificationKind.entries.size - 3,
            NotificationKind.entries.count { it.optional },
        )
    }
}
