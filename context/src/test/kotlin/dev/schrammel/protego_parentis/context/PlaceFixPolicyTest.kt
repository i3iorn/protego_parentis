package dev.schrammel.protego_parentis.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The battery budget for fetching our own place fix (#290). */
class PlaceFixPolicyTest {

    private val minute = 60_000L

    @Test
    fun `no fix at all and never asked asks`() {
        assertTrue(PlaceFixPolicy.shouldRequest(fixAgeMillis = null, sinceLastRequestMillis = null))
    }

    @Test
    fun `a recent fix from any app asks for nothing`() {
        assertFalse(PlaceFixPolicy.shouldRequest(fixAgeMillis = 5 * minute, sinceLastRequestMillis = null))
    }

    @Test
    fun `a stale fix asks, but not again within the quarter hour`() {
        assertTrue(PlaceFixPolicy.shouldRequest(fixAgeMillis = 40 * minute, sinceLastRequestMillis = 16 * minute))
        assertFalse(PlaceFixPolicy.shouldRequest(fixAgeMillis = 40 * minute, sinceLastRequestMillis = 2 * minute))
    }

    @Test
    fun `an ask that produced nothing is not retried on every tick`() {
        assertFalse(PlaceFixPolicy.shouldRequest(fixAgeMillis = null, sinceLastRequestMillis = 1 * minute))
    }

    @Test
    fun `while a place is confirming, a fix older than a minute asks again`() {
        assertTrue(PlaceFixPolicy.shouldRequest(fixAgeMillis = 2 * minute, sinceLastRequestMillis = 2 * minute, confirming = true))
        assertFalse(PlaceFixPolicy.shouldRequest(fixAgeMillis = 2 * minute, sinceLastRequestMillis = 2 * minute))
    }

    @Test
    fun `confirming still waits a minute between asks and for the fix to age`() {
        assertFalse(PlaceFixPolicy.shouldRequest(fixAgeMillis = 2 * minute, sinceLastRequestMillis = 30_000L, confirming = true))
        assertFalse(PlaceFixPolicy.shouldRequest(fixAgeMillis = 30_000L, sinceLastRequestMillis = 2 * minute, confirming = true))
    }
}
