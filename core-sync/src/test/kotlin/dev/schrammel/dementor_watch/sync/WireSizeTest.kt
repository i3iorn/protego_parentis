package dev.schrammel.dementor_watch.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the protocol actually costs.
 *
 * These are not correctness tests — they are the ones that fail if somebody
 * later adds a field to every op, or drops the front coding, and turns a
 * few-hundred-byte exchange into a few kilobytes. On a child's phone with a
 * small data allowance that is a real regression, and it is otherwise invisible:
 * everything still works, it just costs more every fifteen seconds.
 *
 * The numbers are deliberately loose. They are ceilings meant to catch an order
 * of magnitude, not a budget to tune against.
 */
class WireSizeTest {

    private val origin = "A1B2C3D4E5F6"

    /** A realistic category: thirty apps, all sharing one long path prefix. */
    private fun category(id: String, apps: Int): List<StateOp> =
        (1..apps).map { index ->
            StateOp(
                path = "policy/categories/$id/packages/com.example.application$index",
                value = "1",
                lamport = 42L,
                origin = origin,
            )
        }

    @Test
    fun `a category of thirty apps costs far less than its paths`() {
        val ops = category("games", apps = 30)
        val rawPathBytes = ops.sumOf { it.path.length }

        val encoded = StateCodec.encodeOps(ops)

        // Front coding should pay for itself several times over here: every path
        // after the first shares 40-odd characters with the one before it.
        assertTrue(
            "encoded ${encoded.length} vs ${rawPathBytes} of raw path",
            encoded.length < rawPathBytes / 2,
        )
    }

    /** The commonest message of all: one slider moved. */
    @Test
    fun `changing one limit is a very small message`() {
        val encoded = StateCodec.encodeOps(
            listOf(StateOp("policy/dailyLimitMinutes", "90", 7L, origin)),
        )

        assertTrue("one op encoded to ${encoded.length}", encoded.length < 60)
    }

    /** A digest is fixed-size however much state the family has accumulated. */
    @Test
    fun `a digest does not grow with the state`() {
        val small = StateMerge.apply(emptyMap(), listOf(StateOp("policy/x", "1", 1, origin)))
        val large = StateMerge.apply(
            emptyMap(),
            (1..2_000).map { StateOp("policy/key$it", "value$it", it.toLong(), origin) },
        )

        val huge = StateMerge.apply(
            emptyMap(),
            (1..50_000).map { StateOp("policy/key$it", "value$it", it.toLong(), origin) },
        )

        val now = 1_700_000_000L
        val largeDigest = StateCodec.encodeDigest(StateDigest.compute(large, now))
        val hugeDigest = StateCodec.encodeDigest(StateDigest.compute(huge, now))

        // Always sixteen numbers, whatever the family has accumulated.
        assertEquals(StateDigest.BUCKETS, StateDigest.compute(small, now).size)
        assertEquals(StateDigest.BUCKETS, StateDigest.compute(huge, now).size)
        assertTrue("digest was ${hugeDigest.length}", hugeDigest.length < 400)
        // Twenty-five times the state, essentially the same digest.
        assertTrue(hugeDigest.length < largeDigest.length + StateDigest.BUCKETS)
    }

    /**
     * An idle phone must publish nothing at all. This is the difference between
     * a protocol that costs a few hundred bytes an hour and one that costs a few
     * hundred bytes every tick.
     */
    @Test
    fun `re-recording unchanged values produces no ops`() {
        val values = mapOf("policy/dailyLimitMinutes" to "90", "policy/bedtime/enabled" to "true")
        val state = StateMerge.apply(
            emptyMap(),
            values.map { (path, value) -> StateOp(path, value, 1L, origin) },
        )

        val again = StateMerge.novel(
            state,
            values.map { (path, value) -> StateOp(path, value, 1L, origin) },
        )

        assertTrue(again.isEmpty())
        assertEquals("", StateCodec.encodeOps(again))
    }
}
