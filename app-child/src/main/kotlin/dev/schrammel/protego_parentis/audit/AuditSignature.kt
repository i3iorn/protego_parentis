package dev.schrammel.protego_parentis.audit

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.schrammel.protego_parentis.audit.AuditSignature.sign
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Signs a chain link with a key this device generated and cannot export (#84).
 *
 * [dev.schrammel.protego_parentis.model.AuditChain] hashes with plain SHA-256,
 * which is public: anyone who can write to the preferences file can also
 * recompute a "valid" chain from scratch, because nothing about the hash
 * depends on a secret. That is enough to catch an ordinary edit — a text
 * editor, a corrupted write, a restored backup — but not a deliberate one by
 * whoever can run this app's own code, which [AuditChain]'s own doc comment
 * says plainly it does not attempt.
 *
 * An HMAC keyed by an Android Keystore key raises that bar without closing it
 * entirely — the same honest scoping [SecretVault] already gives its own key.
 * Forging a signature now needs the Keystore to actually perform the HMAC,
 * which needs this app's identity (or root acting as it), not just file
 * access. It does not defeat an attacker who can execute as this app; it
 * defeats one who can only read or write its files.
 *
 * A separate key from [SecretVault]'s: that one is an AES key scoped to
 * encrypting the family passphrase, this is an HMAC key scoped to signing
 * chain links, and mixing the two purposes on one key is how a future change
 * to either accidentally weakens the other.
 */
object AuditSignature {

    /**
     * The one initialised [Mac], kept across entries.
     *
     * A Keystore-backed HMAC key is reusable: only `init` is expensive (a
     * KeyStore round-trip), and every [sign] call was paying it — once per
     * audit entry appended. `Mac.doFinal` resets the instance for reuse, so
     * a single cached Mac is safe; on any failure it is discarded and the
     * next call re-inits rather than carrying a broken instance forward.
     */
    @Volatile
    private var mac: Mac? = null

    /** The signature for [data], or null if the keystore refuses — see [SecretVault.seal] for why null, not an exception. */
    fun sign(data: String): String? = runCatching {
        val current = synchronized(LOCK) {
            mac?.takeIf { it.algorithm == ALGORITHM } ?: Mac.getInstance(ALGORITHM)
                .apply { init(key()) }.also { mac = it }
        }
        current
            .doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }.onFailure { mac = null }.getOrNull()

    /** Whether the keystore is usable at all, so a caller can degrade rather than fail. */
    fun isAvailable(): Boolean = runCatching { key() }.isSuccess

    private fun key(): SecretKey = synchronized(LOCK) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    // Same reasoning as SecretVault's own key: this runs from a
                    // background sync/enforcement path with the screen off, so
                    // requiring authentication here would stop history being
                    // recorded exactly when nobody is looking at the phone.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "dementor-audit-chain-hmac"
    private const val ALGORITHM = "HmacSHA256"

    private val LOCK = Any()
}
