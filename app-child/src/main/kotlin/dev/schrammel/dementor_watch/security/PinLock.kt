package dev.schrammel.dementor_watch.security

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parent's PIN.
 *
 * Stored as a salted PBKDF2 hash, never in plain text. The threat here is not a
 * remote attacker — it is a bright teenager with a rooted phone and a file
 * browser, and a plain-text PIN or a bare SHA-256 would not survive either.
 *
 * Verification is constant-time so the check cannot be attacked by timing.
 */
class PinLock(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val isSet: Boolean get() = prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    fun set(pin: String) {
        require(pin.length >= MIN_LENGTH) { "PIN must be at least $MIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        prefs.edit(commit = true) {
            putString(KEY_SALT, encode(salt))
            putString(KEY_HASH, encode(hash(pin, salt)))
        }
    }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let(::decode) ?: return false
        val expected = prefs.getString(KEY_HASH, null)?.let(::decode) ?: return false
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    /** Only ever called after a successful [verify]. */
    fun clear() {
        prefs.edit(commit = true) {
            remove(KEY_SALT)
            remove(KEY_HASH)
        }
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(value) }.getOrNull()

    companion object {
        const val MIN_LENGTH = 4

        private const val FILE_NAME = "security"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATIONS = 150_000
        private const val KEY_BITS = 256
        private const val SALT_BYTES = 16
    }
}
