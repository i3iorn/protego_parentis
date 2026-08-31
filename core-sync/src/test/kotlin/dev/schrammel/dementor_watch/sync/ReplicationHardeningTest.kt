package dev.schrammel.dementor_watch.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a device already inside the family can do to everybody else's replica.
 *
 * Enrolment keeps strangers out; these are the bounds on somebody who got in —
 * a compromised handset, or the managed phone running a modified build. None of
 * them protect against a *trusted* device writing plausible values, which is not
 * something a replicated register can do. They protect against the cheap attacks:
 * exhausting storage, breaking the clock, and writing keys nothing can read back.
 */
class ReplicationHardeningTest {

    private fun op(path: String, value: String? = "x", lamport: Long = 1L) =
        StateOp(path, value, lamport, "AAAA")

    // ---------- the clock ----------

    /**
     * The one that would break ordering everywhere at once. A device claiming
     * Long.MAX_VALUE would be believed by every replica, and the next local tick
     * on each would overflow into negative numbers — after which later writes
     * sort *before* earlier ones and the whole family resolves backwards.
     */
    @Test
    fun `an absurd clock claim is ignored rather than adopted`() {
        val clock = LamportClock()

        clock.observe(Long.MAX_VALUE)
        clock.observe(LamportClock.CEILING + 1)

        assertEquals(1L, clock.tick())
    }

    @Test
    fun `ticking on from the ceiling cannot overflow`() {
        val clock = LamportClock(start = LamportClock.CEILING)

        assertTrue(clock.tick() > 0L)
    }

    @Test
    fun `an ordinary clock claim is still adopted`() {
        val clock = LamportClock()

        clock.observe(500L)

        assertEquals(501L, clock.tick())
    }

    // ---------- what a path may look like ----------

    @Test
    fun `a well-formed path is accepted`() {
        assertTrue(ReplicationRules.isWellFormedPath("policy/dailyLimitMinutes"))
        assertTrue(ReplicationRules.isWellFormedPath("policy/categories/games/packages/com.example.a"))
    }

    /**
     * Segments are read back as identifiers — a category id, a package name, a
     * device id. An empty one would produce a key indistinguishable from its
     * own parent.
     */
    @Test
    fun `empty segments are refused`() {
        listOf("policy//x", "/policy/x", "policy/x/", "//", "/").forEach { path ->
            assertFalse(path, ReplicationRules.isWellFormedPath(path))
        }
    }

    @Test
    fun `a path with no section is refused`() {
        assertFalse(ReplicationRules.isWellFormedPath(""))
        assertFalse(ReplicationRules.isWellFormedPath("policy"))
    }

    @Test
    fun `traversal-looking segments are refused`() {
        assertFalse(ReplicationRules.isWellFormedPath("policy/../telemetry/x"))
        assertFalse(ReplicationRules.isWellFormedPath("policy/./x"))
    }

    @Test
    fun `an over-long path is refused`() {
        val long = "policy/" + "x".repeat(ReplicationRules.MAX_PATH_LENGTH)

        assertFalse(ReplicationRules.isWellFormedPath(long))
    }

    @Test
    fun `an over-long value is refused`() {
        val huge = "y".repeat(ReplicationRules.MAX_VALUE_LENGTH + 1)

        assertFalse(ReplicationRules.isWellFormed(op("chat/1-a/text", huge)))
        assertTrue(ReplicationRules.isWellFormed(op("chat/1-a/text", "a normal message")))
    }

    /** A tombstone has no value, and must not be mistaken for an over-long one. */
    @Test
    fun `a tombstone is well formed`() {
        assertTrue(ReplicationRules.isWellFormed(op("policy/x", value = null)))
    }

    @Test
    fun `a malformed op is refused whoever sent it`() {
        assertFalse(ReplicationRules.accepts(SyncRole.PARENT, op("policy//x")))
        assertFalse(
            ReplicationRules.accepts(
                SyncRole.PARENT,
                op("policy/x", "y".repeat(ReplicationRules.MAX_VALUE_LENGTH + 1)),
            ),
        )
    }

    // ---------- how much one section may hold ----------

    @Test
    fun `every real section has a capacity`() {
        listOf(
            ReplicationRules.POLICY,
            ReplicationRules.ROSTER,
            ReplicationRules.TELEMETRY,
            ReplicationRules.REQUESTS,
            ReplicationRules.CHAT,
            ReplicationRules.AUDIT,
        ).forEach { section ->
            assertTrue(section, ReplicationRules.capacityOf("$section/x") > 0)
        }
    }

    @Test
    fun `an unknown section has no capacity at all`() {
        assertEquals(0, ReplicationRules.capacityOf("somethingNew/x"))
    }

    /**
     * Loose enough that no real family meets them. A cap that a large family
     * could hit would be a bug that only appears after months of ordinary use.
     */
    @Test
    fun `capacities are far above real use`() {
        assertTrue(ReplicationRules.capacityOf("policy/x") >= 10_000)
        assertTrue(ReplicationRules.capacityOf("audit/x") >= 10_000)
        assertTrue(ReplicationRules.capacityOf("roster/x") >= 100)
    }
}
