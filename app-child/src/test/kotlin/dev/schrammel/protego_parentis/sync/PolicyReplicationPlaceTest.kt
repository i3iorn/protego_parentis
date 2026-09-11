package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.StateOp

/**
 * Places as the third worked example of #40's field-per-path modelling,
 * after schedules and contextual rules — with the structural difference
 * that a coordinate never appears on the wire at all. See [PlaceRef] for
 * why, and [PolicyReplicationContextRuleTest] for the sibling this mirrors.
 */
@RunWith(RobolectricTestRunner::class)
class PolicyReplicationPlaceTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val childId = DeviceId("CHILD")

    private fun store() = ReplicatedStateStore(context)
    private fun replication() = PolicyReplication(context)

    @Before
    fun setUp() {
        LocalDeviceId.useInTests("PARENT")
        RoleStore(context).choose(DeviceRole.PARENT)
    }

    @After
    fun tearDown() {
        store().forgetAll()
        ContextRuleStore(context).places = emptyList()
        LocalDeviceId.useInTests(null)
    }

    private fun path(placeId: String, field: String) = "policy/${childId.value}/places/$placeId/$field"

    private fun op(placeId: String, field: String, value: String?, lamport: Long, origin: String) =
        StateOp(path(placeId, field), value, lamport, origin)

    // ---------- concurrent edits to different fields ----------

    @Test
    fun `two parents editing different fields of one place both win, in every order`() {
        val fromA = listOf(op("P1", "name", "Grandma's", lamport = 1, origin = "AAAA"))
        val fromB = listOf(op("P1", "radius", "150", lamport = 2, origin = "BBBB"))

        listOf(fromA + fromB, fromB + fromA, (fromA + fromB).shuffled(seededRandom())).forEach { ops ->
            store().forgetAll()
            store().receive(ops)

            val ref = replication().readPlaceRefs(childId).singleOrNull()
            assertEquals("Grandma's", ref?.name)
            assertEquals(150, ref?.radiusMetres)
        }
    }

    // ---------- delete racing an edit ----------

    @Test
    fun `a delete racing an edit resolves identically whatever order they arrive, whichever stamp is higher`() {
        val deleteOp = op("P1", "deleted", "1700000100", lamport = 10, origin = "AAAA")
        val editOp = op("P1", "name", "Still here?", lamport = 20, origin = "BBBB")
        val created = listOf(
            op("P1", "name", "Original", lamport = 1, origin = "AAAA"),
            op("P1", "radius", "100", lamport = 1, origin = "AAAA"),
        )

        listOf(
            created + listOf(deleteOp, editOp),
            created + listOf(editOp, deleteOp),
            (created + listOf(deleteOp, editOp)).shuffled(seededRandom()),
        ).forEach { ops ->
            store().forgetAll()
            store().receive(ops)

            assertTrue(
                "the place should read as gone whatever order the ops arrived in",
                replication().readPlaceRefs(childId).isEmpty(),
            )
        }
    }

    // ---------- malformed or partial records ----------

    @Test
    fun `a place that has not fully arrived is excluded, not fatal to the read`() {
        val complete = listOf(
            op("P1", "name", "Grandma's", lamport = 1, origin = "AAAA"),
            op("P1", "radius", "100", lamport = 1, origin = "AAAA"),
        )
        val partial = listOf(op("P2", "name", "Half-arrived", lamport = 1, origin = "AAAA"))

        store().receive(complete + partial)

        val refs = replication().readPlaceRefs(childId)
        assertEquals(listOf("Grandma's"), refs.map { it.name })
    }

    // ---------- round trip ----------

    @Test
    fun `a place written through writePlaceRefs reads back the same, and a removed one reads as gone`() {
        val ref = PlaceRef(id = PlaceId("P1"), name = "Grandma's", radiusMetres = 150)

        replication().writePlaceRefs(listOf(ref), childId)
        assertEquals(listOf(ref), replication().readPlaceRefs(childId))

        replication().writePlaceRefs(emptyList(), childId)
        assertTrue(replication().readPlaceRefs(childId).isEmpty())
    }

    // ---------- the bridge to ContextRuleStore, and the coordinate boundary ----------

    @Test
    fun `pushPlaceRefsFromLocal replicates name and radius but never a coordinate`() {
        val place = Place(id = PlaceId("P1"), name = "Grandma's", latitude = 51.5, longitude = -0.1, radiusMetres = 150)
        ContextRuleStore(context).places = listOf(place)

        replication().pushPlaceRefsFromLocal(childId)

        assertEquals(listOf(PlaceRef(place.id, place.name, place.radiusMetres)), replication().readPlaceRefs(childId))
        val fields = store().children(path("P1", "").removeSuffix("/"))
        assertTrue("no lat/lon field should ever exist on the wire", "lat" !in fields && "lon" !in fields)
    }

    @Test
    fun `pullPlaceRefsIntoLocal applies a remote rename or resize but keeps this device's own coordinate`() {
        val original = Place(id = PlaceId("P1"), name = "Grandma's", latitude = 51.5, longitude = -0.1, radiusMetres = 150)
        ContextRuleStore(context).places = listOf(original)

        // A parent, on another phone, renames and resizes the place.
        replication().writePlaceRefs(listOf(PlaceRef(original.id, "Grandma & Grandpa's", 200)), childId)

        replication().pullPlaceRefsIntoLocal(childId)

        val updated = ContextRuleStore(context).places.single()
        assertEquals("Grandma & Grandpa's", updated.name)
        assertEquals(200, updated.radiusMetres)
        assertEquals(original.latitude, updated.latitude, 0.0)
        assertEquals(original.longitude, updated.longitude, 0.0)
    }

    @Test
    fun `pullPlaceRefsIntoLocal deletes a place this device already knows when told it is gone`() {
        val place = Place(id = PlaceId("P1"), name = "Grandma's", latitude = 51.5, longitude = -0.1, radiusMetres = 150)
        ContextRuleStore(context).places = listOf(place)
        replication().writePlaceRefs(listOf(PlaceRef(place.id, place.name, place.radiusMetres)), childId)
        replication().writePlaceRefs(emptyList(), childId)

        replication().pullPlaceRefsIntoLocal(childId)

        assertTrue(ContextRuleStore(context).places.isEmpty())
    }

    @Test
    fun `pullPlaceRefsIntoLocal leaves a place this device has no coordinate for alone`() {
        // A place id that only ever exists on the wire, never locally on this
        // device — the state a genuinely new place would be in if a parent
        // could somehow write one, which there is no path for; this proves
        // the merge does not manufacture a coordinate for it either way.
        replication().writePlaceRefs(listOf(PlaceRef(PlaceId("P9"), "Nowhere this device knows", 100)), childId)

        replication().pullPlaceRefsIntoLocal(childId)

        assertTrue(ContextRuleStore(context).places.isEmpty())
    }

    private fun seededRandom() = kotlin.random.Random(42)
}
