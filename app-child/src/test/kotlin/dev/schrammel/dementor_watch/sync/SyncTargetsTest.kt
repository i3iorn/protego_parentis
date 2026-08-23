package dev.schrammel.dementor_watch.sync

import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.PackageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTargetsTest {

    @Test
    fun `round-trips every kind of target`() {
        listOf(
            GrantTarget.Device,
            GrantTarget.App(PackageName("com.instagram.android")),
            GrantTarget.Category(CategoryId("games")),
        ).forEach { target ->
            assertEquals(target, SyncTargets.decode(SyncTargets.encode(target)))
        }
    }

    /**
     * The bug the explicit prefixes exist to prevent: an app and a category whose
     * identifiers happen to match must not decode to the same thing.
     */
    @Test
    fun `an app and a category with the same id stay distinct`() {
        val app = SyncTargets.encode(GrantTarget.App(PackageName("games")))
        val category = SyncTargets.encode(GrantTarget.Category(CategoryId("games")))

        assertEquals(GrantTarget.App(PackageName("games")), SyncTargets.decode(app))
        assertEquals(GrantTarget.Category(CategoryId("games")), SyncTargets.decode(category))
    }

    @Test
    fun `keeps a package name containing a separator intact`() {
        val target = GrantTarget.App(PackageName("com.example:weird"))

        assertEquals(target, SyncTargets.decode(SyncTargets.encode(target)))
    }

    @Test
    fun `refuses anything it does not recognise`() {
        listOf(null, "", "  ", "app", "app:", ":com.example", "wifi:home", "device:extra")
            .forEach { value -> assertNull(value, SyncTargets.decode(value)) }
    }
}
