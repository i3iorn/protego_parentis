package dev.schrammel.dementor_watch.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The PBKDF2 hashing [PinLock] stores against.
 *
 * Pulled out on its own so the cryptographic core — the part an attacker
 * actually has to defeat — can be exercised in a plain unit test. [PinLock]
 * itself needs a real Android `Context` for the `SharedPreferences` it stores
 * the result in, which this module's test setup has no way to fake.
 */
internal object PinHash {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256
    const val SALT_BYTES = 16

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)

    fun of(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    /** Constant-time by construction — [MessageDigest.isEqual] never short-circuits. */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(of(pin, salt), expected)
}
