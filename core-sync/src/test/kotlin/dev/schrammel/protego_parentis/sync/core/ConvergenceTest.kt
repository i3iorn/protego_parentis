package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two replicas, the whole pipeline, and the question the product actually asks:
 * do both phones end up holding the same rules?
 *
 * Every other test in this module examines one piece in isolation, and they all
 * passed while sync could not work at all. [ReplicationTest] proves the merge
 * converges, but it hands [StateMerge] ops that are *assumed to have been
 * accepted*; [ReplicationRulesTest] proves who may write what, but never merges
 * anything. The shipped bug lived precisely in the gap: the managed phone wrote
 * the rules, every parent refused them at the authorisation step, and no test
 * put those two steps end to end.
 *
 * So this one is deliberately the long way round. A write goes through the
 * clock, the local merge, the encoder, the size batcher, the decoder, the
 * receiver's authorisation and the remote merge — the same order the real
 * publisher and receiver use — and the assertions are about what a person would
 * notice: the rule arrived, and the two phones stopped arguing about it.
 */
class ConvergenceTest {

    /**
     * One phone, holding the pieces the real app spreads across a store, a
     * publisher and a receiver.
     *
     * Small on purpose. It is not a mock of those classes — it is the sequence
     * of steps they perform, written out so a test can put two of them in a
     * room together. If this drifts from what the real ones do the test stops
     * meaning anything, so it does the fewest steps that still include every
     * decision point: stamp, merge, encode, batch, decode, authorise, merge.
     */
    private class Device(val role: SyncRole, val id: String) {
        val clock = LamportClock()
        var state: Map<String, StateEntry> = emptyMap()
        private val pending = mutableListOf<StateOp>()

        /** A local edit. Stamped, applied here, and queued for the family. */
        fun write(path: String, value: String?) {
            val op = StateOp(path, value, clock.tick(), id)
            state = StateMerge.apply(state, listOf(op))
            pending += op
        }

        /** What this phone would put on the wire, as encoded message bodies. */
        fun outbox(): List<String> =
            StateCodec.batch(pending).map(StateCodec::encodeOps).also { pending.clear() }

        /**
         * Everything a message from [sender], identified as [senderId], changes
         * here.
         *
         * Ops that [sender] is not allowed to write are dropped, exactly as the
         * receiver drops them — which is the step that made the whole thing
         * fail, and the reason this test decodes rather than passing ops
         * straight across. [senderId] is the roster-verified id, standing in
         * here for what [dev.schrammel.protego_parentis.sync.RosterRules.admit]
         * would have established in the real receiver.
         */
        fun receive(sender: SyncRole, senderId: DeviceId, body: String) {
            val ops = StateCodec.decodeOps(body).filter { ReplicationRules.accepts(sender, it, senderId) }
            ops.forEach { clock.observe(it.lamport) }
            state = StateMerge.apply(state, ops)
        }

        fun value(path: String): String? = state[path]?.value

        fun digest(nowSeconds: Long): List<Long> = StateDigest.compute(state, nowSeconds)
    }

    /** Hands everything each phone is holding to the other, once. */
    private fun exchange(a: Device, b: Device) {
        val fromA = a.outbox()
        val fromB = b.outbox()
        fromA.forEach { b.receive(a.role, DeviceId(a.id), it) }
        fromB.forEach { a.receive(b.role, DeviceId(b.id), it) }
    }

    private fun child(id: String = "CHILD") = Device(SyncRole.CHILD, id)

    private fun parent() = Device(SyncRole.PARENT, "PARENT")

    // ---------- the failure that shipped ----------

    /**
     * The rules are edited standing at the managed phone, behind the parent PIN,
     * and the parent's phone has to learn them.
     *
     * This is the test that was missing. Under the rule this project shipped
     * with, the parent dropped the op at [ReplicationRules.accepts] and this
     * assertion fails — with the parent holding null, which is exactly what the
     * Rules screen showed.
     */
    @Test
    fun `a rule set on the managed phone reaches the parent`() {
        val child = child()
        val parent = parent()

        child.write("policy/${child.id}/dailyLimitMinutes", "90")
        exchange(child, parent)

        assertEquals("90", parent.value("policy/${child.id}/dailyLimitMinutes"))
    }

    /** And the direction that always worked, so the fix did not trade one for the other. */
    @Test
    fun `a rule set on the parent reaches the managed phone`() {
        val child = child()
        val parent = parent()

        parent.write("policy/${child.id}/dailyLimitMinutes", "45")
        exchange(child, parent)

        assertEquals("45", child.value("policy/${child.id}/dailyLimitMinutes"))
    }

    /**
     * The expensive half of the same bug, and the reason it was worse than a
     * screen not filling in.
     *
     * Two replicas that cannot agree find the same difference on every digest
     * exchange, ask for the same repair, and are refused again — forever, on a
     * phone whose data allowance is why the wire format is compressed at all.
     * Equal digests after one exchange is the assertion that no repair will be
     * asked for, so it is the one that says the storm is gone.
     */
    @Test
    fun `the two phones stop finding a difference to repair`() {
        val child = child()
        val parent = parent()
        val now = 1_700_000_000L

        child.write("policy/${child.id}/dailyLimitMinutes", "90")
        child.write("policy/${child.id}/bedtime/enabled", "1")
        parent.write("policy/${child.id}/categories/games/limitMinutes", "30")
        exchange(child, parent)

        assertEquals(child.state, parent.state)
        assertEquals(
            emptySet<Int>(),
            StateDigest.differing(child.digest(now), parent.digest(now)),
        )
    }

    // ---------- what must not converge ----------

    /**
     * The roster does not soften, and this is where that is worth proving
     * end to end rather than as an authorisation table.
     *
     * A managed phone that could write the roster could admit a device of its
     * own, and that device could then write everything a parent may write. So
     * the op is dropped on arrival and the parent's roster is untouched — an
     * escalation refused, not a view left stale.
     */
    @Test
    fun `the managed phone cannot enrol a device of its own`() {
        val child = child()
        val parent = parent()

        child.write("roster/EVIL/role", "PARENT")
        exchange(child, parent)

        assertEquals(null, parent.value("roster/EVIL/role"))
    }

    /** Telemetry travels the other way only: a parent cannot describe the child's phone. */
    @Test
    fun `a parent cannot report the managed phone's own state`() {
        val child = child()
        val parent = parent()

        parent.write("telemetry/battery", "5")
        exchange(child, parent)

        assertEquals(null, child.value("telemetry/battery"))
    }

    // ---------- convergence under the conditions that break naive sync ----------

    /**
     * A conversation long enough that one message cannot carry it.
     *
     * Batching by op count let a body past what the relay accepts, and a
     * receiver drops an over-long line rather than truncating it — so the state
     * did not arrive damaged, it did not arrive. Every message is checked
     * against the ceiling here, and then the transcript is checked to have
     * arrived whole, because a batcher that split into empty pieces would also
     * pass the first assertion.
     */
    @Test
    fun `a long conversation still arrives whole`() {
        val child = child()
        val parent = parent()
        val line = "x".repeat(500)

        repeat(120) { i -> child.write("chat/1700000000-$i/text", line) }
        val bodies = child.outbox()
        bodies.forEach { body ->
            assertTrue(
                "a message of ${body.length} chars exceeds the ceiling",
                body.length <= StateCodec.MAX_MESSAGE_CHARS,
            )
            parent.receive(child.role, DeviceId(child.id), body)
        }

        assertEquals(120, parent.state.keys.count { it.startsWith("chat/") })
        assertEquals(child.state, parent.state)
    }

    /**
     * Both phones edit the same rule while out of contact, and afterwards they
     * agree on which edit won — the same one, on both.
     *
     * Which of the two it is matters far less than that they match: a family
     * where one phone shows ninety minutes and the other forty-five has no way
     * to notice it is wrong.
     */
    @Test
    fun `a rule edited on both phones at once settles the same way on each`() {
        val child = child()
        val parent = parent()

        child.write("policy/${child.id}/dailyLimitMinutes", "90")
        parent.write("policy/${child.id}/dailyLimitMinutes", "45")
        exchange(child, parent)
        // Twice, because the first exchange is what a real pair of phones would
        // do on one poll and convergence must not need a lucky second round.
        exchange(child, parent)

        assertEquals(child.state, parent.state)
        assertEquals(
            child.value("policy/${child.id}/dailyLimitMinutes"),
            parent.value("policy/${child.id}/dailyLimitMinutes"),
        )
    }

    /**
     * A phone that has been talking to itself for a while does not get to
     * outrank a family it then meets.
     *
     * The clock is observed from every op that arrives, so the two ends of a
     * long silence come back into step on the first exchange rather than one
     * side winning every tie until it happens to catch up.
     */
    @Test
    fun `a phone that ran ahead alone does not win every later tie`() {
        val child = child()
        val parent = parent()

        repeat(50) { i -> child.write("policy/${child.id}/appLimits/com.example.$i", "10") }
        exchange(child, parent)

        parent.write("policy/${child.id}/dailyLimitMinutes", "45")
        exchange(child, parent)

        assertEquals("45", child.value("policy/${child.id}/dailyLimitMinutes"))
        assertEquals(child.state, parent.state)
    }

    /**
     * A family with two managed phones keeps their rules apart.
     *
     * A replica still holds both children's rules — nothing here scopes what a
     * device *stores*, only what it may *write*. The point of a per-child
     * address space is the second half of this test: neither managed phone's
     * write can land in the other's subtree, not because the parent is
     * careful, but because [ReplicationRules.mayWrite] refuses it
     * structurally.
     */
    @Test
    fun `two children converge on two independent rulesets`() {
        val childA = child("CHILD_A")
        val childB = child("CHILD_B")
        val parent = parent()

        parent.write("policy/${childA.id}/dailyLimitMinutes", "60")
        parent.write("policy/${childB.id}/dailyLimitMinutes", "120")
        // A real family syncs over a pub/sub relay: one broadcast reaches every
        // subscriber, not one peer at a time. So the parent's outbox is drained
        // once and handed to both children, the way [exchange] cannot.
        val fromParent = parent.outbox()
        fromParent.forEach { childA.receive(parent.role, DeviceId(parent.id), it) }
        fromParent.forEach { childB.receive(parent.role, DeviceId(parent.id), it) }

        assertEquals("60", childA.value("policy/${childA.id}/dailyLimitMinutes"))
        assertEquals("120", childB.value("policy/${childB.id}/dailyLimitMinutes"))

        // Each managed phone may write only its own subtree...
        childA.write("policy/${childA.id}/bedtime/enabled", "1")
        exchange(childA, parent)
        assertEquals("1", parent.value("policy/${childA.id}/bedtime/enabled"))

        // ...and an attempt at the other child's subtree is refused on arrival,
        // leaving what the parent set untouched.
        childA.write("policy/${childB.id}/dailyLimitMinutes", "999")
        exchange(childA, parent)
        assertEquals("120", parent.value("policy/${childB.id}/dailyLimitMinutes"))
    }
}
