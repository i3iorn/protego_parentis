package dev.schrammel.protego_parentis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceDwellTest {

    private val school = PlaceId("school")
    private val home = PlaceId("home")
    private val known = setOf(school, home)
    private val minute = PlaceDwell.DWELL_MILLIS

    private fun PlaceDwell.read(atMillis: Long, vararg inside: String) =
        advance(PlaceReading(inside.mapTo(mutableSetOf(), ::PlaceId), atMillis), known)

    private val outsideEverywhere = PlaceDwell().read(0)

    @Test
    fun `the first reading is taken as it is`() {
        val atHome = PlaceDwell().read(0, "home")

        assertEquals(setOf(home), atHome.confirmed)
        assertFalse(atHome.awaitingConfirmation)
    }

    @Test
    fun `driving past a place is not an arrival`() {
        val passed = outsideEverywhere
            .read(10_000, "school")
            .read(40_000)
            .read(100_000)

        assertEquals(emptySet<PlaceId>(), passed.confirmed)
        assertFalse(passed.awaitingConfirmation)
    }

    @Test
    fun `two fixes inside a minute apart are an arrival`() {
        val first = outsideEverywhere.read(10_000, "school")
        assertEquals(emptySet<PlaceId>(), first.confirmed)
        assertTrue(first.awaitingConfirmation)

        val second = first.read(10_000 + minute, "school")
        assertEquals(setOf(school), second.confirmed)
        assertFalse(second.awaitingConfirmation)
    }

    @Test
    fun `the same fix read on two checks is not a second reading`() {
        val stale = outsideEverywhere
            .read(10_000, "school")
            .read(10_000, "school")
            .read(10_000, "school")

        assertEquals(emptySet<PlaceId>(), stale.confirmed)
        assertTrue(stale.awaitingConfirmation)
    }

    @Test
    fun `a second fix less than a minute later is not enough yet`() {
        val soon = outsideEverywhere
            .read(10_000, "school")
            .read(10_000 + minute - 1, "school")

        assertEquals(emptySet<PlaceId>(), soon.confirmed)

        val later = soon.read(10_000 + minute, "school")
        assertEquals(setOf(school), later.confirmed)
    }

    @Test
    fun `an older fix arriving late does not restart or complete the wait`() {
        val late = outsideEverywhere
            .read(10_000, "school")
            .read(5_000, "school")
            .read(10_000 + minute - 1, "school")

        assertEquals(emptySet<PlaceId>(), late.confirmed)
    }

    @Test
    fun `leaving also takes two fixes outside a minute apart`() {
        val atSchool = PlaceDwell().read(0, "school")

        val jump = atSchool.read(10_000)
        assertEquals(setOf(school), jump.confirmed)

        val back = jump.read(30_000, "school")
        assertEquals(setOf(school), back.confirmed)
        assertFalse(back.awaitingConfirmation)

        val gone = back.read(40_000).read(40_000 + minute)
        assertEquals(emptySet<PlaceId>(), gone.confirmed)
    }

    @Test
    fun `places are confirmed independently`() {
        val state = PlaceDwell().read(0, "home")
            .read(10_000, "school")
            .read(10_000 + minute, "school")

        assertEquals(setOf(school), state.confirmed)
    }

    @Test
    fun `a place that is no longer saved is forgotten`() {
        val state = PlaceDwell().read(0, "school", "home")
            .advance(PlaceReading(emptySet(), 10_000), setOf(home))

        assertEquals(setOf(home), state.confirmed)
        assertEquals(setOf(home), state.pendingSince.keys)
    }
}
