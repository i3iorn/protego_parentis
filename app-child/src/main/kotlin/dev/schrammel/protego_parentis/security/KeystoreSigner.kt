package dev.schrammel.protego_parentis.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.Fingerprints
import dev.schrammel.protego_parentis.sync.core.Signer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's signing identity, held in the Android Keystore.
 *
 * The private key is generated *inside* the keystore and marked non-exportable,
 * so it never exists in this process's memory and cannot be copied off the device
 * even by someone holding root. That is the property that makes the roster worth
 * anything: an attacker who reads every file on the phone still cannot sign as
 * this device from another one.
 *
 * ECDSA over P-256, because it is available on every version this app supports
 * and is generated in hardware where the phone has a secure element. Ed25519
 * would be a slightly nicer choice on paper and is not reliably available here.
 *
 * The key is created on first use and then never changes. Losing it — a reinstall,
 * a restored backup — means a new identity, which the other phones will see as an
 * unknown device asking to be enrolled again. That is the correct outcome: a
 * device that cannot prove it is the old one should not be trusted as the old one.
 */
class KeystoreSigner(private val alias: String = DEFAULT_ALIAS) : Signer {

    override val publicKey: ByteArray
        get() = entry().certificate.publicKey.encoded

    val deviceId: DeviceId get() = Fingerprints.of(publicKey)

    val publicKeyBase64: String get() = Fingerprints.encodeKey(publicKey)

    override fun sign(payload: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(entry().privateKey as PrivateKey)
            update(payload)
            sign()
        }

    /**
     * The keystore entry, created on first use.
     *
     * Synchronised because the enforcement worker, the sync executor and the UI
     * can all reach for the identity at once on a fresh install, and two
     * simultaneous generations would leave half the app signing with a key the
     * other half had already replaced.
     */
    private fun entry(): KeyStore.PrivateKeyEntry = synchronized(LOCK) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.let { return it }

        generate()
        keyStore.load(null)
        keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    private fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    // Deliberately not requiring authentication: the child device
                    // signs heartbeats with the screen locked, which is exactly
                    // when a parent most wants to hear from it.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKeyPair()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "dementor-device-identity"
        const val CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        val LOCK = Any()
    }
}
