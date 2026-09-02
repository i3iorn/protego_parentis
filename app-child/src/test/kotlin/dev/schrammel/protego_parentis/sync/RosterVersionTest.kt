package dev.schrammel.protego_parentis.sync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

/**
 * The announced version, from the wire to the roster and back off disk.
 *
 * Most messages carry no version — only DEVICE_ANNOUNCE does — so the property
 * that matters is that the heartbeats in between do not erase what was learned.
 */
@RunWith(RobolectricTestRunner::class)
class RosterVersionTest {

    private lateinit var store: RosterStore
    private val id = DeviceId("aaaaaaaaaaaaaaaa")
    private val now: Instant = Instant.parse("2026-09-02T10:00:00Z")

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        store = RosterStore(context)
        store.enrol(
            Vouch(id = id, name = "Parent phone", role = SyncRole.PARENT, publicKey = "key"),
            now,
        )
    }

    private fun device() = store.devices.first { it.id == id }

    @Test
    fun `a device starts with no announced version`() {
        assertNull(device().appVersion)
    }

    @Test
    fun `an announced version is recorded`() {
        store.observed(id, name = null, at = now, appVersion = "1.1.1")

        assertEquals("1.1.1", device().appVersion)
    }

    /** The case that actually happens: a heartbeat between two announcements. */
    @Test
    fun `a message carrying no version keeps the last one`() {
        store.observed(id, name = null, at = now, appVersion = "1.1.1")
        store.observed(id, name = null, at = now.plusSeconds(60), appVersion = null)

        assertEquals("1.1.1", device().appVersion)
    }

    @Test
    fun `a blank version is treated as no version`() {
        store.observed(id, name = null, at = now, appVersion = "1.1.1")
        store.observed(id, name = null, at = now.plusSeconds(60), appVersion = "   ")

        assertEquals("1.1.1", device().appVersion)
    }

    @Test
    fun `an upgrade replaces the recorded version`() {
        store.observed(id, name = null, at = now, appVersion = "1.1.1")
        store.observed(id, name = null, at = now.plusSeconds(60), appVersion = "1.2.0")

        assertEquals("1.2.0", device().appVersion)
    }

    @Test
    fun `the version survives a round trip through storage`() {
        store.observed(id, name = null, at = now, appVersion = "1.1.1")

        assertEquals("1.1.1", RosterStore(context).devices.first { it.id == id }.appVersion)
    }
}
