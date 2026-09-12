package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.sync.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import dev.schrammel.protego_parentis.sync.core.DeviceId

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

    // ---------- upsert ----------

    @Test
    fun `upsert adds a new app alongside the existing list`() {
        val child = DeviceId("CHILD1")
        store().setApps(child, listOf(AppInventoryEntry("com.example.a", "A")))

        store().upsert(child, AppInventoryEntry("com.example.b", "B"))

        assertEquals(
            setOf(AppInventoryEntry("com.example.a", "A"), AppInventoryEntry("com.example.b", "B")),
            store().apps(child).toSet(),
        )
    }

    @Test
    fun `upsert replaces an existing entry for the same package rather than duplicating it`() {
        val child = DeviceId("CHILD1")
        store().setApps(child, listOf(AppInventoryEntry("com.example.a", "Old Label")))

        store().upsert(child, AppInventoryEntry("com.example.a", "New Label"))

        assertEquals(listOf(AppInventoryEntry("com.example.a", "New Label")), store().apps(child))
    }

    @Test
    fun `upsert can start a list from nothing`() {
        val child = DeviceId("CHILD1")

        store().upsert(child, AppInventoryEntry("com.example.a", "A"))

        assertEquals(listOf(AppInventoryEntry("com.example.a", "A")), store().apps(child))
    }
}
