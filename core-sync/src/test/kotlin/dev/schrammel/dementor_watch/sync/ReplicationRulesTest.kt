package dev.schrammel.dementor_watch.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust boundary, now that every device writes to the same state.
 *
 * Symmetric replication is the dangerous part of this design: the managed phone
 * became a first-class writer, and the product only works if it still cannot
 * change what is imposed on it. These are the tests that would fail if that
 * ever quietly stopped being true.
 */
class ReplicationRulesTest {

    private fun op(path: String, value: String? = "x", lamport: Long = 1L) =
        StateOp(path, value, lamport, "AAAA")

    // ---------- the rule the product rests on ----------

    @Test
    fun `the managed phone may not write the rules imposed on it`() {
        listOf(
            "policy/dailyLimitMinutes",
            "policy/bedtime/enabled",
            "policy/categories/games/limitMinutes",
            "policy/appLimits/com.example.a",
        ).forEach { path ->
            assertFalse(path, ReplicationRules.mayWrite(SyncRole.CHILD, path))
            assertTrue(path, ReplicationRules.mayWrite(SyncRole.PARENT, path))
        }
    }

    @Test
    fun `the managed phone may not write the roster`() {
        assertFalse(ReplicationRules.mayWrite(SyncRole.CHILD, "roster/AAAA/role"))
        assertTrue(ReplicationRules.mayWrite(SyncRole.PARENT, "roster/AAAA/role"))
    }

    /**
     * The mirror of the rule above. A parent phone inventing a quiet afternoon
     * would be a lie the child had no way to contradict, because only the
     * managed phone can see what happened on the managed phone.
     */
    @Test
    fun `only the managed phone may report what it observed`() {
        listOf("telemetry/usedMinutes", "telemetry/battery", "telemetry/position/place").forEach { path ->
            assertTrue(path, ReplicationRules.mayWrite(SyncRole.CHILD, path))
            assertFalse(path, ReplicationRules.mayWrite(SyncRole.PARENT, path))
        }
    }

    @Test
    fun `both sides may talk, and both may raise a request`() {
        listOf("chat/1/text", "requests/1/minutes").forEach { path ->
            assertTrue(path, ReplicationRules.mayWrite(SyncRole.CHILD, path))
            assertTrue(path, ReplicationRules.mayWrite(SyncRole.PARENT, path))
        }
    }

    /** Raising a request is the child's; answering one is emphatically not. */
    @Test
    fun `the managed phone may not answer its own request`() {
        listOf(
            "requests/1/state",
            "requests/1/decidedBy",
            "requests/1/decidedAt",
            "requests/1/note",
        ).forEach { path ->
            assertFalse(path, ReplicationRules.mayWriteRequestField(SyncRole.CHILD, path))
            assertTrue(path, ReplicationRules.mayWriteRequestField(SyncRole.PARENT, path))
        }
    }

    @Test
    fun `the managed phone may still write the parts of a request it owns`() {
        listOf("requests/1/minutes", "requests/1/reason", "requests/1/target").forEach { path ->
            assertTrue(path, ReplicationRules.mayWriteRequestField(SyncRole.CHILD, path))
        }
    }

    // ---------- the log nobody may revise ----------

    /**
     * Authorisation says who may write the log at all. Whether a later write can
     * *revise* an entry is a merge question, deliberately not decided here — see
     * the order-independence tests in ReplicationTest for why.
     */
    @Test
    fun `both roles may add to the shared history`() {
        assertTrue(ReplicationRules.accepts(SyncRole.PARENT, op("audit/1-a/action", "X")))
        assertTrue(ReplicationRules.accepts(SyncRole.CHILD, op("audit/1-a/action", "X")))
    }

    @Test
    fun `the shared history is an append-only section`() {
        assertTrue(ReplicationRules.isAppendOnly("audit/1-a/action"))
        assertFalse(ReplicationRules.isAppendOnly("policy/dailyLimitMinutes"))
        assertFalse(ReplicationRules.isAppendOnly("chat/1-a/text"))
    }

    @Test
    fun `ordinary paths may be rewritten freely`() {
        assertTrue(
            ReplicationRules.accepts(SyncRole.PARENT, op("policy/dailyLimitMinutes", "90", 2L)),
        )
    }

    // ---------- anything unrecognised ----------

    /**
     * A path from a newer build is refused rather than stored. This build cannot
     * police a section it has never heard of, and accepting it would let the
     * first device to invent a section own it.
     */
    @Test
    fun `an unknown section is refused for everybody`() {
        listOf("somethingNew/x", "", "/", "policyish/x").forEach { path ->
            assertFalse(path, ReplicationRules.mayWrite(SyncRole.PARENT, path))
            assertFalse(path, ReplicationRules.mayWrite(SyncRole.CHILD, path))
        }
    }

    /** A sender whose role the roster could not establish may write nothing at all. */
    @Test
    fun `a device with no established role writes nothing`() {
        listOf("policy/x", "chat/1/text", "telemetry/x", "requests/1/minutes", "audit/1/x")
            .forEach { path ->
                assertFalse(path, ReplicationRules.mayWrite(null, path))
            }
    }
}
