package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.Heartbeat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

/**
 * [historyHead]/[historyCount] round-trip through storage, not just through the
 * in-memory [Heartbeat] the receiver builds — this is the evidence
 * `IntegrityRules.historyRewritten` (TR-3) compares the *next* heartbeat against,
 * so losing it on the way to disk would make every rewrite look like a first
 * sighting.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `historyHead and historyCount survive a save and reload`() {
        val store = HeartbeatStore(context)
        store.latest = Heartbeat(
            sentAt = Instant.parse("2026-09-12T10:00:00Z"),
            policyVersion = 3L,
            batteryPercent = 80,
            managed = true,
            usageAccessGranted = true,
            suspendedCount = 0,
            historyHead = "abc123",
            historyCount = 42L,
        )

        val reloaded = HeartbeatStore(context).latest

        assertEquals("abc123", reloaded?.historyHead)
        assertEquals(42L, reloaded?.historyCount)
    }

    @Test
    fun `a fresh store has no history evidence yet`() {
        val store = HeartbeatStore(context)
        store.latest = Heartbeat(
            sentAt = Instant.parse("2026-09-12T10:00:00Z"),
            policyVersion = 1L,
            batteryPercent = 80,
            managed = true,
            usageAccessGranted = true,
            suspendedCount = 0,
        )

        val reloaded = requireNotNull(store.latest)

        assertEquals("", reloaded.historyHead)
        assertEquals(0L, reloaded.historyCount)
    }
}
