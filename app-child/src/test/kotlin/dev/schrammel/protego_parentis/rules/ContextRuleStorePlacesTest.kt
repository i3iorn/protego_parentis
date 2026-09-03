package dev.schrammel.protego_parentis.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #56: raising [Place.MINIMUM_RADIUS_METRES] must not drop a place saved
 * under an earlier, smaller floor — [ContextRuleStore.decodePlaces] clamps
 * up instead of letting [Place]'s constructor reject it, and
 * [ContextRuleStore.clampedPlaceIds] is how a screen learns which places
 * that happened to.
 */
@RunWith(RobolectricTestRunner::class)
class ContextRuleStorePlacesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = ContextRuleStore(context)

    /** Simulates a place saved before this milestone, bypassing Place's own validation. */
    private fun writeLegacyPlace(id: String, radius: Int) {
        val prefs = context.getSharedPreferences("context_rules", Context.MODE_PRIVATE)
        val places = JSONArray().put(
            JSONObject().apply {
                put("id", id)
                put("name", "Grandma's")
                put("lat", 51.5)
                put("lon", -0.1)
                put("radius", radius)
            },
        )
        prefs.edit(commit = true) { putString("places", places.toString()) }
    }

    @Test
    fun `a place saved under an earlier, smaller floor is clamped up rather than dropped`() {
        writeLegacyPlace("p1", radius = 50)

        val place = store().places.singleOrNull()

        assertEquals(Place.MINIMUM_RADIUS_METRES, place?.radiusMetres)
    }

    @Test
    fun `a clamped place is flagged`() {
        writeLegacyPlace("p1", radius = 50)

        assertEquals(setOf(PlaceId("p1")), store().clampedPlaceIds)
    }

    @Test
    fun `a place already at or above the floor is not flagged`() {
        val place = Place(
            id = PlaceId("p2"),
            name = "Home",
            latitude = 1.0,
            longitude = 1.0,
            radiusMetres = Place.MINIMUM_RADIUS_METRES,
        )
        store().upsertPlace(place)

        assertTrue(store().clampedPlaceIds.isEmpty())
    }

    @Test
    fun `resizing a clamped place above the floor clears the flag`() {
        writeLegacyPlace("p1", radius = 50)
        val place = store().places.single()

        store().upsertPlace(place.copy(radiusMetres = 200))

        assertTrue(store().clampedPlaceIds.isEmpty())
    }
}
