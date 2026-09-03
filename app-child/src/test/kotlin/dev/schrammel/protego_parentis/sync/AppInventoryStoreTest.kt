package dev.schrammel.protego_parentis.sync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #43: a parent's cached copy of a managed phone's app list, keyed per
 * child now that a family can hold more than one — was a single flat
 * cache that would have let a second child's inventory overwrite the
 * first's.
 */
@RunWith(RobolectricTestRunner::class)
class AppInventoryStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = AppInventoryStore(context)

    @Test
    fun `nothing is known before a phone has ever reported in`() {
        assertTrue(store().apps(DeviceId("CHILD1")).isEmpty())
        assertNull(store().receivedAt(DeviceId("CHILD1")))
    }

    @Test
    fun `two children's inventories do not overwrite each other`() {
        val childA = DeviceId("CHILD1")
        val childB = DeviceId("CHILD2")

        store().setApps(childA, listOf(AppInventoryEntry("com.example.a", "A")))
        store().setApps(childB, listOf(AppInventoryEntry("com.example.b", "B")))

        assertEquals(listOf(AppInventoryEntry("com.example.a", "A")), store().apps(childA))
        assertEquals(listOf(AppInventoryEntry("com.example.b", "B")), store().apps(childB))
    }

    @Test
    fun `receivedAt is set only for the child that reported in`() {
        store().setApps(DeviceId("CHILD1"), listOf(AppInventoryEntry("com.example.a", "A")))

        assertTrue(store().receivedAt(DeviceId("CHILD1")) != null)
        assertNull(store().receivedAt(DeviceId("CHILD2")))
    }
}
