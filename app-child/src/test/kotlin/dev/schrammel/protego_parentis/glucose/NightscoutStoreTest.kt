package dev.schrammel.protego_parentis.glucose

import dev.schrammel.protego_parentis.model.GlucoseReading
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NightscoutStoreTest {

    private fun store() = NightscoutStore(RuntimeEnvironment.getApplication())

    @After
    fun tearDown() = store().clear()

    /**
     * Robolectric has no AndroidKeyStore, so sealing fails here: which is
     * exactly the case where nothing must be stored, not the token in clear.
     */
    @Test
    fun `a token that cannot be sealed is not saved at all`() {
        assertFalse(store().save("https://kid.example.org", "reader-abc"))
        assertFalse(store().isConfigured)
        assertNull(store().baseUrl)
    }

    @Test
    fun `the newest reading, units and last problem round-trip`() {
        val reading = GlucoseReading(98, Instant.ofEpochMilli(1_790_000_000_000))
        store().latest = reading
        store().units = GlucoseUnit.MMOL_L
        store().lastProblem = NightscoutProblem.UNREACHABLE

        assertEquals(reading, store().latest)
        assertEquals(GlucoseUnit.MMOL_L, store().units)
        assertEquals(NightscoutProblem.UNREACHABLE, store().lastProblem)
    }
}
