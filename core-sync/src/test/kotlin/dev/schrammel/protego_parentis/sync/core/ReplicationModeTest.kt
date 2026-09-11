package dev.schrammel.protego_parentis.sync.core

import dev.schrammel.protego_parentis.sync.core.ReplicationRules.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much each section is worth spending data on.
 *
 * These are the rules that keep the protocol cheap. Getting one wrong does not
 * break correctness — it makes every exchange find a difference and ask for a
 * repair, which costs far more than the ops it was meant to save, and would show
 * up as an unexplained appetite for mobile data rather than as a failure.
 */
class ReplicationModeTest {

    private val now = 1_700_000_000L

    @Test
    fun `rules and roster must be identical everywhere`() {
        assertEquals(ReplicationMode.CONVERGING, ReplicationRules.modeOf("policy/dailyLimitMinutes"))
        assertEquals(ReplicationMode.CONVERGING, ReplicationRules.modeOf("roster/AAAA/role"))
    }

    @Test
    fun `streams are kept in step only recently`() {
        listOf("chat/1-a/text", "requests/1-a/state", "audit/1-a/action").forEach { path ->
            assertEquals(path, ReplicationMode.RECENT, ReplicationRules.modeOf(path))
        }
    }

    /** The whole reason the modes exist: telemetry changes faster than it converges. */
    @Test
    fun `telemetry is never compared`() {
        assertEquals(ReplicationMode.VOLATILE, ReplicationRules.modeOf("telemetry/used"))
        assertFalse(ReplicationRules.isDigested("telemetry/used", now))
    }

    @Test
    fun `converging sections are always compared`() {
        assertTrue(ReplicationRules.isDigested("policy/dailyLimitMinutes", now))
        assertTrue(ReplicationRules.isDigested("roster/AAAA/role", now))
    }

    @Test
    fun `a stream entry inside the window is compared`() {
        val recent = ReplicationRules.streamId(now - 60, "abc")

        assertTrue(ReplicationRules.isDigested("chat/$recent/text", now))
    }

    @Test
    fun `a stream entry outside the window is not compared`() {
        val old = ReplicationRules.streamId(now - ReplicationRules.RECENT_WINDOW_SECONDS - 1, "abc")

        assertFalse(ReplicationRules.isDigested("chat/$old/text", now))
    }

    /**
     * An id from a build that numbered them differently. Compared rather than
     * dropped: being too thorough wastes a little data, and dropping it would
     * silently stop two phones ever reconciling that entry.
     */
    @Test
    fun `an entry whose id carries no time is compared anyway`() {
        assertTrue(ReplicationRules.isDigested("chat/legacy-id/text", now))
        assertNull(ReplicationRules.createdAt("chat/legacy-id/text"))
    }

    // ---------- pruning ----------

    /**
     * The safety margin that makes pruning sound. An entry has to be out of the
     * digest well before anything deletes it — otherwise one replica drops what
     * another is still comparing, and they disagree with no way to repair it.
     */
    @Test
    fun `an entry leaves the digest long before it becomes prunable`() {
        val justOutOfWindow =
            ReplicationRules.streamId(now - ReplicationRules.RECENT_WINDOW_SECONDS - 1, "a")

        assertFalse(ReplicationRules.isDigested("chat/$justOutOfWindow/text", now))
        assertFalse(ReplicationRules.isPrunable("chat/$justOutOfWindow/text", now))
    }

    @Test
    fun `a long-expired entry is prunable`() {
        val ancient =
            ReplicationRules.streamId(now - ReplicationRules.RECENT_WINDOW_SECONDS * 3, "a")

        assertTrue(ReplicationRules.isPrunable("chat/$ancient/text", now))
    }

    /** Rules and roster are never dropped, however old the family is. */
    @Test
    fun `converging state is never prunable`() {
        assertFalse(ReplicationRules.isPrunable("policy/dailyLimitMinutes", now))
        assertFalse(ReplicationRules.isPrunable("roster/AAAA/role", now))
    }

    @Test
    fun `telemetry is never prunable, being overwritten rather than accumulated`() {
        assertFalse(ReplicationRules.isPrunable("telemetry/used", now))
    }

    // ---------- ids ----------

    @Test
    fun `a stream id round-trips its creation time`() {
        val id = ReplicationRules.streamId(now, "abc123")

        assertEquals(now, ReplicationRules.createdAt("chat/$id/text"))
    }

    @Test
    fun `a path with no id segment has no creation time`() {
        assertNull(ReplicationRules.createdAt("chat"))
        assertNull(ReplicationRules.createdAt(""))
    }
}
