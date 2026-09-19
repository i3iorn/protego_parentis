package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.sync.core.DeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Mirrors [AppInventoryStoreTest] — the same cache shape, one per managed phone. */
@RunWith(RobolectricTestRunner::class)
class RemoteDiagnosticsStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = RemoteDiagnosticsStore(context)

    @Test
    fun `nothing is known before a phone has ever reported in`() {
        assertNull(store().log(DeviceId("CHILD1")))
        assertNull(store().receivedAt(DeviceId("CHILD1")))
    }

    @Test
    fun `two children's logs do not overwrite each other`() {
        val childA = DeviceId("CHILD1")
        val childB = DeviceId("CHILD2")

        store().setLog(childA, "log-a")
        store().setLog(childB, "log-b")

        assertEquals("log-a", store().log(childA))
        assertEquals("log-b", store().log(childB))
    }

    @Test
    fun `receivedAt is set only for the child that reported in`() {
        store().setLog(DeviceId("CHILD1"), "log-a")

        assertEquals(true, store().receivedAt(DeviceId("CHILD1")) != null)
        assertNull(store().receivedAt(DeviceId("CHILD2")))
    }

    @Test
    fun `a later report replaces the earlier one`() {
        val child = DeviceId("CHILD1")
        store().setLog(child, "old")

        store().setLog(child, "new")

        assertEquals("new", store().log(child))
    }
}
