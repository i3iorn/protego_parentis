package dev.schrammel.protego_parentis.context

import android.content.Context
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [PlaceWatcher.transitions] — turning "which places is the phone inside now"
 * into arrivals and departures against what was true last time.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceWatcherTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val school = Place(PlaceId("school"), "School", 0.0, 0.0, 100)
    private val home = Place(PlaceId("home"), "Home", 1.0, 1.0, 100)
    private val places = listOf(school, home)

    private fun watcher() = PlaceWatcher(context)

    @Test
    fun `the first call seeds silently, reporting nothing`() {
        val transitions = watcher().transitions(places, insideNow = setOf(school.id))

        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `arriving somewhere new after seeding is reported as an arrival`() {
        val watcher = watcher()
        watcher.transitions(places, insideNow = emptySet())

        val transitions = watcher.transitions(places, insideNow = setOf(school.id))

        assertEquals(listOf(PlaceTransition(school, arrived = true)), transitions)
    }

    @Test
    fun `leaving a place is reported as a departure`() {
        val watcher = watcher()
        watcher.transitions(places, insideNow = setOf(school.id))

        val transitions = watcher.transitions(places, insideNow = emptySet())

        assertEquals(listOf(PlaceTransition(school, arrived = false)), transitions)
    }

    @Test
    fun `staying put reports nothing`() {
        val watcher = watcher()
        watcher.transitions(places, insideNow = setOf(school.id))

        val transitions = watcher.transitions(places, insideNow = setOf(school.id))

        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `arriving at one place while leaving another reports both`() {
        val watcher = watcher()
        watcher.transitions(places, insideNow = setOf(school.id))

        val transitions = watcher.transitions(places, insideNow = setOf(home.id))

        assertEquals(
            setOf(PlaceTransition(home, arrived = true), PlaceTransition(school, arrived = false)),
            transitions.toSet(),
        )
    }

    @Test
    fun `a place removed from the saved list is never reported`() {
        val watcher = watcher()
        watcher.transitions(places, insideNow = emptySet())

        // Inside an id that no longer resolves to a known place (deleted since
        // the last seed) — must be silently dropped, not crash or leak a null.
        val transitions = watcher.transitions(listOf(home), insideNow = setOf(school.id))

        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `reset makes the next call seed again instead of reporting`() {
        val watcher = watcher()
        watcher.transitions(places, insideNow = setOf(school.id))

        watcher.reset()
        val transitions = watcher.transitions(places, insideNow = emptySet())

        assertTrue("a reset watcher must re-seed, not report the loss as a departure", transitions.isEmpty())
    }
}
