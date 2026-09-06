package dev.schrammel.protego_parentis.audit

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * No Android Keystore exists in this test environment — the same is true of
 * a real device with a keystore that refuses (see [SecretVault]'s own doc
 * comment for when that happens). Either way, #84's signing is meant to
 * degrade rather than crash: an unsigned entry falls back to the plain hash
 * chain exactly as if this feature did not exist, never to a failed build or
 * a lost history entry.
 */
class AuditSignatureTest {

    @Test
    fun `signing without a usable keystore returns null rather than throwing`() {
        assertNull(AuditSignature.sign("anything"))
    }
}
