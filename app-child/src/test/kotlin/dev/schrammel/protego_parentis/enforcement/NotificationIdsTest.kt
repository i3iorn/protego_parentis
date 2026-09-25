package dev.schrammel.protego_parentis.enforcement

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification ids are a shared resource: [android.app.NotificationManager]
 * replaces by id, so two posters sharing a number silently overwrite each
 * other. This happened twice (5: goal vs new-app; 6: realtime service vs
 * silent device update) before the ids were centralised — this test pins the
 * invariant that can no longer be seen from the constants alone.
 */
class NotificationIdsTest {

    @Test
    fun `every registered id is unique and outside RequestNotifier's range`() {
        val registered = listOf(
            NotificationIds.ENFORCEMENT_SERVICE,
            NotificationIds.WARNING,
            NotificationIds.REALTIME_SERVICE,
            NotificationIds.GOAL_EARNED,
        )
        assertTrue(
            "duplicate notification id registered",
            registered.toSet().size == registered.size
        )

        // RequestNotifier owns the historical 3-21 block (see its companion);
        // anything registered outside it must stay outside it.
        registered.forEach { id ->
            assertTrue(
                "notification id $id collides with RequestNotifier's 3-21 range",
                id !in 3..21,
            )
        }
    }
}
