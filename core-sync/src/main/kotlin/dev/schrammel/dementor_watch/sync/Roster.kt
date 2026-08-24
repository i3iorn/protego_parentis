package dev.schrammel.dementor_watch.sync

import java.time.Instant

/** A device this phone has been told to trust, and what it is allowed to say. */
data class KnownDevice(
    val id: DeviceId,
    /** What the family calls it. Chosen on the device itself, changeable. */
    val name: String,
    val role: SyncRole,
    /** SPKI, base64. The identity; [id] is only its fingerprint. */
    val publicKey: String,
    val approvedAt: Instant,
    /** When it last said anything, for showing which phones are still in use. */
    val lastHeardAt: Instant? = null,
)

/**
 * One parent telling the family it has let a device in.
 *
 * Carries the key as well as the id, so a receiver enrols exactly the device that
 * was approved rather than the next one to claim that name.
 */
data class Vouch(
    val id: DeviceId,
    val name: String,
    val role: SyncRole,
    val publicKey: String,
) {
    /**
     * Whether the key really is the one this id names.
     *
     * A vouch is acted on without a human looking at it, so the one check a human
     * would have made — that the fingerprint matches the key — has to be made here
     * instead. Without it a trusted parent could enrol a device under an id that
     * belongs to somebody else.
     */
    fun isConsistent(): Boolean =
        Fingerprints.decodeKey(publicKey)?.let { Fingerprints.of(it) == id } == true
}

/** A validly-signed device that nobody has approved yet. */
data class PendingDevice(
    val id: DeviceId,
    val name: String,
    val role: SyncRole,
    val publicKey: String,
    val firstHeardAt: Instant,
)

/** Why a signed message was not acted on. */
enum class RefusalReason {
    /** No signature, or one this build cannot read. Older builds, or noise. */
    UNSIGNED,

    /** The signature does not match the public key travelling with it. */
    BAD_SIGNATURE,

    /** The key does not match the id claimed for it. */
    ID_MISMATCH,

    /** Signed by a device nobody has approved. */
    NOT_ENROLLED,

    /**
     * Enrolled, but as the other side of the family.
     *
     * This is the check the whole feature exists for: a child device that obtained
     * the family passphrase can still encrypt a message, but it is on the roster
     * as a child, and a child cannot approve a request.
     */
    WRONG_ROLE,
}

sealed interface Admittance {
    /** Signed by a device on the roster, permitted to send this kind of message. */
    data class Accepted(val device: KnownDevice) : Admittance

    /** Correctly signed, but by a device nobody has vouched for yet. */
    data class Unknown(val device: PendingDevice) : Admittance

    data class Refused(val reason: RefusalReason) : Admittance
}

/**
 * Who is allowed to say what.
 *
 * Encryption proves a message came from someone holding the family passphrase.
 * That is a shared secret, so it says nothing about *which* family member sent
 * it — and the family member with the strongest motive to forge an approval is
 * the one whose phone is being limited.
 *
 * So each message type has an expected sender role, and the sender's role comes
 * from the roster rather than from the message. A device announces what it is
 * when it asks to be enrolled; after that, what it claims to be is ignored.
 *
 * Pure, and the roster is passed in, so every rule here is testable without a
 * keystore or a network.
 */
object RosterRules {

    /**
     * The only role permitted to originate each kind of message.
     *
     * An exhaustive `when` on purpose: a new message type has to state who is
     * allowed to send it, and forgetting is a compile error rather than a hole.
     */
    fun expectedSender(type: SyncMessageType): SyncRole? = when (type) {
        SyncMessageType.REQUEST_RAISED -> SyncRole.CHILD
        SyncMessageType.HEARTBEAT -> SyncRole.CHILD
        SyncMessageType.APP_INSTALLED -> SyncRole.CHILD
        SyncMessageType.DAILY_SUMMARY -> SyncRole.CHILD
        SyncMessageType.DEVICE_STOPPING -> SyncRole.CHILD
        SyncMessageType.REQUEST_AUTO_APPROVED -> SyncRole.CHILD
        SyncMessageType.LOCATION_REPORT -> SyncRole.CHILD
        SyncMessageType.PLACE_TRANSITION -> SyncRole.CHILD
        SyncMessageType.SOS_RAISED -> SyncRole.CHILD
        SyncMessageType.SOS_CLEARED -> SyncRole.CHILD
        SyncMessageType.WEB_SUMMARY -> SyncRole.CHILD

        SyncMessageType.REQUEST_DECIDED -> SyncRole.PARENT
        SyncMessageType.BONUS_GRANTED -> SyncRole.PARENT
        SyncMessageType.APP_DECIDED -> SyncRole.PARENT
        SyncMessageType.LOCK_NOW -> SyncRole.PARENT
        SyncMessageType.LOCATE_REQUEST -> SyncRole.PARENT

        // Only a parent may let somebody in. A child device that obtained the
        // passphrase can sign this perfectly well and is refused, which is the
        // point of the roster existing at all.
        SyncMessageType.DEVICE_VOUCHED -> SyncRole.PARENT

        // Either side introduces itself; that is the point of it.
        SyncMessageType.DEVICE_ANNOUNCE -> null

        // A conversation both sides can start. Note what this does *not* relax:
        // the sender still has to be on the roster, still has to sign, and its id
        // still has to be the fingerprint of the key it signed with. "Either role
        // may send this" is not "anyone may send this".
        SyncMessageType.CHAT_MESSAGE -> null
    }

    /**
     * Decides what to do with a message that has already decrypted.
     *
     * [payload] is the signed form as received, so verification runs against the
     * literal bytes rather than a re-encoding.
     */
    fun admit(
        message: SyncMessage,
        payload: SignedPayload,
        roster: Collection<KnownDevice>,
        now: Instant,
    ): Admittance {
        val publicKeyBase64 = message.senderKey
            ?: return Admittance.Refused(RefusalReason.UNSIGNED)
        val publicKey = Fingerprints.decodeKey(publicKeyBase64)
            ?: return Admittance.Refused(RefusalReason.UNSIGNED)

        // The id must be the fingerprint of the key that signed. Otherwise a
        // device could sign honestly while wearing another device's name badge.
        if (Fingerprints.of(publicKey) != DeviceId(message.sender)) {
            return Admittance.Refused(RefusalReason.ID_MISMATCH)
        }
        if (!MessageSignatures.verify(publicKey, payload)) {
            return Admittance.Refused(RefusalReason.BAD_SIGNATURE)
        }

        val known = roster.firstOrNull { it.id.value == message.sender }
            ?: return Admittance.Unknown(
                PendingDevice(
                    id = DeviceId(message.sender),
                    name = message.senderName.orEmpty(),
                    // Claimed, not trusted. It is shown to whoever approves the
                    // device and is what they are agreeing to.
                    role = message.senderRole ?: SyncRole.CHILD,
                    publicKey = publicKeyBase64,
                    firstHeardAt = now,
                ),
            )

        // Belt and braces: an enrolled device that turns up with a different key
        // is either a reinstall or an impersonation, and both need re-approval.
        if (known.publicKey != publicKeyBase64) {
            return Admittance.Refused(RefusalReason.ID_MISMATCH)
        }

        val required = expectedSender(message.type)
        if (required != null && known.role != required) {
            return Admittance.Refused(RefusalReason.WRONG_ROLE)
        }

        return Admittance.Accepted(known.copy(lastHeardAt = now))
    }
}
