package dev.schrammel.protego_parentis.sync

import java.security.MessageDigest
import java.util.Base64

/** Which side of the family a device speaks for. */
enum class SyncRole {
    CHILD,
    PARENT,
    ;

    companion object {
        fun parse(value: String?): SyncRole? = entries.firstOrNull { it.name == value }
    }
}

/**
 * A device's identity on the family topic: the fingerprint of its public key.
 *
 * Derived rather than assigned, so it cannot be claimed. Two devices can pick the
 * same *name*; they cannot pick the same id without the same private key.
 */
@JvmInline
value class DeviceId(val value: String) {
    override fun toString(): String = value
}

/**
 * Turns a public key into something a person can compare out loud.
 *
 * The whole roster design rests on someone reading a code off one phone and
 * checking it against another, so the code has to survive being read aloud badly.
 * Crockford's alphabet drops I, L, O and U: no confusing 1 with I, 0 with O, and
 * no accidental words.
 */
object Fingerprints {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUP = 4

    /** How many characters of the digest to show. */
    private const val LENGTH = 12

    /**
     * A grouped fingerprint such as `K3M9-2QTV-8XBR`.
     *
     * Twelve characters of a SHA-256 digest is sixty bits. Far short of the full
     * hash, and deliberately so: this defends against a stranger publishing to a
     * topic they guessed, not against someone who can grind 2^60 keys *and* has
     * already obtained the family passphrase. Longer than people will actually
     * compare is worse than shorter, because an unread code checks nothing.
     */
    fun of(publicKey: ByteArray): DeviceId {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val text = buildString {
            for (index in 0 until LENGTH) {
                append(ALPHABET[digest[index].toInt() and 0x1F])
            }
        }
        return DeviceId(text.chunked(GROUP).joinToString("-"))
    }

    fun of(publicKeyBase64: String): DeviceId? =
        decodeKey(publicKeyBase64)?.let(::of)

    /** Null for anything that is not base64 — the topic is public and hostile. */
    fun decodeKey(publicKeyBase64: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(publicKeyBase64) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    fun encodeKey(publicKey: ByteArray): String =
        Base64.getEncoder().encodeToString(publicKey)
}
