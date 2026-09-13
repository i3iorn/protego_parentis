package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MessageFragmentsTest {

    // ---------- split ----------

    @Test
    fun `an envelope within budget is returned unchanged`() {
        val envelope = "AbCdEf12=="

        val chunks = MessageFragments.split("group-1", envelope, maxWireChars = 100)

        assertEquals(listOf(envelope), chunks)
    }

    @Test
    fun `an oversized envelope is split into several pieces, each within budget`() {
        val envelope = "x".repeat(1_000)

        val chunks = MessageFragments.split("group-1", envelope, maxWireChars = 120)

        assertTrue("expected more than one piece, got ${chunks.size}", chunks.size > 1)
        chunks.forEach { assertTrue("piece was ${it.length} chars", it.length <= 120) }
    }

    @Test
    fun `splitting and parsing every piece round-trips the original envelope`() {
        val envelope = (1..5_000).joinToString("") { (it % 10).toString() }

        val chunks = MessageFragments.split("g-42", envelope, maxWireChars = 200)
        val fragments = chunks.map { requireNotNull(MessageFragments.parse(it)) }

        assertEquals(fragments.size, fragments.first().total)
        assertTrue(fragments.all { it.groupId == "g-42" })
        assertEquals((0 until fragments.size).toList(), fragments.map { it.index }.sorted())

        val reassembled = fragments.sortedBy { it.index }.joinToString("") { it.chunk }
        assertEquals(envelope, reassembled)
    }

    @Test
    fun `parse rejects anything without the fragment prefix`() {
        assertNull(MessageFragments.parse("AbCdEf12=="))
        assertNull(MessageFragments.parse(""))
    }

    @Test
    fun `parse rejects a malformed fragment`() {
        assertNull(MessageFragments.parse("F1:onlyonefield"))
        assertNull(MessageFragments.parse("F1:g:notanumber:2:chunk"))
        assertNull(MessageFragments.parse("F1:g:0:0:chunk")) // total must be positive
        assertNull(MessageFragments.parse("F1:g:5:3:chunk")) // index out of range
        assertNull(MessageFragments.parse("F1:g:0:2:")) // empty chunk
    }

    // ---------- FragmentAssembler ----------

    @Test
    fun `a plain envelope passes straight through unchanged`() {
        val assembler = FragmentAssembler()

        assertEquals("AbCdEf12==", assembler.accept("AbCdEf12=="))
    }

    @Test
    fun `a group is withheld until every piece has arrived, then handed back once`() {
        val envelope = "y".repeat(1_000)
        val chunks = MessageFragments.split("group-a", envelope, maxWireChars = 150)
        val assembler = FragmentAssembler()

        val results = chunks.map(assembler::accept)

        assertEquals("only the very last piece should complete the group", chunks.size - 1, results.count { it == null })
        assertEquals(envelope, results.last { it != null })
    }

    @Test
    fun `pieces arriving out of order still reassemble correctly`() {
        val envelope = "z".repeat(1_000)
        val chunks = MessageFragments.split("group-b", envelope, maxWireChars = 150)
        val assembler = FragmentAssembler()

        val shuffled = chunks.reversed()
        val results = shuffled.map(assembler::accept)

        assertEquals(envelope, results.last { it != null })
    }

    @Test
    fun `a group past its age limit is discarded rather than held forever`() {
        val envelope = "w".repeat(1_000)
        val chunks = MessageFragments.split("group-c", envelope, maxWireChars = 150)
        var clock = Instant.parse("2024-01-01T00:00:00Z")
        val assembler = FragmentAssembler(maxAgeMillis = 1_000, now = { clock })

        assembler.accept(chunks.first())
        clock = clock.plusMillis(5_000)
        val result = assembler.accept(chunks.last())

        assertNull("the first piece should have aged out, leaving this group incomplete", result)
    }

    @Test
    fun `a group id reused with a different total is refused rather than guessed at`() {
        val assembler = FragmentAssembler()

        assertNull(assembler.accept("F1:g:0:3:aaa"))
        assertNull(assembler.accept("F1:g:0:2:bbb"))
    }

    @Test
    fun `a claimed total past the fragment ceiling is refused`() {
        val assembler = FragmentAssembler()

        assertNull(assembler.accept("F1:g:0:100000:aaa"))
    }
}
