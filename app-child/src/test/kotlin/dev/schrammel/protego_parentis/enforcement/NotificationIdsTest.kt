package dev.schrammel.protego_parentis.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Notification ids are a shared resource: [android.app.NotificationManager]
 * replaces by id, so two posters sharing a number silently overwrite each
 * other. This happened three times before every id lived in [NotificationIds];
 * the last (goal-earned and update-available both on 22) got past a version of
 * this test that listed the ids by hand and left RequestNotifier's out.
 */
class NotificationIdsTest {

    /** Read reflectively so an id added to the registry cannot be left out of the check. */
    private val registered: Map<String, Int> =
        NotificationIds::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

    @Test
    fun `every registered id is distinct`() {
        val shared = registered.entries
            .groupBy({ it.value }, { it.key })
            .filterValues { it.size > 1 }

        assertEquals("notification ids posted under more than one name", emptyMap<Int, List<String>>(), shared)
    }

    @Test
    fun `the registry is actually read`() {
        // Guards the reflection above: if it matched nothing, the distinctness
        // check would pass vacuously.
        assertTrue(registered.size >= 24)
        assertEquals(NotificationIds.GOAL_EARNED, registered["GOAL_EARNED"])
    }
}
