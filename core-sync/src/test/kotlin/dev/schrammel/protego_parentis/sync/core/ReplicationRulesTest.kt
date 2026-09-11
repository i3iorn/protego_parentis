package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
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

    private val childA = DeviceId("CHILD_A")
    private val childB = DeviceId("CHILD_B")

    // ---------- the rule the product rests on ----------

    /**
     * A parent may write any child's rules; the managed phone may write only
     * its own subtree — `policy/<its own id>/…`.
     *
     * The rules live on the managed phone and can be edited standing at it,
     * behind the parent PIN. Refusing its writes meant a parent's phone never
     * learned them at all and the two replicas could never agree — so the
     * boundary for one child's own rules is the PIN rather than the device.
     * See [ReplicationRules.mayWrite] for what that gives up, and why the
     * scope is nonetheless narrowed to the child's own subtree now that a
     * family can have more than one managed phone.
     */
    @Test
    fun `either phone may write a child's rules, within that child's subtree`() {
        listOf(
            "policy/${childA.value}/dailyLimitMinutes",
            "policy/${childA.value}/bedtime/enabled",
            "policy/${childA.value}/categories/games/limitMinutes",
            "policy/${childA.value}/appLimits/com.example.a",
        ).forEach { path ->
            assertTrue(path, ReplicationRules.mayWrite(SyncRole.CHILD, path, childA))
            assertTrue(path, ReplicationRules.mayWrite(SyncRole.PARENT, path))
        }
    }

    /**
     * The scoping that per-child address space exists for: a second managed
     * phone in the family may not touch the first one's rules, even though
     * both write the same [ReplicationRules.POLICY] section.
     */
    @Test
    fun `a child may not write another child's policy subtree`() {
        val path = "policy/${childA.value}/dailyLimitMinutes"
        assertFalse(ReplicationRules.mayWrite(SyncRole.CHILD, path, childB))
        assertTrue(ReplicationRules.mayWrite(SyncRole.PARENT, path, childB))
    }

    /** No verified writer id at all is refused, not silently granted. */
    @Test
    fun `a child with no established writer id may not write policy`() {
        assertFalse(ReplicationRules.mayWrite(SyncRole.CHILD, "policy/${childA.value}/dailyLimitMinutes"))
    }

    /**
     * The rule that does *not* soften, and the reason the one above safely can.
     *
     * A phone that could write the roster could admit a device of its own, and
     * that device could then write everything a parent may write. That is an
     * escalation rather than a view being wrong, so the managed phone is kept
     * out of it exactly as before.
     */
    @Test
    fun `the managed phone may not write the roster`() {
        assertFalse(ReplicationRules.mayWrite(SyncRole.CHILD, "roster/AAAA/role"))
        assertFalse(ReplicationRules.mayWrite(SyncRole.CHILD, "roster/BBBB/key"))
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
            ReplicationRules.accepts(SyncRole.PARENT, op("policy/${childA.value}/dailyLimitMinutes", "90", 2L)),
        )
    }

    /**
     * The whole boundary in one place, as a table — every section except
     * [ReplicationRules.POLICY], which is no longer a plain "these roles may
     * write this section" rule now that it is scoped per child. See the
     * dedicated policy tests above for that one.
     *
     * Worth pinning explicitly because of how the failure shows up when it is
     * wrong. Each adapter writes its own section locally, and a local write is
     * not authorised against these rules — only what arrives from a peer is. So
     * a section a device writes but its peers refuse does not fail loudly: the
     * two replicas simply never agree, the digest finds the same difference on
     * every exchange, and they repair each other forever over a value that will
     * be refused again. That was a real bug, and it cost continuous traffic on
     * a phone whose data allowance is the reason the wire format is compressed
     * at all.
     *
     * Changing a row here means checking that whichever device writes that
     * section is still permitted to.
     */
    @Test
    fun `each section says exactly who may write it`() {
        val expected = mapOf(
            // Written only by parents, and pulled into trust by everyone.
            ReplicationRules.ROSTER to setOf(SyncRole.PARENT),
            // Written only by the phone that can actually observe itself.
            ReplicationRules.TELEMETRY to setOf(SyncRole.CHILD),
            ReplicationRules.REQUESTS to setOf(SyncRole.CHILD, SyncRole.PARENT),
            ReplicationRules.CHAT to setOf(SyncRole.CHILD, SyncRole.PARENT),
            ReplicationRules.AUDIT to setOf(SyncRole.CHILD, SyncRole.PARENT),
        )

        expected.forEach { (section, writers) ->
            val actual = listOf(SyncRole.CHILD, SyncRole.PARENT)
                .filter { ReplicationRules.mayWrite(it, "$section/x") }
                .toSet()
            assertEquals(section, writers, actual)
        }
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

    // ---------- consensus: a parent-only conversation ----------

    /** The managed phone has no vote in the family's own structure. */
    @Test
    fun `the managed phone may not write consensus at all`() {
        listOf(
            "consensus/1/kind",
            "consensus/1/target",
            "consensus/1/approvals/AAAA",
            "consensus/1/cancelled",
        ).forEach { path ->
            assertFalse(path, ReplicationRules.mayWrite(SyncRole.CHILD, path))
        }
    }

    /** Any parent may raise a proposal and its fixed fields. */
    @Test
    fun `any parent may write a proposal's fixed fields`() {
        listOf("consensus/1/kind", "consensus/1/target", "consensus/1/proposedBy", "consensus/1/proposedAt")
            .forEach { path ->
                assertTrue(path, ReplicationRules.mayWrite(SyncRole.PARENT, path))
            }
    }

    /**
     * Approval is self-attestation: a parent may only ever claim its own
     * approval, never write it into another parent's register.
     */
    @Test
    fun `a parent may approve for itself but not on another parent's behalf`() {
        val own = op("consensus/1/approvals/AAAA", "1")
        val onBehalfOfSomeoneElse = op("consensus/1/approvals/BBBB", "1")

        assertTrue(ReplicationRules.accepts(SyncRole.PARENT, own, DeviceId("AAAA")))
        assertFalse(ReplicationRules.accepts(SyncRole.PARENT, onBehalfOfSomeoneElse, DeviceId("AAAA")))
    }

    /** With no verified writer id at all, nothing is self-attested. */
    @Test
    fun `an approval with no verified writer id is refused`() {
        val approval = op("consensus/1/approvals/AAAA", "1")

        assertFalse(ReplicationRules.accepts(SyncRole.PARENT, approval, writerId = null))
    }

    /**
     * A tombstone is the one write to an approval register that is not
     * self-attested — clearing somebody else's approval when a proposal
     * commits has to be doable by whichever device notices, not only by the
     * approver's own device.
     */
    @Test
    fun `any parent may tombstone another parent's approval, but not fabricate one`() {
        val tombstone = op("consensus/1/approvals/AAAA", value = null)
        val fabricated = op("consensus/1/approvals/AAAA", "1")

        assertTrue(ReplicationRules.accepts(SyncRole.PARENT, tombstone, DeviceId("BBBB")))
        assertFalse(ReplicationRules.accepts(SyncRole.PARENT, fabricated, DeviceId("BBBB")))
    }

    /**
     * The `enrolled` register works the same way as `approvals`, and for a
     * parallel reason: a parent claiming another parent's device had already
     * enrolled a second managed phone locally would let that proposal's
     * cleanup fire before every parent's own roster actually held the child.
     */
    @Test
    fun `a parent may mark itself enrolled but not another parent`() {
        val own = op("consensus/1/enrolled/AAAA", "1")
        val onBehalfOfSomeoneElse = op("consensus/1/enrolled/BBBB", "1")

        assertTrue(ReplicationRules.accepts(SyncRole.PARENT, own, DeviceId("AAAA")))
        assertFalse(ReplicationRules.accepts(SyncRole.PARENT, onBehalfOfSomeoneElse, DeviceId("AAAA")))
    }

    /** Same tombstone exemption as `approvals`, for the same reason. */
    @Test
    fun `any parent may tombstone another parent's enrolled register, but not fabricate one`() {
        val tombstone = op("consensus/1/enrolled/AAAA", value = null)
        val fabricated = op("consensus/1/enrolled/AAAA", "1")

        assertTrue(ReplicationRules.accepts(SyncRole.PARENT, tombstone, DeviceId("BBBB")))
        assertFalse(ReplicationRules.accepts(SyncRole.PARENT, fabricated, DeviceId("BBBB")))
    }

    /**
     * The removal timer's cancellation is deliberately not self-attestation —
     * any current parent, including the one being removed, may stop it. See
     * ReplicationRules.mayWrite's CONSENSUS branch: the section-level "any
     * parent" rule already covers this, since `cancelled` is not under
     * `approvals/`.
     */
    @Test
    fun `cancelling a removal timer is not restricted to any one parent`() {
        val cancelledByTarget = op("consensus/1/cancelled", "1")

        assertTrue(ReplicationRules.accepts(SyncRole.PARENT, cancelledByTarget, DeviceId("THE_TARGET")))
    }
}
