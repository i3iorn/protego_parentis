package dev.schrammel.protego_parentis.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property the whole sync layer rests on: any set of ops, applied in any
 * order, any number of times, leaves every replica holding the same map.
 *
 * If that stops being true, two phones in a family disagree about the rules and
 * neither can tell — which is exactly the failure this design replaced.
 */
class ReplicationTest {

    private fun op(
        path: String,
        value: String?,
        lamport: Long,
        origin: String = "AAAA",
    ) = StateOp(path, value, lamport, origin)

    // ---------- ordering ----------

    @Test
    fun `a later write wins`() {
        val state = StateMerge.apply(emptyMap(), listOf(op("policy/limit", "60", 1)))

        val merged = StateMerge.apply(state, listOf(op("policy/limit", "90", 2)))

        assertEquals("90", merged["policy/limit"]?.value)
    }

    @Test
    fun `an earlier write arriving late is ignored`() {
        val state = StateMerge.apply(emptyMap(), listOf(op("policy/limit", "90", 5)))

        val merged = StateMerge.apply(state, listOf(op("policy/limit", "60", 2)))

        assertEquals("90", merged["policy/limit"]?.value)
    }

    /**
     * Two devices writing the same field at the same logical time is a genuine
     * concurrent edit. Which one wins does not matter; that *both replicas pick
     * the same one* is the entire point.
     */
    @Test
    fun `a tie is broken the same way on every replica`() {
        val fromA = op("policy/limit", "60", 4, origin = "AAAA")
        val fromB = op("policy/limit", "90", 4, origin = "BBBB")

        val oneWay = StateMerge.apply(StateMerge.apply(emptyMap(), listOf(fromA)), listOf(fromB))
        val orTheOther = StateMerge.apply(StateMerge.apply(emptyMap(), listOf(fromB)), listOf(fromA))

        assertEquals(oneWay, orTheOther)
        assertEquals("90", oneWay["policy/limit"]?.value)
    }

    @Test
    fun `applying the same op twice changes nothing`() {
        val once = StateMerge.apply(emptyMap(), listOf(op("chat/1/text", "hello", 1)))
        val twice = StateMerge.apply(once, listOf(op("chat/1/text", "hello", 1)))

        assertEquals(once, twice)
    }

    @Test
    fun `order of arrival never changes the result`() {
        val ops = listOf(
            op("policy/limit", "60", 1, "AAAA"),
            op("policy/limit", "90", 3, "BBBB"),
            op("policy/bedtime", "2100", 2, "AAAA"),
            op("policy/limit", "30", 2, "CCCC"),
            op("policy/bedtime", null, 4, "BBBB"),
        )

        val forwards = StateMerge.apply(emptyMap(), ops)
        val backwards = StateMerge.apply(emptyMap(), ops.reversed())
        val shuffled = StateMerge.apply(emptyMap(), ops.sortedBy { it.origin })

        assertEquals(forwards, backwards)
        assertEquals(forwards, shuffled)
    }

    /** Two replicas that saw the same ops in different orders must agree. */
    @Test
    fun `two replicas converge whatever they heard first`() {
        val fromA = listOf(op("policy/limit", "60", 1, "AAAA"), op("policy/x", "1", 3, "AAAA"))
        val fromB = listOf(op("policy/limit", "90", 2, "BBBB"), op("policy/y", "1", 4, "BBBB"))

        val replicaA = StateMerge.apply(StateMerge.apply(emptyMap(), fromA), fromB)
        val replicaB = StateMerge.apply(StateMerge.apply(emptyMap(), fromB), fromA)

        assertEquals(replicaA, replicaB)
    }

    // ---------- deletion ----------

    /**
     * A tombstone has to be a real entry. Dropping the key instead would let an
     * older write that a replica has not yet seen bring the value back.
     */
    @Test
    fun `deleting leaves a tombstone rather than removing the key`() {
        val state = StateMerge.apply(emptyMap(), listOf(op("policy/categories/games/name", "Games", 1)))

        val merged = StateMerge.apply(state, listOf(op("policy/categories/games/name", null, 2)))

        assertTrue(merged.containsKey("policy/categories/games/name"))
        assertNull(merged["policy/categories/games/name"]?.value)
        assertTrue(merged["policy/categories/games/name"]!!.isTombstone)
    }

    @Test
    fun `an older write cannot resurrect a deleted key`() {
        val deleted = StateMerge.apply(emptyMap(), listOf(op("policy/x", null, 5)))

        val merged = StateMerge.apply(deleted, listOf(op("policy/x", "back", 3)))

        assertNull(merged["policy/x"]?.value)
    }

    @Test
    fun `a newer write after a delete brings the key back`() {
        val deleted = StateMerge.apply(emptyMap(), listOf(op("policy/x", null, 5)))

        val merged = StateMerge.apply(deleted, listOf(op("policy/x", "back", 6)))

        assertEquals("back", merged["policy/x"]?.value)
    }

    /** An empty value is a value. Folding it into "deleted" would lose a real state. */
    @Test
    fun `an empty string is not a tombstone`() {
        val state = StateMerge.apply(emptyMap(), listOf(op("chat/1/text", "", 1)))

        assertFalse(state["chat/1/text"]!!.isTombstone)
        assertEquals("", state["chat/1/text"]?.value)
    }

    // ---------- what is worth sending ----------

    @Test
    fun `novel reports only the ops that would change anything`() {
        val state = StateMerge.apply(emptyMap(), listOf(op("policy/limit", "60", 5)))

        val novel = StateMerge.novel(
            state,
            listOf(
                op("policy/limit", "30", 2),
                op("policy/limit", "90", 9),
                op("policy/other", "1", 1),
            ),
        )

        assertEquals(setOf("policy/limit" to 9L, "policy/other" to 1L), novel.map { it.path to it.lamport }.toSet())
    }

    @Test
    fun `applying a no-op batch returns the state unchanged`() {
        val state = StateMerge.apply(emptyMap(), listOf(op("policy/limit", "60", 5)))

        assertTrue(state === StateMerge.apply(state, listOf(op("policy/limit", "30", 1))))
        assertTrue(state === StateMerge.apply(state, emptyList()))
    }

    @Test
    fun `state round-trips through ops`() {
        val state = StateMerge.apply(
            emptyMap(),
            listOf(op("policy/limit", "60", 1), op("policy/x", null, 2, "BBBB")),
        )

        assertEquals(state, StateMerge.apply(emptyMap(), StateMerge.opsOf(state)))
    }

    // ---------- the shared history ----------

    /**
     * The bug this rule was rewritten for.
     *
     * The original refused any write to an already-populated audit path, which
     * made the outcome depend on which op a replica happened to see first — so
     * two phones could end up holding different versions of the same log entry
     * and neither could ever repair to the other.
     */
    @Test
    fun `an audit entry resolves the same way whatever order it arrives in`() {
        val original = op("audit/1-a/action", "DAILY_LIMIT_CHANGED", 5, "AAAA")
        val revision = op("audit/1-a/action", "SOMETHING_ELSE", 9, "BBBB")

        val sawOriginalFirst = StateMerge.apply(StateMerge.apply(emptyMap(), listOf(original)), listOf(revision))
        val sawRevisionFirst = StateMerge.apply(StateMerge.apply(emptyMap(), listOf(revision)), listOf(original))

        assertEquals(sawOriginalFirst, sawRevisionFirst)
    }

    @Test
    fun `a later write cannot revise the shared history`() {
        val original = op("audit/1-a/action", "DAILY_LIMIT_CHANGED", 5, "AAAA")
        val revision = op("audit/1-a/action", "SOMETHING_ELSE", 9, "BBBB")

        val merged = StateMerge.apply(StateMerge.apply(emptyMap(), listOf(original)), listOf(revision))

        assertEquals("DAILY_LIMIT_CHANGED", merged["audit/1-a/action"]?.value)
    }

    /** Same clock, different devices: still resolved identically everywhere. */
    @Test
    fun `a tie in the shared history is broken deterministically too`() {
        val fromA = op("audit/1-a/action", "X", 4, "AAAA")
        val fromB = op("audit/1-a/action", "Y", 4, "BBBB")

        val oneWay = StateMerge.apply(StateMerge.apply(emptyMap(), listOf(fromA)), listOf(fromB))
        val orTheOther = StateMerge.apply(StateMerge.apply(emptyMap(), listOf(fromB)), listOf(fromA))

        assertEquals(oneWay, orTheOther)
        // Earliest-wins inverts the tiebreak as well, so the lower id stands.
        assertEquals("X", oneWay["audit/1-a/action"]?.value)
    }

    @Test
    fun `the shared history is still idempotent`() {
        val entry = op("audit/1-a/action", "X", 5, "AAAA")
        val once = StateMerge.apply(emptyMap(), listOf(entry))

        assertEquals(once, StateMerge.apply(once, listOf(entry)))
    }

    /** An ordinary register keeps last-write-wins; only the log is inverted. */
    @Test
    fun `inverting the log did not invert everything else`() {
        val merged = StateMerge.apply(
            StateMerge.apply(emptyMap(), listOf(op("policy/limit", "60", 5))),
            listOf(op("policy/limit", "90", 9)),
        )

        assertEquals("90", merged["policy/limit"]?.value)
    }

    // ---------- the clock ----------

    @Test
    fun `the clock counts up`() {
        val clock = LamportClock()

        assertEquals(1L, clock.tick())
        assertEquals(2L, clock.tick())
    }

    /** After hearing a later time, the next local write must sort after it. */
    @Test
    fun `observing a later time moves the clock forward`() {
        val clock = LamportClock()
        clock.tick()

        clock.observe(50L)

        assertEquals(51L, clock.tick())
    }

    @Test
    fun `observing an earlier time changes nothing`() {
        val clock = LamportClock(start = 10L)

        clock.observe(3L)

        assertEquals(11L, clock.tick())
    }
}
