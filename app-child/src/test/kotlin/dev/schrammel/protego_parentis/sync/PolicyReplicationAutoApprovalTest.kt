package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.AutoApprovalWindow
import dev.schrammel.protego_parentis.rules.AutoApprovalStore
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
import kotlin.time.Duration.Companion.minutes
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.security.LocalDeviceId

/**
 * Auto-approval rules — #42's last list-of-records slice, the same shape
 * [PolicyReplicationScheduleTest] exercises for schedules: field-per-path,
 * a `deleted` marker rather than a whole-record tombstone.
 */
@RunWith(RobolectricTestRunner::class)
class PolicyReplicationAutoApprovalTest {

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
        AutoApprovalStore(context).rules = emptyList()
        LocalDeviceId.useInTests(null)
    }

    private fun path(ruleId: String, field: String) = "policy/${childId.value}/autoApproval/$ruleId/$field"

    private fun op(ruleId: String, field: String, value: String?, lamport: Long, origin: String) =
        StateOp(path(ruleId, field), value, lamport, origin)

    // ---------- delete racing an edit ----------

    @Test
    fun `a delete racing an edit resolves identically whatever order they arrive`() {
        val deleteOp = op("R1", "deleted", "1700000100", lamport = 10, origin = "AAAA")
        val editOp = op("R1", "name", "Still here?", lamport = 20, origin = "BBBB")
        val created = listOf(
            op("R1", "name", "Bonus games", lamport = 1, origin = "AAAA"),
            op("R1", "enabled", "true", lamport = 1, origin = "AAAA"),
            op("R1", "maxMinutes", "15", lamport = 1, origin = "AAAA"),
            op("R1", "maxPerWindow", "2", lamport = 1, origin = "AAAA"),
            op("R1", "window", "WEEK", lamport = 1, origin = "AAAA"),
        )

        listOf(
            created + listOf(deleteOp, editOp),
            created + listOf(editOp, deleteOp),
            (created + listOf(deleteOp, editOp)).shuffled(kotlin.random.Random(42)),
        ).forEach { ops ->
            store().forgetAll()
            store().receive(ops)

            assertTrue(
                "the rule should read as gone whatever order the ops arrived in",
                replication().readAutoApprovals(childId).isEmpty(),
            )
        }
    }

    // ---------- malformed or partial records ----------

    @Test
    fun `a rule that has not fully arrived is excluded, not fatal to the read`() {
        val complete = listOf(
            op("R1", "name", "Bonus games", lamport = 1, origin = "AAAA"),
            op("R1", "enabled", "true", lamport = 1, origin = "AAAA"),
            op("R1", "maxMinutes", "15", lamport = 1, origin = "AAAA"),
            op("R1", "maxPerWindow", "2", lamport = 1, origin = "AAAA"),
            op("R1", "window", "WEEK", lamport = 1, origin = "AAAA"),
        )
        val partial = listOf(op("R2", "name", "Half-arrived", lamport = 1, origin = "AAAA"))

        store().receive(complete + partial)

        val rules = replication().readAutoApprovals(childId)
        assertEquals(listOf("Bonus games"), rules.map { it.name })
    }

    @Test
    fun `an unrecognised window value falls back rather than failing the whole record`() {
        val ops = listOf(
            op("R1", "name", "Bonus games", lamport = 1, origin = "AAAA"),
            op("R1", "enabled", "true", lamport = 1, origin = "AAAA"),
            op("R1", "maxMinutes", "15", lamport = 1, origin = "AAAA"),
            op("R1", "maxPerWindow", "2", lamport = 1, origin = "AAAA"),
            // From a newer build with a window this one has never heard of.
            op("R1", "window", "SOMETHING_FUTURE", lamport = 1, origin = "AAAA"),
        )

        store().receive(ops)

        val rule = replication().readAutoApprovals(childId).singleOrNull()
        assertEquals("Bonus games", rule?.name)
        assertEquals(AutoApprovalWindow.WEEK, rule?.window)
    }

    // ---------- round trip ----------

    @Test
    fun `a rule written through writeAutoApprovals reads back the same, and a removed one reads as gone`() {
        val rule = AutoApproval(
            id = "R1",
            name = "Bonus games",
            enabled = true,
            maxAmount = 15.minutes,
            maxPerWindow = 2,
            window = AutoApprovalWindow.DAY,
        )

        replication().writeAutoApprovals(listOf(rule), childId)
        assertEquals(listOf(rule), replication().readAutoApprovals(childId))

        replication().writeAutoApprovals(emptyList(), childId)
        assertTrue(replication().readAutoApprovals(childId).isEmpty())
    }

    // ---------- the bridge to AutoApprovalStore ----------

    @Test
    fun `pushAutoApprovalsFromLocal replicates whatever AutoApprovalStore holds`() {
        val rule = AutoApproval(
            id = "R1",
            name = "Bonus games",
            maxAmount = 15.minutes,
            maxPerWindow = 2,
        )
        AutoApprovalStore(context).rules = listOf(rule)

        replication().pushAutoApprovalsFromLocal(childId)

        assertEquals(listOf(rule), replication().readAutoApprovals(childId))
    }

    @Test
    fun `pullAutoApprovalsIntoLocal writes the replica's rules onto AutoApprovalStore`() {
        val rule = AutoApproval(
            id = "R1",
            name = "Bonus games",
            maxAmount = 15.minutes,
            maxPerWindow = 2,
        )
        replication().writeAutoApprovals(listOf(rule), childId)

        replication().pullAutoApprovalsIntoLocal(childId)

        assertEquals(listOf(rule), AutoApprovalStore(context).rules)
    }
}
