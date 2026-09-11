package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCodecTest {

    /**
     * The wire separators, written as escapes so this file stays plain text and
     * the tests can still build raw input by hand.
     */
    private val recordSeparator = "\u001E"
    private val fieldSeparator = "\u001F"

    private fun rawOp(vararg fields: String) = fields.joinToString(fieldSeparator)

    private val ops = listOf(
        StateOp("policy/limit", "60", 1, "AAAA"),
        StateOp("policy/categories/games/name", "Games", 2, "BBBB"),
        StateOp("policy/x", null, 3, "AAAA"),
    )

    /**
     * As a set: the encoder sorts by path to make its front coding pay, and ops
     * are commutative so nothing downstream can tell. See the test below.
     */
    @Test
    fun `round-trips a batch of ops`() {
        assertEquals(ops.toSet(), StateCodec.decodeOps(StateCodec.encodeOps(ops)).toSet())
    }

    @Test
    fun `encoding sorts by path, and is allowed to`() {
        val decoded = StateCodec.decodeOps(StateCodec.encodeOps(ops))

        assertEquals(decoded.map { it.path }.sorted(), decoded.map { it.path })
    }

    /**
     * The point of the front coding. Two long paths sharing a long prefix must
     * cost far less than twice one of them, or a category with thirty apps in it
     * is thirty full paths on the wire.
     */
    @Test
    fun `a shared path prefix is not paid for twice`() {
        val root = "policy/categories/games/packages/"
        val one = StateCodec.encodeOps(listOf(StateOp(root + "com.example.a", "1", 1, "AAAA")))
        val two = StateCodec.encodeOps(
            listOf(
                StateOp(root + "com.example.a", "1", 1, "AAAA"),
                StateOp(root + "com.example.b", "1", 1, "AAAA"),
            ),
        )

        assertTrue(
            "two ops cost ${two.length}, one costs ${one.length}",
            two.length < one.length + root.length,
        )
    }

    /** One clock tick and one device for the whole batch, written once. */
    @Test
    fun `a repeated origin and clock are not paid for twice`() {
        val shared = StateCodec.encodeOps(
            listOf(
                StateOp("a/one", "x", 7, "LONGDEVICEID"),
                StateOp("b/two", "x", 7, "LONGDEVICEID"),
            ),
        )
        val differing = StateCodec.encodeOps(
            listOf(
                StateOp("a/one", "x", 7, "LONGDEVICEID"),
                StateOp("b/two", "x", 8, "OTHERDEVICEID"),
            ),
        )

        assertTrue(shared.length < differing.length)
    }

    @Test
    fun `round-trips the empty batch`() {
        assertEquals(emptyList<StateOp>(), StateCodec.decodeOps(StateCodec.encodeOps(emptyList())))
        assertEquals(emptyList<StateOp>(), StateCodec.decodeOps(null))
        assertEquals(emptyList<StateOp>(), StateCodec.decodeOps(""))
    }

    /** A tombstone and an empty value are different states and must stay so on the wire. */
    @Test
    fun `a tombstone survives the round trip as a tombstone`() {
        val decoded = StateCodec.decodeOps(StateCodec.encodeOps(listOf(StateOp("p/x", null, 1, "A"))))

        assertNull(decoded.single().value)
    }

    @Test
    fun `an empty value survives as an empty value`() {
        val decoded = StateCodec.decodeOps(StateCodec.encodeOps(listOf(StateOp("p/x", "", 1, "A"))))

        assertEquals("", decoded.single().value)
    }

    /**
     * Somebody will type a newline into a chat message, and the separators are
     * reachable from a keyboard too. None of it may split the batch.
     */
    @Test
    fun `values containing separators and backslashes survive`() {
        val awkward = listOf(
            StateOp("chat/1/text", "line one\nline two", 1, "AAAA"),
            StateOp("chat/2/text", "tab\there", 2, "AAAA"),
            StateOp("chat/3/text", "back\\slash", 3, "AAAA"),
            StateOp("chat/4/text", "record${recordSeparator}separator", 4, "AAAA"),
            StateOp("chat/5/text", "field${fieldSeparator}separator", 5, "AAAA"),
            StateOp("chat/6/text", "\\\\${recordSeparator}\\", 6, "AAAA"),
        )

        assertEquals(awkward, StateCodec.decodeOps(StateCodec.encodeOps(awkward)))
    }

    /** A path can carry a separator too, and is the key everything else is filed under. */
    @Test
    fun `paths containing separators survive`() {
        val awkward = listOf(StateOp("chat/${fieldSeparator}odd/text", "hi", 1, "AAAA"))

        assertEquals(awkward, StateCodec.decodeOps(StateCodec.encodeOps(awkward)))
    }

    /**
     * A newer build may write an op this one cannot parse. Losing the other
     * thirty-nine in the same message would be far worse than losing the one.
     */
    @Test
    fun `one unreadable op does not cost the rest of the batch`() {
        val corrupted = StateCodec.encodeOps(ops) + recordSeparator + "not a real op"

        assertEquals(ops.toSet(), StateCodec.decodeOps(corrupted).toSet())
    }

    @Test
    fun `an op with an unparseable clock is skipped`() {
        val raw = rawOp("0", "p/x", "1", "value", "not-a-number", "AAAA")

        assertTrue(StateCodec.decodeOps(raw).isEmpty())
    }

    @Test
    fun `an op with no path or no origin is skipped`() {
        assertTrue(StateCodec.decodeOps(rawOp("0", "", "1", "value", "5", "AAAA")).isEmpty())
        assertTrue(StateCodec.decodeOps(rawOp("0", "p/x", "1", "value", "5", "")).isEmpty())
    }

    @Test
    fun `an op with too few fields is skipped`() {
        assertTrue(StateCodec.decodeOps(rawOp("0", "p/x", "1", "value")).isEmpty())
    }

    /**
     * A shared-prefix length longer than the path before it means a truncated or
     * corrupted batch. Skipping keeps the rest readable; guessing would invent a
     * path and write it into state.
     */
    @Test
    fun `an op claiming more shared prefix than exists is skipped`() {
        assertTrue(StateCodec.decodeOps(rawOp("99", "x", "1", "v", "5", "AAAA")).isEmpty())
        assertTrue(StateCodec.decodeOps(rawOp("-1", "x", "1", "v", "5", "AAAA")).isEmpty())
        assertTrue(StateCodec.decodeOps(rawOp("nope", "x", "1", "v", "5", "AAAA")).isEmpty())
    }

    /** The first op in a batch has nothing before it, so it may not lean on one. */
    @Test
    fun `the first op carries its whole path`() {
        val encoded = StateCodec.encodeOps(listOf(StateOp("policy/limit", "60", 1, "AAAA")))

        assertTrue(encoded.startsWith("0"))
    }

    // ---------- digests ----------

    @Test
    fun `round-trips a digest`() {
        val digest = listOf(0L, -5L, Long.MAX_VALUE, 42L)

        assertEquals(digest, StateCodec.decodeDigest(StateCodec.encodeDigest(digest)))
    }

    @Test
    fun `a digest with an unreadable bucket decodes to nothing at all`() {
        assertEquals(emptyList<Long>(), StateCodec.decodeDigest("1,2,oops,4"))
        assertEquals(emptyList<Long>(), StateCodec.decodeDigest(null))
    }

    @Test
    fun `round-trips a set of buckets`() {
        val buckets = setOf(0, 3, 15)

        assertEquals(buckets, StateCodec.decodeBuckets(StateCodec.encodeBuckets(buckets)))
    }

    @Test
    fun `bucket numbers outside the range are dropped`() {
        assertEquals(setOf(0, 5), StateCodec.decodeBuckets("0,5,99,-1,oops"))
        assertEquals(emptySet<Int>(), StateCodec.decodeBuckets(null))
    }
}
