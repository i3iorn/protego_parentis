package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an op arriving from another device may claim about itself.
 *
 * Authorisation is by the device that sent an op; these pin the claims the op
 * carries on top of that — its clock, its origin, and the time in its id — so
 * an enrolled device cannot use them to pin values, speak as someone else, or
 * fill a section with entries that never expire.
 */
class ReplicationProvenanceTest {

    private val alice = DeviceId("AAAA-AAAA-AAAA")
    private val bob = DeviceId("BBBB-BBBB-BBBB")
    private val now = 1_800_000_000L

    private fun chatId(author: DeviceId?, seconds: Long = now) =
        ReplicationRules.streamId(seconds, "abc123", author?.value)

    private fun op(path: String, origin: DeviceId, lamport: Long = 1L, value: String? = "hi") =
        StateOp(path, value, lamport, origin.value)

    private fun incoming(op: StateOp, sender: DeviceId, role: SyncRole = SyncRole.PARENT) =
        ReplicationRules.acceptsIncoming(role, op, sender, now)

    // ---------- the clock ----------

    @Test
    fun `an op stamped beyond the clock ceiling is refused`() {
        val pinned = op("policy/x/limit", alice, lamport = Long.MAX_VALUE)

        assertFalse(ReplicationRules.isWellFormed(pinned))
        assertFalse(incoming(pinned, alice))
    }

    @Test
    fun `an op at the ceiling itself is still accepted`() {
        assertTrue(incoming(op("policy/x/limit", alice, lamport = LamportClock.CEILING), alice))
    }

    // ---------- who wrote it ----------

    @Test
    fun `a chat line sent by one device naming another as its origin is refused`() {
        val forged = op("chat/${chatId(author = null)}/text", origin = alice)

        assertFalse(incoming(forged, sender = bob))
    }

    @Test
    fun `a first-hand chat line under an older id is accepted`() {
        assertTrue(incoming(op("chat/${chatId(author = null)}/text", origin = alice), sender = alice))
    }

    @Test
    fun `nobody but its author may write under an id that names one`() {
        val path = "chat/${chatId(author = alice)}/text"

        assertTrue(incoming(op(path, origin = alice), sender = alice))
        assertFalse(incoming(op(path, origin = bob), sender = bob))
        assertFalse(incoming(op(path, origin = bob, value = null), sender = bob))
    }

    @Test
    fun `audit entries are held to the same authorship`() {
        val path = "audit/${chatId(author = alice)}/action"

        assertFalse(incoming(op(path, origin = bob, lamport = 0L), sender = bob))
    }

    @Test
    fun `sections without attribution may still be relayed`() {
        assertTrue(incoming(op("policy/x/limit", origin = alice), sender = bob))
    }

    @Test
    fun `the author is read back from an id that names one, and not from an older id`() {
        assertEquals(alice.value, ReplicationRules.authorOf("chat/${chatId(alice)}/text"))
        assertNull(ReplicationRules.authorOf("chat/${chatId(null)}/text"))
        assertEquals(now, ReplicationRules.createdAt("chat/${chatId(alice)}/text"))
    }

    // ---------- when it claims to be from ----------

    @Test
    fun `a stream entry dated far in the future is refused`() {
        val farAhead = chatId(alice, seconds = now + 10L * 365 * 24 * 60 * 60)

        assertFalse(incoming(op("chat/$farAhead/text", origin = alice), sender = alice))
    }

    @Test
    fun `a stream entry from a slightly fast clock is accepted`() {
        val slightlyAhead = chatId(alice, seconds = now + 60)

        assertTrue(incoming(op("chat/$slightlyAhead/text", origin = alice), sender = alice))
    }
}
