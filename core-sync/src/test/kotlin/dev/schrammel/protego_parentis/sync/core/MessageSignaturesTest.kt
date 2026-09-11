package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Stops a child who has the family passphrase from forging a parent's approval.
 *
 * Encryption alone proves only membership in the family; this is what proves
 * *which* member sent a message, so it is the single highest-priority thing in
 * this module to have watertight.
 */
class MessageSignaturesTest {

    private val keys = keyPair()
    private val signer = fakeSigner(keys)

    @Test
    fun `a signed payload round trips true`() {
        val signed = MessageSignatures.sign(signer, "grant 30 minutes")
        val payload = MessageSignatures.split(signed)!!

        assertTrue(MessageSignatures.verify(signer.publicKey, payload))
    }

    @Test
    fun `a different signer's key does not verify it`() {
        val impostor = keyPair()
        val signed = MessageSignatures.sign(signer, "grant 30 minutes")
        val payload = MessageSignatures.split(signed)!!

        assertFalse(MessageSignatures.verify(impostor.public.encoded, payload))
    }

    @Test
    fun `tampering with the body after signing breaks verification`() {
        val signed = MessageSignatures.sign(signer, "grant 30 minutes")
        val payload = MessageSignatures.split(signed)!!
        val tampered = payload.copy(body = "grant 90 minutes")

        assertFalse(MessageSignatures.verify(signer.publicKey, tampered))
    }

    @Test
    fun `garbage never throws, only fails to verify`() {
        val real = MessageSignatures.split(MessageSignatures.sign(signer, "hello"))!!

        val garbageSignatures = listOf(
            ByteArray(0),
            byteArrayOf(1, 2, 3, 4, 5),
            ByteArray(64) { it.toByte() },
        )
        garbageSignatures.forEach { badSignature ->
            val payload = real.copy(signature = badSignature)
            assertFalse(MessageSignatures.verify(signer.publicKey, payload))
        }

        val garbageKeys = listOf(
            ByteArray(0),
            byteArrayOf(9, 9, 9),
            signer.publicKey.copyOf(10),
        )
        garbageKeys.forEach { badKey ->
            assertFalse(MessageSignatures.verify(badKey, real))
        }
    }

    // ---------- split ----------

    @Test
    fun `split refuses a payload with no separator`() {
        assertNull(MessageSignatures.split("nospacehere"))
    }

    @Test
    fun `split refuses an empty signature portion`() {
        assertNull(MessageSignatures.split(" body only"))
    }

    @Test
    fun `split refuses a signature that is not valid base64`() {
        assertNull(MessageSignatures.split("not-base64-!! body"))
    }

    @Test
    fun `split refuses a payload with nothing after the separator`() {
        val signature = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertNull(MessageSignatures.split("$signature "))
    }

    @Test
    fun `signing the same body twice gives different signatures that both verify`() {
        // ECDSA is randomized, so a fixed signature would mean a broken RNG, not a
        // correct implementation.
        val first = MessageSignatures.split(MessageSignatures.sign(signer, "same body"))!!
        val second = MessageSignatures.split(MessageSignatures.sign(signer, "same body"))!!

        assertNotEquals(first, second)
        assertTrue(MessageSignatures.verify(signer.publicKey, first))
        assertTrue(MessageSignatures.verify(signer.publicKey, second))
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun fakeSigner(keys: KeyPair) = object : Signer {
        override val publicKey: ByteArray = keys.public.encoded

        override fun sign(payload: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(payload)
                sign()
            }
    }
}
