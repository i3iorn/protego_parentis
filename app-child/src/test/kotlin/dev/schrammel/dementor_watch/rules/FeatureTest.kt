package dev.schrammel.dementor_watch.rules

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The list of what may be put away is a product promise, not an implementation
 * detail. Adding `optional = false` to something is easy; quietly dropping it from
 * one of the three that carry the promise is just as easy, and nothing else in the
 * build would notice.
 */
class FeatureTest {

    @Test
    fun `the safety floor and the transparency promise cannot be put away`() {
        assertEquals(
            setOf(Feature.EMERGENCY, Feature.HISTORY, Feature.TRANSPARENCY),
            Feature.entries.filterNot { it.optional }.toSet(),
        )
    }

    @Test
    fun `everything else is a family's own choice`() {
        assertEquals(
            "a new part of the app should be offered unless there is a reason not to",
            Feature.entries.size - 3,
            Feature.entries.count { it.optional },
        )
    }
}
