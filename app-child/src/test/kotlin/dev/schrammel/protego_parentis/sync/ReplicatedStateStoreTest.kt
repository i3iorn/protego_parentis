package dev.schrammel.protego_parentis.sync

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The first test in this module for a class that takes a [Context].
 *
 * Worth saying why it is the first. Everything in this package needs a Context,
 * nothing in the project could test such a class until now, and so none of them
 * had a test — while the pure-JVM modules next door carry five hundred. Every
 * sync defect found so far lived on this side of that line and was found by
 * reading the code rather than by anything failing, which is the predictable
 * result of the line being where it was.
 *
 * [ReplicatedStateStore] goes first because it is the densest of them and
 * because it needs nothing this harness cannot provide — no signing identity,
 * no network, just SharedPreferences and the merge rules.
 */
@RunWith(RobolectricTestRunner::class)
class ReplicatedStateStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * The clock outlives any one store, so a test that did not clear it would
     * inherit whatever the previous one left — and the bug this class is most
     * worth testing for is precisely about a stale clock. [forgetAll] drops both
     * the replica and the clock, which is exactly the reset wanted here.
     */
    @Before
    fun reset() = ReplicatedStateStore(context).forgetAll()

    @After
    fun tearDown() = ReplicatedStateStore(context).forgetAll()

    private fun store() = ReplicatedStateStore(context)

    // ---------- the clock, which is where the silence was ----------

    /**
     * The defect this class shipped with, written as the sequence that produced
     * it.
     *
     * The clock used to be per-instance, read off disk when that instance
     * happened to be constructed — and the publisher, the receiver and every
     * section adapter build their own. A store constructed early and kept held a
     * clock from before everyone else's writes, so its own edit was stamped
     * below what the map already held and lost its own merge: the value did not
     * change, the op was still queued, and every peer refused it for the same
     * reason. Nothing was logged anywhere.
     *
     * Constructing both stores up front is the whole test. Do it after the
     * writes and the bug hides.
     */
    @Test
    fun `a store built before another's writes can still win with its own`() {
        val early = store()
        val busy = store()

        repeat(3) { i -> busy.write("policy/dailyLimitMinutes", "$i", "BUSY", SyncRole.PARENT) }
        early.write("policy/dailyLimitMinutes", "90", "EARLY", SyncRole.PARENT)

        assertEquals("90", store().value("policy/dailyLimitMinutes"))
    }

    /** The same property stated directly: two stores never stamp the same tick. */
    @Test
    fun `two stores in one process do not hand out the same lamport`() {
        val a = store()
        val b = store()

        val first = a.write("policy/dailyLimitMinutes", "60", "A", SyncRole.PARENT)
        val second = b.write("policy/bedtime/enabled", "1", "B", SyncRole.PARENT)

        assertNotEquals(first?.lamport, second?.lamport)
    }

    /**
     * A high clock must not travel to the next family.
     *
     * A phone that had been busy elsewhere would otherwise join and outrank
     * everything already there, clobbering rules it had never seen — and it
     * would do so silently, because winning a merge looks exactly like being
     * right.
     */
    @Test
    fun `leaving a family drops the clock as well as the replica`() {
        val busy = store()
        repeat(20) { i -> busy.write("policy/appLimits/com.example.$i", "$i", "OLD", SyncRole.PARENT) }
        val before = busy.write("policy/dailyLimitMinutes", "10", "OLD", SyncRole.PARENT)?.lamport ?: 0

        busy.forgetAll()
        val after = store().write("policy/dailyLimitMinutes", "10", "NEW", SyncRole.PARENT)?.lamport ?: 0

        assertTrue("clock carried over: $before then $after", after < before)
    }

    // ---------- what a write does, and does not, do ----------

    @Test
    fun `a write is readable and queued for the family`() {
        store().write("policy/dailyLimitMinutes", "90", "A", SyncRole.PARENT)

        assertEquals("90", store().value("policy/dailyLimitMinutes"))
        assertEquals(listOf("policy/dailyLimitMinutes"), store().pending.map { it.path })
    }

    /**
     * Setting a value to what it already is publishes nothing.
     *
     * This is not an optimisation to be traded away later: the rules are pushed
     * on every enforcement tick, so a store that queued an op each time would
     * republish the whole policy all day on a phone whose data allowance is the
     * reason the wire format is compressed at all.
     */
    @Test
    fun `writing the value it already holds queues nothing`() {
        store().write("policy/dailyLimitMinutes", "90", "A", SyncRole.PARENT)
        val queuedAfterFirst = store().pending.size

        val op = store().write("policy/dailyLimitMinutes", "90", "A", SyncRole.PARENT)

        assertNull(op)
        assertEquals(queuedAfterFirst, store().pending.size)
    }

    /**
     * A path or value a peer would refuse is refused here too.
     *
     * The symmetry is the point. A replica holding a value nobody else accepts,
     * in a section the digest compares, is a difference that repair can never
     * settle — the two phones would ask each other about it forever. An
     * over-long app label is enough to cause it.
     */
    @Test
    fun `a value no peer would accept is not written locally either`() {
        val tooLong = "x".repeat(ReplicationRules.MAX_VALUE_LENGTH + 1)

        val op = store().write("policy/appLimits/com.example.a", tooLong, "A", SyncRole.PARENT)

        assertNull(op)
        assertNull(store().value("policy/appLimits/com.example.a"))
    }

    @Test
    fun `clearing pending leaves the value behind`() {
        val op = store().write("policy/dailyLimitMinutes", "90", "A", SyncRole.PARENT)

        store().clearPending(listOfNotNull(op))

        assertTrue(store().pending.isEmpty())
        assertEquals("90", store().value("policy/dailyLimitMinutes"))
    }

    // ---------- receiving ----------

    @Test
    fun `an op from elsewhere is applied and reported as novel`() {
        val incoming = StateOp("policy/dailyLimitMinutes", "45", 9, "THEM")

        val novel = store().receive(listOf(incoming))

        assertEquals(listOf(incoming), novel)
        assertEquals("45", store().value("policy/dailyLimitMinutes"))
    }

    @Test
    fun `the same op arriving twice is novel only once`() {
        val incoming = StateOp("policy/dailyLimitMinutes", "45", 9, "THEM")
        store().receive(listOf(incoming))

        assertTrue(store().receive(listOf(incoming)).isEmpty())
    }

    /**
     * Receiving does not queue anything to send back.
     *
     * A store that queued what it had just been told would answer every message
     * with the same message, and two replicas doing that to each other is a loop
     * that costs traffic until one of them is switched off.
     */
    @Test
    fun `receiving queues nothing to publish`() {
        store().receive(listOf(StateOp("policy/dailyLimitMinutes", "45", 9, "THEM")))

        assertTrue(store().pending.isEmpty())
    }

    /**
     * Hearing from a peer that has run ahead pulls this device's clock forward.
     *
     * The op arriving says somebody has reached a logical time this device had
     * not, and every local write from here has to sort after it. Without that, a
     * phone coming back from a long time offline would lose edit after edit to
     * the peer that kept going, each one silently, until its clock happened to
     * catch up on its own.
     *
     * Deliberately a different path from the one written next, so what is being
     * checked is the clock moving rather than one op beating another.
     */
    @Test
    fun `an op from a peer that ran ahead pulls this device's clock forward`() {
        store().write("policy/dailyLimitMinutes", "60", "US", SyncRole.PARENT)

        store().receive(listOf(StateOp("policy/bedtime/enabled", "1", 500, "THEM")))
        val next = store().write("policy/dailyLimitMinutes", "70", "US", SyncRole.PARENT)

        assertTrue("local write did not sort after the peer", (next?.lamport ?: 0) > 500)
    }

    /**
     * And the same when nothing the peer sent was new.
     *
     * A receive that finds nothing novel returns before committing state, so the
     * clock is the only thing it has to write — an easy branch to lose.
     *
     * Honest about its reach: the clock is process-wide, so a later store reads
     * it from memory and this cannot tell a clock that was persisted from one
     * that merely was not forgotten. Separating those needs a way to drop the
     * in-memory clock without also clearing the replica, and [forgetAll] does
     * both by design. What is covered is that the no-novel path does not *undo*
     * the observation, which is the failure that would actually bite.
     */
    @Test
    fun `a message that changed nothing still leaves the clock moved`() {
        val heard = StateOp("policy/bedtime/enabled", "1", 500, "THEM")
        store().receive(listOf(heard))

        assertTrue(store().receive(listOf(heard)).isEmpty())
        val next = ReplicatedStateStore(context).write("policy/dailyLimitMinutes", "70", "US", SyncRole.PARENT)

        assertTrue("clock did not survive a no-op receive", (next?.lamport ?: 0) > 500)
    }

    // ---------- survival ----------

    /**
     * A write made in a tunnel is still queued after the app is killed.
     *
     * The store is the only thing between an edit and the transport, and the
     * transport is allowed to be unavailable for a long time.
     */
    @Test
    fun `a queued op survives the store being rebuilt`() {
        store().write("policy/dailyLimitMinutes", "90", "A", SyncRole.PARENT)

        val rebuilt = ReplicatedStateStore(context)

        assertEquals(listOf("policy/dailyLimitMinutes"), rebuilt.pending.map { it.path })
        assertEquals("90", rebuilt.value("policy/dailyLimitMinutes"))
    }

    @Test
    fun `leaving a family leaves nothing behind`() {
        store().write("policy/dailyLimitMinutes", "90", "A", SyncRole.PARENT)
        store().write("chat/1700000000-a/text", "hello", "A", SyncRole.PARENT)

        store().forgetAll()

        assertTrue(store().entries.isEmpty())
        assertTrue(store().pending.isEmpty())
    }

    // ---------- reading the tree ----------

    @Test
    fun `children returns one level and not the level below it`() {
        store().writeAll(
            mapOf(
                "policy/categories/games/limitMinutes" to "30",
                "policy/categories/social/limitMinutes" to "20",
                "policy/categories/games/apps/com.example.a" to "1",
            ),
            "A",
            SyncRole.PARENT,
        )

        assertEquals(setOf("games", "social"), store().idsUnder("policy/categories"))
    }

    @Test
    fun `a deleted key is not offered as a child`() {
        store().write("policy/appLimits/com.example.a", "30", "A", SyncRole.PARENT)
        store().write("policy/appLimits/com.example.a", null, "A", SyncRole.PARENT)

        assertEquals(emptyMap<String, String>(), store().children("policy/appLimits"))
    }

    // ---------- the write-time gate ----------

    /**
     * A write this device is not allowed to make is refused here, before it is
     * ever queued — not merely dropped by every peer once published.
     *
     * This is the asymmetry [ReplicationRules.mayWrite] warns about: a receiver
     * refusing a peer's op was already tested, but a local write went straight
     * into the map and the outbound queue with no equivalent check, so the two
     * replicas disagreed on every digest exchange forever. See
     * [dev.schrammel.protego_parentis.sync.ReplicationRulesTest].
     */
    @Test
    fun `a write this device may not make is refused, not merely queued and later refused`() {
        val op = store().write("policy/OTHER_CHILD/dailyLimitMinutes", "90", "SELF", SyncRole.CHILD)

        assertNull(op)
        assertNull(store().value("policy/OTHER_CHILD/dailyLimitMinutes"))
        assertTrue(store().pending.isEmpty())
    }

    /** The mirror: a managed phone writing its own subtree is still let through. */
    @Test
    fun `a child may still write its own policy subtree locally`() {
        val op = store().write("policy/SELF/dailyLimitMinutes", "90", "SELF", SyncRole.CHILD)

        assertEquals("90", op?.value)
        assertEquals("90", store().value("policy/SELF/dailyLimitMinutes"))
    }
}
