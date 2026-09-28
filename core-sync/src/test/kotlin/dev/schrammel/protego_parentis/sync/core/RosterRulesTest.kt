package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.security.spec.ECGenParameterSpec

/**
 * The check that closes the hole a shared passphrase leaves open: encryption says
 * a message came from *the family*, and only these rules say which member.
 */
class RosterRulesTest {

    private val now: Instant = Instant.parse("2026-08-24T10:00:00Z")

    private val parentKeys = keyPair()
    private val childKeys = keyPair()

    private val parent = enrolled(parentKeys, SyncRole.PARENT, "Mum's phone")
    private val child = enrolled(childKeys, SyncRole.CHILD, "The phone")

    @Test
    fun `accepts a decision from an enrolled parent`() {
        val (message, payload) = signed(parentKeys, SyncMessageType.REQUEST_DECIDED, SyncRole.PARENT)

        val admittance = RosterRules.admit(message, payload, listOf(parent, child), now)

        assertTrue(admittance is Admittance.Accepted)
        assertEquals(parent.id, (admittance as Admittance.Accepted).device.id)
    }

    @Test
    fun `accepts a heartbeat from the enrolled child`() {
        val (message, payload) = signed(childKeys, SyncMessageType.HEARTBEAT, SyncRole.CHILD)

        assertTrue(RosterRules.admit(message, payload, listOf(parent, child), now) is Admittance.Accepted)
    }

    /**
     * The attack the whole feature exists for. The child device holds the family
     * passphrase, so it can encrypt perfectly well; what it cannot do is be on the
     * roster as a parent.
     */
    @Test
    fun `refuses an approval signed by the child device`() {
        val (message, payload) = signed(childKeys, SyncMessageType.REQUEST_DECIDED, SyncRole.CHILD)

        val admittance = RosterRules.admit(message, payload, listOf(parent, child), now)

        assertEquals(
            Admittance.Refused(RefusalReason.WRONG_ROLE),
            admittance,
        )
    }

    /** Claiming to be a parent in the message changes nothing; the roster decides. */
    @Test
    fun `refuses an approval from the child even when it claims to be a parent`() {
        val (message, payload) = signed(childKeys, SyncMessageType.REQUEST_DECIDED, SyncRole.PARENT)

        assertEquals(
            Admittance.Refused(RefusalReason.WRONG_ROLE),
            RosterRules.admit(message, payload, listOf(parent, child), now),
        )
    }

    @Test
    fun `a stranger is offered for enrolment, not acted on`() {
        val stranger = keyPair()
        val (message, payload) = signed(stranger, SyncMessageType.REQUEST_DECIDED, SyncRole.PARENT)

        val admittance = RosterRules.admit(message, payload, listOf(child), now)

        assertTrue(admittance is Admittance.Unknown)
        assertEquals(
            Fingerprints.of(stranger.public.encoded),
            (admittance as Admittance.Unknown).device.id,
        )
    }

    @Test
    fun `refuses a message whose signature does not match its body`() {
        val (message, payload) = signed(parentKeys, SyncMessageType.REQUEST_DECIDED, SyncRole.PARENT)
        val tampered = payload.copy(body = payload.body + "f.sneaky\tvalue\n")

        assertEquals(
            Admittance.Refused(RefusalReason.BAD_SIGNATURE),
            RosterRules.admit(message, tampered, listOf(parent), now),
        )
    }

    /** Signing honestly while wearing another device's name badge. */
    @Test
    fun `refuses a message whose id is not the fingerprint of its key`() {
        val (message, payload) = signed(parentKeys, SyncMessageType.REQUEST_DECIDED, SyncRole.PARENT)
        val impostor = message.copy(sender = child.id.value)

        assertEquals(
            Admittance.Refused(RefusalReason.ID_MISMATCH),
            RosterRules.admit(impostor, payload, listOf(parent, child), now),
        )
    }

    /** A reinstall produces a new key. It has to be looked at again, not waved through. */
    @Test
    fun `refuses an enrolled device that turns up with a different key`() {
        val replaced = parent.copy(publicKey = Fingerprints.encodeKey(keyPair().public.encoded))
        val (message, payload) = signed(parentKeys, SyncMessageType.REQUEST_DECIDED, SyncRole.PARENT)

        assertEquals(
            Admittance.Refused(RefusalReason.ID_MISMATCH),
            RosterRules.admit(message, payload, listOf(replaced.copy(id = parent.id)), now),
        )
    }

    @Test
    fun `refuses a message with no key at all`() {
        val message = SyncMessage(
            id = "m1",
            type = SyncMessageType.BONUS_GRANTED,
            sender = parent.id.value,
            sentAt = now,
        )
        val payload = SignedPayload(byteArrayOf(1), SyncCodec.encode(message))

        assertEquals(
            Admittance.Refused(RefusalReason.UNSIGNED),
            RosterRules.admit(message, payload, listOf(parent), now),
        )
    }

    /** Either side may introduce itself; that is what an announcement is for. */
    @Test
    fun `an announcement has no required sender role`() {
        assertEquals(null, RosterRules.expectedSender(SyncMessageType.DEVICE_ANNOUNCE))
        assertEquals(SyncRole.CHILD, RosterRules.expectedSender(SyncMessageType.HEARTBEAT))
        assertEquals(SyncRole.PARENT, RosterRules.expectedSender(SyncMessageType.BONUS_GRANTED))
        assertEquals(SyncRole.PARENT, RosterRules.expectedSender(SyncMessageType.PLACE_CREATE_REQUESTED))
        assertEquals(SyncRole.PARENT, RosterRules.expectedSender(SyncMessageType.DIAGNOSTICS_REQUESTED))
        assertEquals(SyncRole.CHILD, RosterRules.expectedSender(SyncMessageType.DIAGNOSTICS_REPORT))
    }

    @Test
    fun `records when an accepted device was last heard`() {
        val (message, payload) = signed(parentKeys, SyncMessageType.BONUS_GRANTED, SyncRole.PARENT)

        val admittance = RosterRules.admit(message, payload, listOf(parent), now)

        assertEquals(now, (admittance as Admittance.Accepted).device.lastHeardAt)
    }

    // ---------- helpers ----------

    /**
     * Who may send each message type, written out in full. The `when` in
     * [RosterRules.expectedSender] makes a new type state a sender; this makes
     * changing an existing one (say, letting the managed phone send LOCK_NOW)
     * a deliberate edit here too rather than a one-line slip.
     */
    @Test
    fun `every message type has the sender role it was designed for`() {
        val child = SyncRole.CHILD
        val parent = SyncRole.PARENT
        val expected = mapOf(
            SyncMessageType.REQUEST_RAISED to child,
            SyncMessageType.HEARTBEAT to child,
            SyncMessageType.APP_INSTALLED to child,
            SyncMessageType.DAILY_SUMMARY to child,
            SyncMessageType.DEVICE_STOPPING to child,
            SyncMessageType.REQUEST_AUTO_APPROVED to child,
            SyncMessageType.LOCATION_REPORT to child,
            SyncMessageType.PLACE_TRANSITION to child,
            SyncMessageType.SOS_RAISED to child,
            SyncMessageType.SOS_CLEARED to child,
            SyncMessageType.WEB_SUMMARY to child,
            SyncMessageType.POLICY_SNAPSHOT to child,
            SyncMessageType.APP_INVENTORY to child,
            SyncMessageType.DIAGNOSTICS_REPORT to child,
            SyncMessageType.REQUEST_DECIDED to parent,
            SyncMessageType.BONUS_GRANTED to parent,
            SyncMessageType.APP_DECIDED to parent,
            SyncMessageType.LOCK_NOW to parent,
            SyncMessageType.LOCATE_REQUEST to parent,
            SyncMessageType.POLICY_REQUESTED to parent,
            SyncMessageType.POLICY_UPDATED to parent,
            SyncMessageType.APP_INVENTORY_REQUESTED to parent,
            SyncMessageType.PLACE_CREATE_REQUESTED to parent,
            SyncMessageType.DIAGNOSTICS_REQUESTED to parent,
            SyncMessageType.DEVICE_VOUCHED to parent,
            SyncMessageType.DEVICE_REVOKED to parent,
            SyncMessageType.FAMILY_DISBANDED to parent,
            SyncMessageType.DEVICE_ANNOUNCE to null,
            SyncMessageType.STATE_OPS to null,
            SyncMessageType.STATE_DIGEST to null,
            SyncMessageType.STATE_REPAIR to null,
            SyncMessageType.CHAT_MESSAGE to null,
        )

        assertEquals(SyncMessageType.entries.toSet(), expected.keys)
        SyncMessageType.entries.forEach { type ->
            assertEquals(type.name, expected.getValue(type), RosterRules.expectedSender(type))
        }
    }

    private fun signed(
        keys: KeyPair,
        type: SyncMessageType,
        claimedRole: SyncRole,
    ): Pair<SyncMessage, SignedPayload> {
        val message = SyncMessage(
            id = "m-${type.name}-${claimedRole.name}",
            type = type,
            sender = Fingerprints.of(keys.public.encoded).value,
            sentAt = now,
            senderKey = Fingerprints.encodeKey(keys.public.encoded),
            senderRole = claimedRole,
            senderName = "A phone",
        )
        val body = SyncCodec.encode(message)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(body.toByteArray(Charsets.UTF_8))
            sign()
        }
        return message to SignedPayload(signature, body)
    }

    private fun enrolled(keys: KeyPair, role: SyncRole, name: String) = KnownDevice(
        id = Fingerprints.of(keys.public.encoded),
        name = name,
        role = role,
        publicKey = Fingerprints.encodeKey(keys.public.encoded),
        approvedAt = now.minusSeconds(3600),
    )

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
}
