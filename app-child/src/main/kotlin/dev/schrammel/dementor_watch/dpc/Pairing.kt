package dev.schrammel.dementor_watch.dpc

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit

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
 * Deliberately plain `SharedPreferences`: this runs inside
 * `onProfileProvisioningComplete`, which is a broadcast callback with no
 * coroutine scope and a short window, so the write has to be synchronous and
 * cannot wait on a database opening.
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
        prefs.edit(commit = true) {
            putString(KEY_TOKEN, details.token)
            putString(KEY_FAMILY, details.familyId)
            putString(KEY_CHILD, details.childId)
        }
    }

    fun load(): PairingDetails = PairingDetails(
        token = prefs.getString(KEY_TOKEN, null),
        familyId = prefs.getString(KEY_FAMILY, null),
        childId = prefs.getString(KEY_CHILD, null),
    )

    /** Called once the token has been redeemed with the server; it is single-use. */
    fun clearToken() {
        prefs.edit { remove(KEY_TOKEN) }
    }

    private companion object {
        const val FILE_NAME = "pairing"
        const val KEY_TOKEN = "token"
        const val KEY_FAMILY = "family_id"
        const val KEY_CHILD = "child_id"
    }
}
