package dev.schrammel.protego_parentis.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a replica finds out it is behind.
 *
 * The digest is the only thing standing between "the relay expired the ops you
 * missed" and a phone that is quietly wrong forever, so the properties here are
 * worth pinning: agreeing states agree, disagreeing states disagree in exactly
 * the buckets at fault, and neither depends on the order a map happens to be in.
 */
class StateDigestTest {

    /** A fixed clock, so a windowed section is unambiguously inside the window. */
    private val now = 1_700_000_000L

    private fun state(vararg entries: Pair<String, StateEntry>) = entries.toMap()

    private fun entry(value: String?, lamport: Long, origin: String = "AAAA") =
        StateEntry(value, lamport, origin)

    @Test
    fun `identical states produce identical digests`() {
        val one = state("policy/limit" to entry("60", 1), "chat/${now}-a/text" to entry("hi", 2))
        val other = state("chat/${now}-a/text" to entry("hi", 2), "policy/limit" to entry("60", 1))

        assertEquals(StateDigest.compute(one, now), StateDigest.compute(other, now))
        assertTrue(StateDigest.differing(StateDigest.compute(one, now), StateDigest.compute(other, now)).isEmpty())
    }

    @Test
    fun `a different value shows up as a difference`() {
        val mine = state("policy/limit" to entry("60", 1))
        val theirs = state("policy/limit" to entry("90", 2))

        assertNotEquals(StateDigest.compute(mine, now), StateDigest.compute(theirs, now))
    }

    /** The same value written at a different time is still a divergence to repair. */
    @Test
    fun `a different logical time shows up as a difference`() {
        val mine = state("policy/limit" to entry("60", 1))
        val theirs = state("policy/limit" to entry("60", 2))

        assertNotEquals(StateDigest.compute(mine, now), StateDigest.compute(theirs, now))
    }

    @Test
    fun `a different origin shows up as a difference`() {
        val mine = state("policy/limit" to entry("60", 1, "AAAA"))
        val theirs = state("policy/limit" to entry("60", 1, "BBBB"))

        assertNotEquals(StateDigest.compute(mine, now), StateDigest.compute(theirs, now))
    }

    /** A tombstone is a state, and must not hash as an empty string. */
    @Test
    fun `a tombstone differs from an empty value`() {
        val deleted = state("policy/x" to entry(null, 1))
        val blank = state("policy/x" to entry("", 1))

        assertNotEquals(StateDigest.compute(deleted, now), StateDigest.compute(blank, now))
    }

    @Test
    fun `an empty state digests to all zeroes`() {
        assertEquals(List(StateDigest.BUCKETS) { 0L }, StateDigest.compute(emptyMap(), now))
    }

    @Test
    fun `only the buckets actually at fault are reported`() {
        val shared = "policy/limit" to entry("60", 1)
        val mine = state(shared, "chat/${now}-a/text" to entry("hi", 2))
        val theirs = state(shared)

        val differing = StateDigest.differing(StateDigest.compute(mine, now), StateDigest.compute(theirs, now))

        assertEquals(setOf(StateDigest.bucketOf("chat/${now}-a/text")), differing)
    }

    @Test
    fun `a repair carries exactly the entries in the buckets asked for`() {
        val full = state(
            "policy/limit" to entry("60", 1),
            "chat/${now}-a/text" to entry("hi", 2),
        )
        val wanted = setOf(StateDigest.bucketOf("chat/${now}-a/text"))

        val repair = StateDigest.entriesIn(full, wanted, now)

        assertEquals(listOf("chat/${now}-a/text"), repair.map { it.path })
    }

    /** Applying a repair must leave the asking replica agreeing with the answering one. */
    @Test
    fun `a repair round trip makes two replicas agree`() {
        val theirs = state(
            "policy/limit" to entry("90", 9),
            "chat/${now}-a/text" to entry("hi", 2),
            "policy/bedtime" to entry(null, 4),
        )
        val mine = state("policy/limit" to entry("60", 1))

        val buckets = StateDigest.differing(StateDigest.compute(mine, now), StateDigest.compute(theirs, now))
        val repaired = StateMerge.apply(mine, StateDigest.entriesIn(theirs, buckets, now))

        assertEquals(StateDigest.compute(theirs, now), StateDigest.compute(repaired, now))
    }

    // ---------- what is worth comparing at all ----------

    /**
     * The reason the modes exist. Telemetry changes every tick, so including it
     * would mean every exchange found a difference and asked for a repair.
     */
    @Test
    fun `volatile telemetry is not compared`() {
        val quiet = state("telemetry/usedMinutes" to entry("10", 1))
        val busy = state("telemetry/usedMinutes" to entry("11", 2))

        assertEquals(StateDigest.compute(quiet, now), StateDigest.compute(busy, now))
    }

    /** An old chat message must not be able to trigger a repair forever. */
    @Test
    fun `stream entries outside the window are not compared`() {
        val ancient = now - ReplicationRules.RECENT_WINDOW_SECONDS - 1
        val mine = state("chat/${ancient}-a/text" to entry("hi", 1))

        assertEquals(StateDigest.compute(emptyMap(), now), StateDigest.compute(mine, now))
    }

    @Test
    fun `stream entries inside the window are compared`() {
        val recent = now - 60
        val mine = state("chat/${recent}-a/text" to entry("hi", 1))

        assertNotEquals(StateDigest.compute(emptyMap(), now), StateDigest.compute(mine, now))
    }

    /** A repair must not carry what the digest never compared. */
    @Test
    fun `a repair skips entries the digest ignored`() {
        val ancient = now - ReplicationRules.RECENT_WINDOW_SECONDS - 1
        val full = state(
            "telemetry/usedMinutes" to entry("10", 1),
            "chat/${ancient}-a/text" to entry("old", 2),
            "chat/${now}-b/text" to entry("new", 3),
        )

        val repair = StateDigest.entriesIn(full, (0 until StateDigest.BUCKETS).toSet(), now)

        assertEquals(listOf("chat/${now}-b/text"), repair.map { it.path })
    }

    @Test
    fun `every path lands in a real bucket`() {
        listOf("", "policy", "policy/limit", "a/b/c/d", "åäö", "x".repeat(500))
            .forEach { path ->
                assertTrue(path, StateDigest.bucketOf(path) in 0 until StateDigest.BUCKETS)
            }
    }

    /**
     * A digest from a build that buckets differently cannot be compared. Saying
     * "everything differs" would ask for a full repair on every single exchange.
     */
    @Test
    fun `a digest of the wrong size is ignored rather than treated as total disagreement`() {
        val mine = StateDigest.compute(state("policy/limit" to entry("60", 1)), now)

        assertTrue(StateDigest.differing(mine, listOf(1L, 2L, 3L)).isEmpty())
        assertTrue(StateDigest.differing(mine, emptyList()).isEmpty())
    }
}
