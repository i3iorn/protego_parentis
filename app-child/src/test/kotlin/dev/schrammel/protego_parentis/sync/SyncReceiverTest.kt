package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.snapshot
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_OPS
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_POLICY_JSON
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_REVOKED_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_TARGET_LABEL
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_ID
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_KEY
import dev.schrammel.protego_parentis.sync.SyncWireFields.FIELD_VOUCHED_ROLE
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Fingerprints
import dev.schrammel.protego_parentis.sync.core.MessageCrypto
import dev.schrammel.protego_parentis.sync.core.MessageSignatures
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.Signer
import dev.schrammel.protego_parentis.sync.core.StateCodec
import dev.schrammel.protego_parentis.sync.core.StateOp
import dev.schrammel.protego_parentis.sync.core.SyncCodec
import dev.schrammel.protego_parentis.sync.core.SyncKey
import dev.schrammel.protego_parentis.sync.core.SyncMessage
import dev.schrammel.protego_parentis.sync.core.SyncMessageType
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

/**
 * [SyncReceiver] is where every message from another family device is
 * decrypted, replay-checked, role-admitted, and dispatched — and, until now,
 * completely untested. Delivers real signed-and-sealed envelopes through
 * [SyncReceiver.receiveEnvelope], the same entry point production code uses,
 * rather than calling any private dispatch method directly.
 *
 * `applyVouch`/`applyRevoke` used to ask [dev.schrammel.protego_parentis.security.KeystoreSigner]
 * directly for this phone's own id, which Robolectric cannot produce (no
 * `AndroidKeyStore` provider) — see [LocalDeviceId]'s own doc comment for why
 * every other call site in this app already goes through it instead. Switched
 * those two to the same seam so they could be tested at all; behaviour in
 * production is unchanged, since [LocalDeviceId.current] falls through to the
 * real keystore call whenever no test override is set.
 */
@RunWith(RobolectricTestRunner::class)
class SyncReceiverTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now: Instant = Instant.parse("2026-09-12T10:00:00Z")

    private val key: SyncKey = MessageCrypto.deriveKey("correct horse battery staple", "dementor-test-topic")

    private fun publisher() = SyncPublisher(context)
    private fun receiver() = SyncReceiver(context, publisher())
    private fun roster() = RosterStore(context)

    @Before
    fun setUp() {
        LocalDeviceId.useInTests("receiving-device")
    }

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        roster().forgetAll()
    }

    // ---------- helpers ----------

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private class RawSigner(private val keys: KeyPair) : Signer {
        override val publicKey: ByteArray get() = keys.public.encoded
        override fun sign(payload: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(payload)
                sign()
            }
    }

    private fun enroll(keys: KeyPair, role: SyncRole, name: String = "A phone") {
        roster().enrol(
            Vouch(
                id = Fingerprints.of(keys.public.encoded),
                name = name,
                role = role,
                publicKey = Fingerprints.encodeKey(keys.public.encoded),
            ),
            now,
        )
    }

    private fun message(
        keys: KeyPair,
        type: SyncMessageType,
        claimedRole: SyncRole,
        fields: Map<String, String> = emptyMap(),
        sentAt: Instant = now,
    ) = SyncMessage(
        id = "m-${UUID.randomUUID()}",
        type = type,
        sender = Fingerprints.of(keys.public.encoded).value,
        sentAt = sentAt,
        fields = fields,
        senderKey = Fingerprints.encodeKey(keys.public.encoded),
        senderRole = claimedRole,
        senderName = "A phone",
    )

    /** Signs and seals [message] exactly as [SyncPublisher] would, then delivers it. */
    private fun deliver(message: SyncMessage, keys: KeyPair): Boolean {
        val body = SyncCodec.encode(message)
        val plaintext = MessageSignatures.sign(RawSigner(keys), body)
        val envelope = MessageCrypto.seal(key, plaintext)
        return receiver().receiveEnvelope(key, envelope)
    }

    // ---------- applyVouch ----------

    @Test
    fun `a parent accepts a vouch for a new device from an enrolled parent`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val voucher = keyPair()
        enroll(voucher, SyncRole.PARENT, "Mum's phone")
        val newDevice = keyPair()

        val applied = deliver(
            message(
                voucher,
                SyncMessageType.DEVICE_VOUCHED,
                SyncRole.PARENT,
                fields = mapOf(
                    FIELD_VOUCHED_ID to Fingerprints.of(newDevice.public.encoded).value,
                    FIELD_VOUCHED_ROLE to SyncRole.PARENT.name,
                    FIELD_VOUCHED_KEY to Fingerprints.encodeKey(newDevice.public.encoded),
                    FIELD_TARGET_LABEL to "Dad's phone",
                ),
            ),
            voucher,
        )

        assertTrue(applied)
        assertTrue(roster().devices.any { it.id == Fingerprints.of(newDevice.public.encoded) })
    }

    @Test
    fun `a vouch signed by a device on the roster as a child is refused`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val childDevice = keyPair()
        enroll(childDevice, SyncRole.CHILD, "The managed phone")
        val newDevice = keyPair()

        // DEVICE_VOUCHED's expected sender is a parent; a device enrolled as
        // the child claiming otherwise is refused before applyVouch ever runs.
        val applied = deliver(
            message(
                childDevice,
                SyncMessageType.DEVICE_VOUCHED,
                SyncRole.PARENT,
                fields = mapOf(
                    FIELD_VOUCHED_ID to Fingerprints.of(newDevice.public.encoded).value,
                    FIELD_VOUCHED_ROLE to SyncRole.PARENT.name,
                    FIELD_VOUCHED_KEY to Fingerprints.encodeKey(newDevice.public.encoded),
                ),
            ),
            childDevice,
        )

        assertFalse(applied)
        assertTrue(roster().devices.none { it.id == Fingerprints.of(newDevice.public.encoded) })
    }

    // ---------- applyRevoke ----------

    @Test
    fun `a parent removes an enrolled device named in a revoke from another parent`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val voucher = keyPair()
        enroll(voucher, SyncRole.PARENT, "Mum's phone")
        val target = keyPair()
        enroll(target, SyncRole.PARENT, "Grandma's phone")

        val applied = deliver(
            message(
                voucher,
                SyncMessageType.DEVICE_REVOKED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_REVOKED_ID to Fingerprints.of(target.public.encoded).value),
            ),
            voucher,
        )

        assertTrue(applied)
        assertTrue(roster().devices.none { it.id == Fingerprints.of(target.public.encoded) })
    }

    /**
     * [dev.schrammel.protego_parentis.sync.core.Enrolment.shouldAcceptRevoke] is
     * the whole security boundary here: revoking the managed phone stays a
     * local, PIN-gated action, never something a message may cause — including
     * on the managed phone's own receiver.
     */
    @Test
    fun `a revoke naming the managed child device is refused`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val voucher = keyPair()
        enroll(voucher, SyncRole.PARENT, "Mum's phone")
        val child = keyPair()
        enroll(child, SyncRole.CHILD, "The managed phone")

        val applied = deliver(
            message(
                voucher,
                SyncMessageType.DEVICE_REVOKED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_REVOKED_ID to Fingerprints.of(child.public.encoded).value),
            ),
            voucher,
        )

        assertFalse(applied)
        assertTrue(roster().devices.any { it.id == Fingerprints.of(child.public.encoded) })
    }

    // ---------- applyPolicyUpdate ----------

    @Test
    fun `a policy update based on the current version is applied`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val store = LocalPolicyStore(context)
        val currentVersion = store.load().version
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        val snapshot = store.snapshot().copy(version = currentVersion, dailyLimitMinutes = 45)
        val applied = deliver(
            message(
                parent,
                SyncMessageType.POLICY_UPDATED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_POLICY_JSON to snapshot.encode()),
            ),
            parent,
        )

        assertTrue(applied)
        assertEquals(45, store.dailyLimitMinutes)
    }

    @Test
    fun `a policy update based on a stale version is refused, not silently overwritten`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        val store = LocalPolicyStore(context)
        store.dailyLimitMinutes = 20
        val parent = keyPair()
        enroll(parent, SyncRole.PARENT, "Mum's phone")

        // A version this phone's own store has moved past already, simulating a
        // parent editing from a copy that raced a local edit made in the meantime.
        val stale = store.snapshot().copy(version = store.load().version + 1, dailyLimitMinutes = 999)
        val applied = deliver(
            message(
                parent,
                SyncMessageType.POLICY_UPDATED,
                SyncRole.PARENT,
                fields = mapOf(FIELD_POLICY_JSON to stale.encode()),
            ),
            parent,
        )

        assertFalse(applied)
        assertEquals(20, store.dailyLimitMinutes)
    }

    // ---------- withinCapacity (state-ops DoS bound) ----------

    /**
     * [ReplicationRules.capacityOf] bounds how many *new* keys one section may
     * hold, so a device in the family cannot flood every replica by inventing
     * keys forever. Telemetry is the smallest section (500) and is the one the
     * managed phone alone may write, which is also the device this bound
     * matters most for — its own compromise is the threat model.
     */
    @Test
    fun `state ops past a section's capacity are dropped, not applied`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD, "The managed phone")
        val cap = ReplicationRules.capacityOf("${ReplicationRules.TELEMETRY}/x")
        val senderId = Fingerprints.of(child.public.encoded).value

        val ops = (0 until cap + 10).map { index ->
            StateOp(
                path = "${ReplicationRules.TELEMETRY}/key-$index",
                value = "v",
                lamport = index.toLong(),
                origin = senderId,
            )
        }

        val applied = deliver(
            message(
                child,
                SyncMessageType.STATE_OPS,
                SyncRole.CHILD,
                fields = mapOf(FIELD_OPS to StateCodec.encodeOps(ops)),
            ),
            child,
        )

        assertTrue(applied)
        val stored = ReplicatedStateStore(context).entries.keys
            .count { it.startsWith("${ReplicationRules.TELEMETRY}${ReplicationRules.SEPARATOR}") }
        assertEquals("only the first `cap` new keys should have been accepted", cap, stored)
    }

    // ---------- messages from a stranger ----------

    @Test
    fun `a message from a device nobody has vouched for is not applied`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val stranger = keyPair()

        val applied = deliver(
            message(stranger, SyncMessageType.HEARTBEAT, SyncRole.CHILD),
            stranger,
        )

        assertFalse(applied)
    }

    @Test
    fun `an enrolled child's heartbeat is applied by a parent`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        val child = keyPair()
        enroll(child, SyncRole.CHILD)

        val applied = deliver(
            message(child, SyncMessageType.HEARTBEAT, SyncRole.CHILD),
            child,
        )

        // Confirms the full pipeline (decrypt, replay-guard, roster-admit,
        // dispatch) reaches applyAsParent -> rememberHeartbeat for an
        // already-enrolled sender, the ordinary case every poll exercises.
        assertTrue(applied)
        assertTrue(HeartbeatStore(context).latest != null)
    }
}
