package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.sync.core.*
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.rules.ScheduleStore
import dev.schrammel.protego_parentis.rules.StoredSchedule
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

/**
 * Schedules as the worked example for #40's modelling problem: a list of
 * records, where every other replicated field so far has been a scalar or a
 * set.
 *
 * Every test here feeds [ReplicatedStateStore.receive] the same batch of ops
 * in a different order and checks [PolicyReplication.readSchedules] comes out
 * the same regardless — the property [ReplicationTest] proves generically for
 * [StateMerge], exercised here against the specific shape a schedule takes and
 * the specific question of whether "gone" ever depends on arrival order.
 */
@RunWith(RobolectricTestRunner::class)
class PolicyReplicationScheduleTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val childId = DeviceId("CHILD")

    private fun store() = ReplicatedStateStore(context)
    private fun replication() = PolicyReplication(context)

    @Before
    fun setUp() {
        LocalDeviceId.useInTests("PARENT")
        // Only relevant to tests that write through PolicyReplication.write*
        // and so go through the real write-time gate — the ordering tests feed
        // ReplicatedStateStore.receive directly, which is unauthorised on
        // purpose (see ReplicatedStateStoreTest).
        RoleStore(context).choose(DeviceRole.PARENT)
    }

    @After
    fun tearDown() {
        store().forgetAll()
        ScheduleStore(context).schedules = emptyList()
        LocalDeviceId.useInTests(null)
    }

    private fun path(scheduleId: String, field: String) = "policy/${childId.value}/schedules/$scheduleId/$field"

    private fun op(scheduleId: String, field: String, value: String?, lamport: Long, origin: String) =
        StateOp(path(scheduleId, field), value, lamport, origin)

    // ---------- concurrent edits to different fields ----------

    /**
     * Two parents, each editing a different field of the same schedule while
     * out of contact. Both edits have to survive — a schedule is a record, and
     * this is the property field-per-path exists to give it.
     */
    @Test
    fun `two parents editing different fields of one schedule both win, in every order`() {
        val fromA = listOf(
            op("S1", "name", "Homework", lamport = 1, origin = "AAAA"),
            op("S1", "start", "540", lamport = 1, origin = "AAAA"),
            op("S1", "end", "600", lamport = 1, origin = "AAAA"),
            op("S1", "days/1", "1", lamport = 1, origin = "AAAA"),
        )
        val fromB = listOf(op("S1", "enabled", "false", lamport = 2, origin = "BBBB"))

        listOf(fromA + fromB, fromB + fromA, (fromA + fromB).shuffled(seededRandom())).forEach { ops ->
            store().forgetAll()
            store().receive(ops)

            val schedule = replication().readSchedules(childId).singleOrNull()
            assertEquals("Homework", schedule?.name)
            assertEquals(false, schedule?.enabled)
        }
    }

    // ---------- delete racing a rename ----------

    /**
     * The race #40 exists to resolve deterministically: one device renames a
     * schedule, another deletes it, at the same moment. Whichever the merge
     * would pick for `name` alone must not matter — once `deleted` has been
     * written at all, the schedule reads as gone on every replica, whatever
     * arrival order or Lamport stamp the rename carries.
     */
    @Test
    fun `a delete racing a rename resolves identically whatever order they arrive, whichever stamp is higher`() {
        // The rename's Lamport stamp is deliberately higher than the delete's —
        // if "deleted" were resolved by comparing stamps against other fields
        // the way a single tombstoned register would, this is the ordering that
        // would make the rename win. It must not: the two are different paths,
        // and neither ordering nor stamp height decides between them.
        val deleteOp = op("S1", "deleted", "1700000100", lamport = 10, origin = "AAAA")
        val renameOp = op("S1", "name", "Still here?", lamport = 20, origin = "BBBB")
        val created = listOf(
            op("S1", "name", "Original", lamport = 1, origin = "AAAA"),
            op("S1", "start", "540", lamport = 1, origin = "AAAA"),
            op("S1", "end", "600", lamport = 1, origin = "AAAA"),
            op("S1", "days/1", "1", lamport = 1, origin = "AAAA"),
        )

        listOf(
            created + listOf(deleteOp, renameOp),
            created + listOf(renameOp, deleteOp),
            (created + listOf(deleteOp, renameOp)).shuffled(seededRandom()),
        ).forEach { ops ->
            store().forgetAll()
            store().receive(ops)

            assertTrue(
                "the schedule should read as gone whatever order the ops arrived in",
                replication().readSchedules(childId).isEmpty(),
            )
        }
    }

    // ---------- malformed or partial records ----------

    @Test
    fun `a schedule that has not fully arrived is excluded, not fatal to the read`() {
        val complete = listOf(
            op("S1", "name", "Homework", lamport = 1, origin = "AAAA"),
            op("S1", "start", "540", lamport = 1, origin = "AAAA"),
            op("S1", "end", "600", lamport = 1, origin = "AAAA"),
            op("S1", "days/1", "1", lamport = 1, origin = "AAAA"),
        )
        // A second schedule whose name has replicated but nothing else has yet —
        // the ordinary state of a record still arriving, not a corruption.
        val partial = listOf(op("S2", "name", "Half-arrived", lamport = 1, origin = "AAAA"))

        store().receive(complete + partial)

        val schedules = replication().readSchedules(childId)
        assertEquals(listOf("Homework"), schedules.map { it.name })
    }

    @Test
    fun `an unrecognised mode value falls back rather than failing the whole record`() {
        val ops = listOf(
            op("S1", "name", "Homework", lamport = 1, origin = "AAAA"),
            op("S1", "start", "540", lamport = 1, origin = "AAAA"),
            op("S1", "end", "600", lamport = 1, origin = "AAAA"),
            op("S1", "days/1", "1", lamport = 1, origin = "AAAA"),
            // From a newer build with a mode this one has never heard of.
            op("S1", "mode", "SOMETHING_FUTURE", lamport = 1, origin = "AAAA"),
        )

        store().receive(ops)

        val schedule = replication().readSchedules(childId).singleOrNull()
        assertEquals("Homework", schedule?.name)
        assertEquals(ScheduleBlock.Mode.EXCLUSIVE, schedule?.mode)
    }

    // ---------- round trip ----------

    @Test
    fun `a schedule written through writeSchedules reads back the same, and a removed one reads as gone`() {
        val schedule = StoredSchedule(
            id = "S1",
            name = "Homework",
            days = setOf(1, 3, 5),
            startMinuteOfDay = 540,
            endMinuteOfDay = 600,
            packages = setOf("com.example.games"),
            mode = ScheduleBlock.Mode.INCLUSIVE,
            enabled = true,
        )

        replication().writeSchedules(listOf(schedule), childId)
        assertEquals(listOf(schedule), replication().readSchedules(childId))

        replication().writeSchedules(emptyList(), childId)
        assertTrue(replication().readSchedules(childId).isEmpty())
    }

    /** #83: a fortnightly schedule's anchor replicates like any other field. */
    @Test
    fun `a fortnightly schedule round-trips its parity anchor`() {
        val schedule = StoredSchedule(
            id = "S1",
            name = "Football",
            days = setOf(5),
            startMinuteOfDay = 900,
            endMinuteOfDay = 1020,
            parityAnchorEpochDay = 19222L,
        )

        replication().writeSchedules(listOf(schedule), childId)

        assertEquals(schedule, replication().readSchedules(childId).single())
    }

    /** Clearing it back to every-week has to actually clear, not just stop being sent. */
    @Test
    fun `un-setting a parity anchor clears it on the next write`() {
        val fortnightly = StoredSchedule(
            id = "S1",
            name = "Football",
            days = setOf(5),
            startMinuteOfDay = 900,
            endMinuteOfDay = 1020,
            parityAnchorEpochDay = 19222L,
        )
        replication().writeSchedules(listOf(fortnightly), childId)

        replication().writeSchedules(listOf(fortnightly.copy(parityAnchorEpochDay = null)), childId)

        assertEquals(null, replication().readSchedules(childId).single().parityAnchorEpochDay)
    }

    /** #12: bedtime replicates through this channel now, kind included. */
    @Test
    fun `a bedtime-kind schedule round-trips its kind`() {
        val bedtime = StoredSchedule(
            id = ScheduleStore.BEDTIME_ID,
            name = "Bedtime",
            days = setOf(1, 2, 3, 4, 5),
            startMinuteOfDay = 21 * 60,
            endMinuteOfDay = 7 * 60,
            kind = ScheduleBlock.Kind.BEDTIME,
        )

        replication().writeSchedules(listOf(bedtime), childId)

        assertEquals(ScheduleBlock.Kind.BEDTIME, replication().readSchedules(childId).single().kind)
    }

    // ---------- the bridge to ScheduleStore ----------

    @Test
    fun `pushSchedulesFromLocal replicates whatever ScheduleStore holds`() {
        val schedule = StoredSchedule(
            id = "S1",
            name = "Homework",
            days = setOf(1, 3, 5),
            startMinuteOfDay = 540,
            endMinuteOfDay = 600,
        )
        ScheduleStore(context).schedules = listOf(schedule)

        replication().pushSchedulesFromLocal(childId)

        assertEquals(listOf(schedule), replication().readSchedules(childId))
    }

    @Test
    fun `pullSchedulesIntoLocal writes the replica's schedules onto ScheduleStore`() {
        val schedule = StoredSchedule(
            id = "S1",
            name = "Dinner",
            days = setOf(1, 2, 3, 4, 5, 6, 7),
            startMinuteOfDay = 1080,
            endMinuteOfDay = 1140,
        )
        replication().writeSchedules(listOf(schedule), childId)

        replication().pullSchedulesIntoLocal(childId)

        assertEquals(listOf(schedule), ScheduleStore(context).schedules)
    }

    /** [ScheduleStore]'s own JSON persistence, not just the replication path. */
    @Test
    fun `ScheduleStore itself round-trips a parity anchor`() {
        val schedule = StoredSchedule(
            id = "S1",
            name = "Football",
            days = setOf(5),
            startMinuteOfDay = 900,
            endMinuteOfDay = 1020,
            parityAnchorEpochDay = 19222L,
        )

        ScheduleStore(context).schedules = listOf(schedule)

        assertEquals(listOf(schedule), ScheduleStore(context).schedules)
    }

    private fun seededRandom() = kotlin.random.Random(42)
}
