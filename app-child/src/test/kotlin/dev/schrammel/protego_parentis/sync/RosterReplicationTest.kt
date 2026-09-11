package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.sync.core.*
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import dev.schrammel.protego_parentis.sync.core.Enrolment
import dev.schrammel.protego_parentis.sync.core.Fingerprints
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.security.LocalDeviceId

/**
 * #45's own done-when list asks for `RosterStore.child()`'s one production
 * caller — [PolicyReplication.defaultChildId] — to be re-checked now that a
 * second managed device can actually be enrolled (#45's own PRs 1-3), not
 * bulk-edited on the assumption it still holds.
 *
 * It does hold: [RosterReplication.pullIntoRoster] is the one path that
 * could otherwise introduce a second child into a device's own roster from
 * replicated state, and it runs every CHILD-role entry through
 * [Enrolment.shouldAccept] exactly as a `DEVICE_VOUCHED` message would —
 * unconditionally, with no branch on the *receiving* device's own role. So a
 * managed phone's own [RosterStore.children] can never grow past itself, and
 * `defaultChildId()`'s single-child assumption is exactly as safe with two
 * managed phones in the family as it always was.
 */
@RunWith(RobolectricTestRunner::class)
class RosterReplicationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun replication() = RosterReplication(context)
    private fun roster() = RosterStore(context)
    private fun state() = ReplicatedStateStore(context)

    @After
    fun tearDown() {
        state().forgetAll()
        roster().forgetAll()
        LocalDeviceId.useInTests(null)
    }

    @Test
    fun `a managed phone's own roster never admits a second child from replicated state`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("SELF-0000-0000")

        // A self-consistent entry (key really does fingerprint to this id),
        // exactly as it would look after a parent who already trusts this
        // second child pushed its own RosterStore via RosterReplication.push
        // — so what refuses it here is the CHILD role on the entry itself,
        // not a malformed key or an unauthorised writer.
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val otherChildId = Fingerprints.of(keys.public.encoded)
        state().writeAll(
            mapOf(
                "roster/${otherChildId.value}/name" to "Other managed phone",
                "roster/${otherChildId.value}/role" to SyncRole.CHILD.name,
                "roster/${otherChildId.value}/key" to Fingerprints.encodeKey(keys.public.encoded),
            ),
            origin = "A-PARENT-DEVICE",
            role = SyncRole.PARENT,
        )

        replication().pullIntoRoster()

        assertTrue(roster().children().isEmpty())
        assertFalse(roster().devices.any { it.id == otherChildId })
    }
}
