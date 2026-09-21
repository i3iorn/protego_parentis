package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.sync.core.*
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.PendingDevice
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.security.LocalDeviceId

/**
 * The fixed set of high-stakes family decisions, and the two shapes their
 * agreement takes: unanimous approval for three of them, a stoppable timer
 * for the fourth.
 */
@RunWith(RobolectricTestRunner::class)
class ConsensusReplicationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun consensus() = ConsensusReplication(context)
    private fun roster() = RosterStore(context)
    private fun state() = ReplicatedStateStore(context)

    @Before
    fun setUp() {
        RoleStore(context).choose(DeviceRole.PARENT)
    }

    @After
    fun tearDown() {
        state().forgetAll()
        roster().forgetAll()
        LocalDeviceId.useInTests(null)
    }

    /** Adds a parent to the roster with no fingerprint checks — a test seam. */
    private fun seedParent(id: String): DeviceId {
        val deviceId = DeviceId(id)
        roster().notice(PendingDevice(deviceId, "Parent $id", SyncRole.PARENT, "pk-$id", Instant.now()))
        roster().approve(deviceId, "Parent $id")
        return deviceId
    }

    /** Adds a device waiting to be vouched in, without approving it. */
    private fun seedPending(id: String, role: SyncRole = SyncRole.PARENT): DeviceId {
        val deviceId = DeviceId(id)
        roster().notice(PendingDevice(deviceId, "Candidate $id", role, "pk-$id", Instant.now()))
        return deviceId
    }

    // ---------- raising a proposal ----------

    @Test
    fun `a parent can raise a proposal, and it is visible by id`() {
        val p1 = seedParent("P1")
        LocalDeviceId.useInTests(p1.value)

        val id = consensus().propose(ConsensusKind.DISBAND_FAMILY, target = p1)

        assertNotNull(id)
        val proposal = consensus().proposal(id!!)
        assertEquals(ConsensusKind.DISBAND_FAMILY, proposal?.kind)
        assertEquals(p1, proposal?.proposedBy)
        assertEquals(p1, proposal?.target)
    }

    @Test
    fun `the managed phone cannot raise a proposal`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("CHILD")

        val id = consensus().propose(ConsensusKind.DISBAND_FAMILY, target = DeviceId("CHILD"))

        assertNull(id)
        assertTrue(consensus().proposals().isEmpty())
    }

    // ---------- held kinds: unanimity ----------

    @Test
    fun `a held proposal does not commit until every current parent approves`() {
        val p1 = seedParent("P1")
        val p2 = seedParent("P2")
        LocalDeviceId.useInTests(p1.value)
        val id = consensus().propose(ConsensusKind.DISBAND_FAMILY, target = p1)!!

        LocalDeviceId.useInTests(p1.value)
        consensus().approve(id)
        assertFalse(consensus().isApproved(consensus().proposal(id)!!))

        LocalDeviceId.useInTests(p2.value)
        consensus().approve(id)
        assertTrue(consensus().isApproved(consensus().proposal(id)!!))
    }

    @Test
    fun `a parent cannot approve on another parent's behalf`() {
        val p1 = seedParent("P1")
        seedParent("P2")
        LocalDeviceId.useInTests(p1.value)
        val id = consensus().propose(ConsensusKind.DISBAND_FAMILY, target = p1)!!

        // Attempting to write into P2's approval register directly, the way a
        // hand-rolled or compromised client might, rather than through
        // approve() — which always writes this device's own id and so cannot
        // express the attack at all.
        val forged = state().writeAll(
            mapOf("consensus/$id/approvals/P2" to "1"),
            origin = p1.value,
            role = SyncRole.PARENT,
        )

        assertTrue(forged.isEmpty())
        assertEquals(emptySet<DeviceId>(), consensus().proposal(id)!!.approvals)
    }

    @Test
    fun `committing a held proposal disbands the family and tombstones it`() {
        val p1 = seedParent("P1")
        LocalDeviceId.useInTests(p1.value)
        val id = consensus().propose(ConsensusKind.DISBAND_FAMILY, target = p1)!!
        consensus().approve(id)
        val proposal = consensus().proposal(id)!!
        assertTrue(consensus().isApproved(proposal))

        val publisher = SyncPublisher(context)
        val committed = consensus().commitIfReady(proposal, publisher)

        assertTrue(committed)
        assertNull(consensus().proposal(id))
        // leave() wipes the roster along with everything else this phone held.
        assertTrue(roster().devices.isEmpty())
    }

    @Test
    fun `committing a vouch-parent proposal admits the pending device`() {
        val p1 = seedParent("P1")
        val candidate = seedPending("P2")
        LocalDeviceId.useInTests(p1.value)
        val id = consensus().propose(ConsensusKind.VOUCH_PARENT, target = candidate)!!
        consensus().approve(id)
        val proposal = consensus().proposal(id)!!
        assertTrue(consensus().isApproved(proposal))

        val committed = consensus().commitIfReady(proposal, SyncPublisher(context))

        assertTrue(committed)
        assertTrue(roster().devices.any { it.id == candidate && it.role == SyncRole.PARENT })
        assertNull(consensus().proposal(id))
    }

    // ---------- enrol_child: unanimous approval, then unanimous enrolment ----------

    @Test
    fun `an enrol-child proposal is not tombstoned until every current parent has enrolled it, not merely approved it`() {
        val p1 = seedParent("P1")
        val p2 = seedParent("P2")
        val candidate = seedPending("NEWCHILD", role = SyncRole.CHILD)
        LocalDeviceId.useInTests(p1.value)
        val id = consensus().propose(ConsensusKind.ENROL_CHILD, target = candidate)!!
        consensus().approve(id)
        LocalDeviceId.useInTests(p2.value)
        consensus().approve(id)
        val proposal = consensus().proposal(id)!!
        assertTrue(consensus().isApproved(proposal))

        // P1 runs its own commit pass first. Unanimous approval is already
        // true, but only P1 has enrolled the child locally so far.
        LocalDeviceId.useInTests(p1.value)
        val committedByP1 = consensus().commitIfReady(proposal, SyncPublisher(context))

        assertFalse(committedByP1)
        assertNotNull(consensus().proposal(id))
        assertTrue(roster().devices.any { it.id == candidate && it.role == SyncRole.CHILD })

        // P2's own pass still has to run before the proposal is done — this
        // is the fix for the trap the plan on #45 calls out: nothing may
        // tombstone the proposal on P1's say-so alone, because there is no
        // vouch that could carry the result to a parent who has not yet
        // independently confirmed it.
        LocalDeviceId.useInTests(p2.value)
        val committedByP2 = consensus().commitIfReady(proposal, SyncPublisher(context))

        assertTrue(committedByP2)
        assertNull(consensus().proposal(id))
    }

    @Test
    fun `a parent with no local pending entry for the candidate is skipped, not tombstoned, and catches up later`() {
        val p1 = seedParent("P1")
        val p2 = seedParent("P2")
        LocalDeviceId.useInTests(p1.value)
        // The candidate's own DEVICE_ANNOUNCE has not reached this replica
        // yet — nothing pending, nothing known — simulating a parent who has
        // not seen it announce at commit time.
        val id = consensus().propose(ConsensusKind.ENROL_CHILD, target = DeviceId("NEWCHILD"))!!
        consensus().approve(id)
        LocalDeviceId.useInTests(p2.value)
        consensus().approve(id)
        val proposal = consensus().proposal(id)!!
        assertTrue(consensus().isApproved(proposal))

        val committed = consensus().commitIfReady(proposal, SyncPublisher(context))

        assertFalse(committed)
        // Still there, unresolved — a device that cannot yet act on this
        // proposal must never be the reason it disappears for everyone else.
        assertNotNull(consensus().proposal(id))
        assertTrue(roster().devices.none { it.id == DeviceId("NEWCHILD") })

        // The announce arrives; the same device gets another chance on its
        // next pass, with no new approval needed.
        seedPending("NEWCHILD", role = SyncRole.CHILD)
        val committedAfterAnnounce = consensus().commitIfReady(consensus().proposal(id)!!, SyncPublisher(context))

        assertTrue(roster().devices.any { it.id == DeviceId("NEWCHILD") && it.role == SyncRole.CHILD })
        // p2 has not enrolled it independently in this test, so unanimous
        // enrolment still is not reached and the proposal survives.
        assertFalse(committedAfterAnnounce)
        assertNotNull(consensus().proposal(id))
    }

    // ---------- remove-parent: timer + veto ----------

    @Test
    fun `a remove-parent proposal does not commit before its timer`() {
        val p1 = seedParent("P1")
        val target = seedParent("P2")
        LocalDeviceId.useInTests(p1.value)
        val now = Instant.ofEpochSecond(1_700_000_000L)
        val id = consensus().propose(ConsensusKind.REMOVE_PARENT, target = target, now = now, timerSeconds = 100)!!

        val proposal = consensus().proposal(id)!!
        assertFalse(consensus().isDue(proposal, now.plusSeconds(50)))
        assertTrue(consensus().isDue(proposal, now.plusSeconds(101)))
    }

    @Test
    fun `cancelling a remove-parent proposal by the target itself stops it`() {
        val p1 = seedParent("P1")
        val target = seedParent("P2")
        LocalDeviceId.useInTests(p1.value)
        val now = Instant.ofEpochSecond(1_700_000_000L)
        val id = consensus().propose(ConsensusKind.REMOVE_PARENT, target = target, now = now, timerSeconds = 100)!!

        // The target cancels its own removal.
        LocalDeviceId.useInTests(target.value)
        assertTrue(consensus().cancel(id))

        val proposal = consensus().proposal(id)!!
        assertTrue(proposal.cancelled)
        assertFalse(consensus().isDue(proposal, now.plusSeconds(1_000)))
    }

    @Test
    fun `committing an elapsed remove-parent proposal revokes the target`() {
        val p1 = seedParent("P1")
        val target = seedParent("P2")
        LocalDeviceId.useInTests(p1.value)
        val now = Instant.ofEpochSecond(1_700_000_000L)
        val id = consensus().propose(ConsensusKind.REMOVE_PARENT, target = target, now = now, timerSeconds = 100)!!

        val due = now.plusSeconds(200)
        val proposal = consensus().proposal(id)!!
        val committed = consensus().commitIfReady(proposal, SyncPublisher(context), now = due)

        assertTrue(committed)
        assertFalse(roster().devices.any { it.id == target })
        assertNull(consensus().proposal(id))
    }

    // ---------- a kind this build does not recognise ----------

    /**
     * The four [ConsensusKind]s are a fixed, deliberately short list — but not
     * fixed *forever*: a future release could add a fifth. Until every parent
     * in a family is on that release, an older parent's phone will see a
     * proposal of a kind it has never heard of. [ConsensusReplication.proposal]
     * already guards this with [ConsensusKind.parse], the same as every other
     * decode here; this proves the guard end to end — a raw record written the
     * way a newer build's [ConsensusReplication.propose] would, straight into
     * this replica's state, the way replication actually delivers it, rather
     * than only exercising the enum.
     *
     * Dropping it here is a real limitation this test also records: an older
     * parent's device does not just fail to *act* on a kind it does not know —
     * it never counts as having approved anything about it either, so a
     * proposal needing that device's unanimous approval can never actually
     * complete until every parent has upgraded. That is a rollout property to
     * be aware of, not a bug this change fixes.
     */
    @Test
    fun `a proposal of a kind this build does not recognise is skipped, not the whole list`() {
        val p1 = seedParent("P1")
        LocalDeviceId.useInTests(p1.value)
        val knownId = consensus().propose(ConsensusKind.DISBAND_FAMILY, target = p1)!!

        val unknownId = "future-proposal"
        state().writeAll(
            mapOf(
                "consensus/$unknownId/kind" to "merge_families",
                "consensus/$unknownId/target" to p1.value,
                "consensus/$unknownId/proposedBy" to p1.value,
                "consensus/$unknownId/proposedAt" to Instant.now().epochSecond.toString(),
            ),
            origin = p1.value,
            role = SyncRole.PARENT,
        )

        assertNull(consensus().proposal(unknownId))
        assertEquals(listOf(knownId), consensus().proposals().map { it.id })
    }

    // ---------- idempotent commit ----------

    @Test
    fun `committing an already-resolved proposal a second time is harmless`() {
        val p1 = seedParent("P1")
        val target = seedParent("P2")
        LocalDeviceId.useInTests(p1.value)
        val now = Instant.ofEpochSecond(1_700_000_000L)
        val id = consensus().propose(ConsensusKind.REMOVE_PARENT, target = target, now = now, timerSeconds = 100)!!
        val due = now.plusSeconds(200)
        val proposal = consensus().proposal(id)!!
        val publisher = SyncPublisher(context)

        consensus().commitIfReady(proposal, publisher, now = due)
        // A second device (here, a second call against the same replica,
        // passing the same now-stale snapshot the way a device that has not
        // yet heard the tombstone would) finds the target already gone and
        // simply re-tombstones an already-empty subtree — [RosterStore.revoke]
        // is itself a no-op on an id it does not hold, so nothing here
        // crashes or double-acts.
        consensus().commitIfReady(proposal, publisher, now = due)

        assertFalse(roster().devices.any { it.id == target })
        assertEquals(1, roster().devices.size)
        assertNull(consensus().proposal(id))
    }
}
