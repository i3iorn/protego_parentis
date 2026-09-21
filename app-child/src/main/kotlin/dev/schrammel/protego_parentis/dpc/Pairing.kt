package dev.schrammel.protego_parentis.dpc

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.security.SecretVault

/** Identifies which family and which child this device belongs to. */
data class PairingDetails(
    val token: String?,
    val familyId: String?,
    val childId: String?,
) {
    val isComplete: Boolean
        get() = !token.isNullOrBlank() && !familyId.isNullOrBlank() && !childId.isNullOrBlank()
}

/**
 * Persists the pairing details handed over during provisioning.
 *
 * The write itself is deliberately plain, synchronous `SharedPreferences`:
 * this runs inside `onProfileProvisioningComplete`, which is a broadcast
 * callback with no coroutine scope and a short window, so it has to be
 * synchronous and cannot wait on a database opening. The token is sealed
 * with [SecretVault] first, the same as [dev.schrammel.protego_parentis.sync.SyncConfigStore]'s
 * passphrase — `SecretVault.seal` is a local, synchronous keystore call, not
 * I/O, so it costs nothing the commit-write constraint above can't afford.
 * If sealing fails, the token is simply not stored: `ComplianceActivity`
 * already has a documented fallback to manual code entry for exactly this
 * case, the same one an incomplete pairing from any other cause takes.
 */
class PairingStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Written synchronously on purpose: the caller is a broadcast callback that may
     * be torn down as soon as it returns, and losing the token means the device
     * cannot find its family without manual re-entry.
     */
    @SuppressLint("ApplySharedPref")
    fun save(details: PairingDetails) {
        val sealed = details.token?.let(SecretVault::seal)
        prefs.edit(commit = true) {
            remove(KEY_TOKEN_LEGACY)
            if (sealed != null) putString(KEY_TOKEN_SEALED, sealed) else remove(KEY_TOKEN_SEALED)
            putString(KEY_FAMILY, details.familyId)
            putString(KEY_CHILD, details.childId)
        }
    }

    fun load(): PairingDetails = PairingDetails(
        token = token(),
        familyId = prefs.getString(KEY_FAMILY, null),
        childId = prefs.getString(KEY_CHILD, null),
    )

    /** Reads the token, migrating a plain-text one written before this store sealed it. */
    private fun token(): String? {
        prefs.getString(KEY_TOKEN_LEGACY, null)?.takeIf(String::isNotBlank)?.let { legacy ->
            val sealed = SecretVault.seal(legacy)
            prefs.edit {
                remove(KEY_TOKEN_LEGACY)
                if (sealed != null) putString(KEY_TOKEN_SEALED, sealed)
            }
            return legacy
        }
        return prefs.getString(KEY_TOKEN_SEALED, null)?.let(SecretVault::open)
    }

    /** Called once the token has been redeemed with the server; it is single-use. */
    fun clearToken() {
        prefs.edit {
            remove(KEY_TOKEN_SEALED)
            remove(KEY_TOKEN_LEGACY)
        }
    }

    private companion object {
        const val FILE_NAME = "pairing"
        const val KEY_TOKEN_LEGACY = "token"
        const val KEY_TOKEN_SEALED = "token_sealed"
        const val KEY_FAMILY = "family_id"
        const val KEY_CHILD = "child_id"
    }
}
