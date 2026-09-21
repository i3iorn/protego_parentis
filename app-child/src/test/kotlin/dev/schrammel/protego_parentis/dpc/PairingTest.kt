package dev.schrammel.protego_parentis.dpc

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [PairingStore] now seals its token with [dev.schrammel.protego_parentis.security.SecretVault],
 * the same as [dev.schrammel.protego_parentis.sync.SyncConfigStore]'s passphrase.
 * Robolectric has no `AndroidKeyStore` provider (the same gap noted in this
 * codebase for [dev.schrammel.protego_parentis.security.KeystoreSigner]), so
 * `SecretVault.seal` always fails in this environment — nothing here can
 * round-trip a real token. What these tests pin instead is the
 * graceful-failure path: familyId/childId still persist, and a token that
 * could not be sealed comes back null rather than crashing or silently
 * falling back to plain text, the same contract `ComplianceActivity`'s
 * manual-code-entry fallback already relies on for an incomplete pairing
 * from any other cause.
 */
@RunWith(RobolectricTestRunner::class)
class PairingTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun store() = PairingStore(context)

    @Test
    fun `familyId and childId persist regardless of whether the token could be sealed`() {
        store().save(PairingDetails(token = "a-token", familyId = "family-1", childId = "child-1"))

        val loaded = store().load()

        assertEquals("family-1", loaded.familyId)
        assertEquals("child-1", loaded.childId)
    }

    @Test
    fun `a token that cannot be sealed on this device is not stored in plain text`() {
        store().save(PairingDetails(token = "a-token", familyId = "family-1", childId = "child-1"))

        // Never written as plain text under any key this store owns — the
        // whole point of #253. Robolectric can't seal it, so it comes back
        // null rather than as the original plaintext.
        assertNull(store().load().token)
        assertNull(
            "the token must never land in prefs unsealed",
            context.getSharedPreferences("pairing", Context.MODE_PRIVATE).getString("token", null),
        )
    }

    @Test
    fun `clearToken removes both the sealed and legacy keys`() {
        val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
        prefs.edit().putString("token", "legacy-plaintext").putString("token_sealed", "sealed-value").commit()

        store().clearToken()

        assertNull(prefs.getString("token", null))
        assertNull(prefs.getString("token_sealed", null))
    }

    @Test
    fun `a legacy plain-text token is migrated out on the next load`() {
        val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
        prefs.edit().putString("token", "legacy-plaintext").commit()

        // Migration hands back the legacy value this one time...
        assertEquals("legacy-plaintext", store().load().token)

        // ...and removes the plain-text copy even though this device cannot
        // reseal it, so it is never read as plain text again.
        assertNull(prefs.getString("token", null))
    }
}
