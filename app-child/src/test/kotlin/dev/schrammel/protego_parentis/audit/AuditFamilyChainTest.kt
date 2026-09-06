package dev.schrammel.protego_parentis.audit

import android.content.Context
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.ChainCheck
import dev.schrammel.protego_parentis.sync.AuditReplication
import dev.schrammel.protego_parentis.sync.LocalDeviceId
import dev.schrammel.protego_parentis.sync.ReplicatedStateStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #84: a device's own local chain is only half the promise — the other half
 * is that a *different* phone can check it too, from the copy replication
 * handed it, rather than only trusting what the recording device says about
 * itself. [Audit.checkFamily] is what does that.
 *
 * Genuinely simulating tampering here would mean bypassing
 * [ReplicatedStateStore] entirely and editing its backing preferences file
 * directly, the same way a rooted-phone attacker would — the replication
 * layer's own append-only rule for the audit section (`ReplicationRules.isAppendOnly`)
 * already refuses a revised value for a field once set, even fed straight
 * through `receive()`. That is real protection in its own right, proven
 * elsewhere; what these tests pin is the part unique to #84 — that a
 * legitimately replicated chain verifies, and that a family mid-upgrade
 * (entries with no chain fields at all) is never mistaken for one under
 * attack.
 */
@RunWith(RobolectricTestRunner::class)
class AuditFamilyChainTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ReplicatedStateStore(context).forgetAll()
        context.getSharedPreferences("audit", Context.MODE_PRIVATE).edit().clear().commit()
        LocalDeviceId.useInTests(null)
    }

    @Test
    fun `a device's replicated chain verifies from another device's point of view`() {
        LocalDeviceId.useInTests("DEVICEA")
        Audit.record(context, AuditActor.PARENT, AuditAction.PIN_CHANGED)
        Audit.record(context, AuditActor.CHILD, AuditAction.APP_LIMIT_CHANGED, subject = "Games", before = "30", after = "45")

        LocalDeviceId.useInTests("DEVICEB")

        assertTrue(Audit.checkFamily(context).isIntact)
    }

    @Test
    fun `entries replicated with no chain fields at all are skipped, not broken`() {
        // Simulates a family where another device is still on a build from
        // before #84 — its entries replicate with content but no prev/hash.
        LocalDeviceId.useInTests("DEVICEA")
        AuditReplication(context).record(
            actor = AuditActor.PARENT.name,
            action = AuditAction.PIN_CHANGED.name,
            subject = null,
            before = null,
            after = null,
            previousHash = "",
            hash = "",
        )

        LocalDeviceId.useInTests("DEVICEB")

        assertTrue(Audit.checkFamily(context) is ChainCheck.Intact)
    }
}
