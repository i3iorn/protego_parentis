package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the managed phone publishes about its own rules, and when it decides not
 * to bother.
 *
 * These tests exist because of [LocalDeviceId]. Everything here goes through
 * `PolicyReplication.write`, which asks who this phone is and returns an empty
 * list if it cannot find out — and under Robolectric it never could, because
 * `KeystoreSigner` needs a keystore provider Robolectric does not implement. A
 * test written before the seam would have watched every one of these produce
 * nothing and called it a pass.
 */
@RunWith(RobolectricTestRunner::class)
class PolicyReplicationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Spelled out rather than built from [PolicyReplication]'s own constants,
     * and not only because they are private.
     *
     * This is a path on the wire. Phones already in the field hold it in their
     * replicas and compare it in every digest, so renaming it is a compatibility
     * break rather than a refactor. A test written in terms of the constant
     * would follow such a rename without a word; this one does not.
     */
    private val dailyLimitPath = "policy/THISPHONE/dailyLimitMinutes"

    private val thisChildId = DeviceId("THISPHONE")

    @Before
    fun useAKnownIdentity() {
        LocalDeviceId.useInTests("THISPHONE")
        FamilyData.forget(context)
    }

    /** The override outlives the test if it is not put back. */
    @After
    fun restore() {
        FamilyData.forget(context)
        LocalDeviceId.useInTests(null)
    }

    private fun rules() = LocalPolicyStore(context)

    private fun push() = PolicyReplication(context).pushFromLocalPolicy(thisChildId)

    // ---------- the gate ----------

    @Test
    fun `the first push sends the rules`() {
        rules().dailyLimitMinutes = 90

        val ops = push()

        assertTrue(ops.isNotEmpty())
        assertEquals("90", ReplicatedStateStore(context).value(dailyLimitPath))
    }

    /**
     * The enforcement tick runs every few seconds and pushes each time.
     *
     * Flattening the whole policy into two hundred paths to conclude nothing
     * changed is a poor thing to do all day on a battery, so the version is
     * checked first.
     */
    @Test
    fun `pushing again with nothing changed sends nothing`() {
        rules().dailyLimitMinutes = 90
        push()

        assertTrue(push().isEmpty())
    }

    @Test
    fun `changing a rule makes the next push send again`() {
        rules().dailyLimitMinutes = 90
        push()

        rules().dailyLimitMinutes = 45

        assertTrue(push().isNotEmpty())
        assertEquals("45", ReplicatedStateStore(context).value(dailyLimitPath))
    }

    // ---------- the gate against the wipe ----------

    /**
     * Leaving a family and joining another must not leave the new one with no
     * rules at all.
     *
     * This is the defect the gate introduced and the reason it needed a
     * companion. Leaving wipes the replica, but the rules themselves have not
     * changed, so their version has not moved — and the gate reads the version.
     * Without [PolicyReplication.forgetPushed] the managed phone would join its
     * next family and publish nothing whatsoever, leaving its parents on an
     * empty Rules screen until somebody happened to edit something.
     *
     * It is the exact shape of the bug the gate was added alongside fixing,
     * moved one step along, and it was found by reading rather than by anything
     * failing. This is that reading, written down so it cannot come back.
     */
    @Test
    fun `after leaving a family the rules are published to the next one`() {
        rules().dailyLimitMinutes = 90
        push()
        assertTrue("the gate should have closed", push().isEmpty())

        FamilyData.forget(context)

        val ops = push()
        assertTrue("nothing was published to the new family", ops.isNotEmpty())
        assertEquals("90", ReplicatedStateStore(context).value(dailyLimitPath))
    }

    /** The wipe really does empty the replica, so the test above is not vacuous. */
    @Test
    fun `leaving a family empties the replica first`() {
        rules().dailyLimitMinutes = 90
        push()
        assertFalse(ReplicatedStateStore(context).entries.isEmpty())

        FamilyData.forget(context)

        assertTrue(ReplicatedStateStore(context).entries.isEmpty())
    }

    // ---------- what a push is worth without an identity ----------

    /**
     * A phone that cannot say who it is publishes nothing — and does not record
     * that it has.
     *
     * The keystore can genuinely be unavailable: a key invalidated by a
     * biometric change, a restored backup, the moments before first unlock. The
     * write correctly produces nothing, because an op with no origin cannot be
     * attributed and the shared history is built on knowing which device wrote
     * what.
     *
     * The gate must not count that as done. It used to: the version was recorded
     * whether or not anything was published, so a phone whose keystore was
     * briefly unavailable would mark its rules as pushed, publish none of them,
     * and then refuse to try again until somebody happened to edit a rule. Same
     * shape as the bug that came with the gate, one step further along.
     */
    @Test
    fun `a phone with no identity publishes nothing and tries again later`() {
        rules().dailyLimitMinutes = 90
        LocalDeviceId.useInTests(null)

        assertTrue("published without an identity", push().isEmpty())

        LocalDeviceId.useInTests("THISPHONE")

        assertTrue("gave up after one failure", push().isNotEmpty())
        assertEquals("90", ReplicatedStateStore(context).value(dailyLimitPath))
    }
}
