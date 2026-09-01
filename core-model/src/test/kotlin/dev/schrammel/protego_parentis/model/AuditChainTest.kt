package dev.schrammel.protego_parentis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AuditChainTest {

    private val start: Instant = Instant.parse("2026-08-24T09:00:00Z")

    @Test
    fun `a chain built in order verifies`() {
        assertTrue(AuditChain.verify(chainOf(5)).isIntact)
    }

    @Test
    fun `an empty history verifies`() {
        assertTrue(AuditChain.verify(emptyList()).isIntact)
    }

    @Test
    fun `editing an entry breaks the chain there`() {
        val entries = chainOf(5).toMutableList()
        entries[2] = entries[2].copy(after = "999")

        assertEquals(ChainCheck.Broken(2), AuditChain.verify(entries))
    }

    /**
     * The edit most worth making, and the reason every field goes into the hash:
     * moving a rule change to a different evening.
     */
    @Test
    fun `moving an entry in time breaks the chain`() {
        val entries = chainOf(4).toMutableList()
        entries[1] = entries[1].copy(at = entries[1].at.minusSeconds(86_400))

        assertEquals(ChainCheck.Broken(1), AuditChain.verify(entries))
    }

    @Test
    fun `removing an entry breaks the chain at the gap`() {
        val entries = chainOf(5).toMutableList()
        entries.removeAt(2)

        assertEquals(ChainCheck.Broken(2), AuditChain.verify(entries))
    }

    @Test
    fun `reordering entries breaks the chain`() {
        val entries = chainOf(4).toMutableList()
        val moved = entries.removeAt(3)
        entries.add(1, moved)

        assertEquals(ChainCheck.Broken(1), AuditChain.verify(entries))
    }

    /**
     * Rewriting an entry *and* the links after it is exactly what an attacker who
     * can run this code would do. It verifies, and the class documentation says so
     * — this is a check against edits from outside the app, not from inside it.
     */
    @Test
    fun `a fully recomputed chain verifies`() {
        val original = chainOf(3)
        val forged = rechain(
            original.toMutableList().apply { this[1] = this[1].copy(after = "999") },
        )

        assertTrue(AuditChain.verify(forged).isIntact)
        assertNotEquals(original[1].hash, forged[1].hash)
    }

    @Test
    fun `a trimmed history verifies from its anchor`() {
        val full = chainOf(6)
        val kept = full.drop(2)

        assertTrue(AuditChain.verify(kept, anchor = full[1].hash).isIntact)
        // Without the anchor the first surviving entry has nothing to follow.
        assertEquals(ChainCheck.Broken(0), AuditChain.verify(kept))
    }

    /** Two different histories must never be able to share a link. */
    @Test
    fun `field boundaries cannot be shifted`() {
        val a = entry(subject = "ab", before = "c")
        val b = entry(subject = "a", before = "bc")

        assertNotEquals(
            AuditChain.link(a, AuditChain.GENESIS),
            AuditChain.link(b, AuditChain.GENESIS),
        )
    }

    @Test
    fun `the same entry after a different history hashes differently`() {
        val single = entry()

        assertNotEquals(
            AuditChain.link(single, AuditChain.GENESIS),
            AuditChain.link(single, "somethingelse"),
        )
    }

    // ---------- helpers ----------

    private fun chainOf(count: Int): List<AuditEntry> =
        rechain((0 until count).map { index -> entry(id = "e$index", seconds = index * 60L) })

    private fun rechain(entries: List<AuditEntry>): List<AuditEntry> {
        var previous = AuditChain.GENESIS
        return entries.map { entry ->
            val linked = entry.copy(
                previousHash = previous,
                hash = AuditChain.link(entry, previous),
            )
            previous = linked.hash
            linked
        }
    }

    private fun entry(
        id: String = "e",
        seconds: Long = 0L,
        subject: String? = "Instagram",
        before: String? = "45",
        after: String? = "30",
    ) = AuditEntry(
        id = id,
        at = start.plusSeconds(seconds),
        actor = AuditActor.PARENT,
        action = AuditAction.APP_LIMIT_CHANGED,
        subject = subject,
        before = before,
        after = after,
    )
}
