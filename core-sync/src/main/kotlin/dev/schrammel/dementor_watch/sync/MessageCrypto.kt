package dev.schrammel.dementor_watch.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A key derived from the family's passphrase. Hold it, do not re-derive per message. */
class SyncKey internal constructor(internal val bytes: ByteArray)

/** Why a received message could not be opened. */
enum class DecryptFailure {
    /** Not our format at all — someone else publishing to the same public topic. */
    MALFORMED,

    /** A version this build does not know how to read. */
    UNSUPPORTED_VERSION,

    /** Wrong key, or the message was altered in transit. Indistinguishable, by design. */
    NOT_AUTHENTIC,
}

sealed interface DecryptResult {
    data class Opened(val plaintext: String) : DecryptResult
    data class Failed(val reason: DecryptFailure) : DecryptResult
}

/**
 * End-to-end encryption for everything that leaves the device.
 *
 * The transport is a public pub/sub topic: anyone who learns the topic name can
 * read every message published to it, and can publish their own. So the topic is
 * treated as hostile, and confidentiality and authenticity both come from this
 * layer rather than from the server.
 *
 * AES-256-GCM. Authenticated encryption is not optional here — with plain
 * encryption anyone could publish garbage, or flip bits in a real message, and
 * the receiving phone would act on the result. GCM's tag means a message that was
 * not produced by someone holding the key simply fails to open.
 *
 * The key is stretched from a human-chosen passphrase with PBKDF2, because a
 * passphrase a parent will actually type is nowhere near 256 bits of entropy.
 */
object MessageCrypto {

    /** Bumped if the envelope format ever changes; old readers reject what they cannot parse. */
    private const val VERSION: Byte = 1

    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    /** Twelve bytes is the size GCM is defined for; other sizes weaken it. */
    private const val NONCE_BYTES = 12

    private const val PBKDF2_ITERATIONS = 210_000

    /** Shortest passphrase we will derive from. Shorter is not a secret. */
    const val MIN_PASSPHRASE_LENGTH = 12

    /**
     * Stretches [passphrase] into a key.
     *
     * [salt] must be the same on every device in the family, so the shared topic
     * name is used: it is not secret, and a salt does not need to be. Its job is
     * to stop one precomputed table working against every family at once.
     */
    fun deriveKey(passphrase: String, salt: String): SyncKey {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }
        require(salt.isNotBlank()) { "salt must not be blank" }

        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            KEY_BITS,
        )
        return SyncKey(SecretKeyFactory.getInstance(KEY_ALGORITHM).generateSecret(spec).encoded)
    }

    /**
     * Encrypts [plaintext] into a self-contained, transport-safe string.
     *
     * Layout: version byte, then the nonce, then ciphertext and tag, base64'd.
     * A fresh random nonce every time — reusing one under the same key is the
     * classic way to destroy GCM entirely.
     */
    fun seal(key: SyncKey, plaintext: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.bytes, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
        }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val envelope = ByteArray(1 + nonce.size + body.size)
        envelope[0] = VERSION
        nonce.copyInto(envelope, 1)
        body.copyInto(envelope, 1 + nonce.size)

        return Base64.getEncoder().encodeToString(envelope)
    }

    /**
     * Opens a message, or explains why it could not be opened.
     *
     * Never throws. A public topic will contain other people's traffic and
     * whatever anyone chooses to publish, so unopenable input is the normal case
     * and must not be able to crash the receiver.
     */
    fun open(key: SyncKey, envelope: String): DecryptResult {
        val raw = runCatching { Base64.getDecoder().decode(envelope) }.getOrNull()
            ?: return DecryptResult.Failed(DecryptFailure.MALFORMED)

        if (raw.size <= 1 + NONCE_BYTES) return DecryptResult.Failed(DecryptFailure.MALFORMED)
        if (raw[0] != VERSION) return DecryptResult.Failed(DecryptFailure.UNSUPPORTED_VERSION)

        val nonce = raw.copyOfRange(1, 1 + NONCE_BYTES)
        val body = raw.copyOfRange(1 + NONCE_BYTES, raw.size)

        return try {
            val cipher = Cipher.getInstance(CIPHER).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key.bytes, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
            }
            DecryptResult.Opened(String(cipher.doFinal(body), Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            // Wrong key or tampered message. Deliberately not distinguished: telling
            // an attacker which one it was is free information.
            DecryptResult.Failed(DecryptFailure.NOT_AUTHENTIC)
        } catch (_: GeneralSecurityFailure) {
            DecryptResult.Failed(DecryptFailure.NOT_AUTHENTIC)
        }
    }
}

/** Alias so the catch above reads clearly; every JCE failure lands here. */
private typealias GeneralSecurityFailure = java.security.GeneralSecurityException
