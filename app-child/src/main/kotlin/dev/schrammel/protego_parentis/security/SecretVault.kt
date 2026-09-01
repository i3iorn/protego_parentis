package dev.schrammel.protego_parentis.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the few secrets this app has to keep, using a key it cannot read itself.
 *
 * The family passphrase used to sit in plain text in a preferences file. Android's
 * per-app sandbox keeps that away from other apps, but it is one `adb pull` on a
 * rooted phone away from being read — and once read it is not just this device's
 * secret, it is the whole family's, usable from any phone in the world.
 *
 * Now the passphrase is stored encrypted under an AES key generated inside the
 * Android Keystore and marked non-exportable. On a phone with a secure element
 * that key never leaves the hardware. The improvement is specific and worth being
 * precise about: an attacker with root can still ask this app's identity to
 * decrypt, so they can still learn the passphrase *on that phone*. What they can
 * no longer do is take it away with them, which is what turns one compromised
 * handset into a permanent hole in the family.
 */
object SecretVault {

    /**
     * Encrypts [plaintext] for storage, or returns null if the keystore refuses.
     *
     * Null rather than an exception because the caller's sensible response is to
     * decline to store the secret at all, not to crash — some devices have a
     * keystore that fails after a lock-screen change, and losing a passphrase is
     * recoverable by typing it again.
     */
    fun seal(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        val envelope = ByteArray(1 + iv.size + body.size)
        envelope[0] = iv.size.toByte()
        iv.copyInto(envelope, 1)
        body.copyInto(envelope, 1 + iv.size)

        Base64.encodeToString(envelope, Base64.NO_WRAP)
    }.getOrNull()

    /**
     * Opens a sealed secret.
     *
     * Null covers every way this legitimately fails: a value written before the
     * vault existed, a keystore key invalidated by a factory reset of credentials,
     * or a corrupted file. The caller treats all of them the same way — the
     * passphrase is gone and has to be typed again.
     */
    fun open(sealed: String): String? = runCatching {
        val envelope = Base64.decode(sealed, Base64.NO_WRAP)
        val ivLength = envelope[0].toInt()
        if (ivLength <= 0 || envelope.size <= 1 + ivLength) return null

        val iv = envelope.copyOfRange(1, 1 + ivLength)
        val body = envelope.copyOfRange(1 + ivLength, envelope.size)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /** Whether the keystore is usable at all, so a caller can warn rather than fail. */
    fun isAvailable(): Boolean = runCatching { key() }.isSuccess

    private fun key(): SecretKey = synchronized(LOCK) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // The child device polls with the screen locked, so requiring
                    // authentication here would stop sync working exactly when it
                    // is needed. The protection sought is non-exportability.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "dementor-secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private val LOCK = Any()
}
