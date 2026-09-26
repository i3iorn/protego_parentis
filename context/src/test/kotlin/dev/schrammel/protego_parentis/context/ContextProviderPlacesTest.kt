package dev.schrammel.protego_parentis.context

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [ContextProvider.placesIfKnown] tells "outside every place" apart from "no
 * idea where the phone is", which the arrival announcements depend on: a
 * phone with no recent fix used to read as having left home.
 */
@RunWith(RobolectricTestRunner::class)
class ContextProviderPlacesTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val home = Place(PlaceId("home"), "Home", 59.3293, 18.0686, 150)

    private fun grantLocation() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun fixAt(latitude: Double, longitude: Double, ageMillis: Long = 0L) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 10f
            time = System.currentTimeMillis() - ageMillis
        }
        shadowOf(app.getSystemService(LocationManager::class.java)).apply {
            setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            setLastKnownLocation(LocationManager.GPS_PROVIDER, location)
        }
    }

    @Test
    fun `with no fix at all the answer is unknown, not outside`() {
        grantLocation()

        assertNull(ContextProvider(app).placesIfKnown(listOf(home)))
    }

    @Test
    fun `a fix older than the provider trusts is unknown too`() {
        grantLocation()
        fixAt(home.latitude, home.longitude, ageMillis = 2 * 60 * 60 * 1000L)

        assertNull(ContextProvider(app).placesIfKnown(listOf(home)))
    }

    @Test
    fun `a fresh fix inside a place says so`() {
        grantLocation()
        fixAt(home.latitude, home.longitude)

        assertEquals(setOf(home.id), ContextProvider(app).placesIfKnown(listOf(home)))
    }

    @Test
    fun `a fresh fix elsewhere is a definite outside`() {
        grantLocation()
        fixAt(57.7089, 11.9746)

        assertEquals(emptySet<PlaceId>(), ContextProvider(app).placesIfKnown(listOf(home)))
    }

    @Test
    fun `without the location permission the answer is unknown`() {
        assertNull(ContextProvider(app).placesIfKnown(listOf(home)))
    }
}
