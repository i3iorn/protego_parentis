package dev.schrammel.protego_parentis.security

import android.content.Context
import androidx.core.content.edit
import java.util.Base64

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
        val salt = PinHash.newSalt()
        prefs.edit(commit = true) {
            putString(KEY_SALT, encode(salt))
            putString(KEY_HASH, encode(PinHash.of(pin, salt)))
        }
    }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let(::decode) ?: return false
        val expected = prefs.getString(KEY_HASH, null)?.let(::decode) ?: return false
        return PinHash.matches(pin, salt, expected)
    }

    /**
     * Wipes the PIN, but only once [pin] itself has been checked.
     *
     * Not two separate calls — a standalone `clear()` relied on every caller
     * remembering to check [verify] first, which is a rule that lives only in
     * a doc comment and a bug three modules away from silently defeating.
     * Baking the check into this call's own signature means there is no way
     * to reach it without one.
     */
    fun clearIfVerified(pin: String): Boolean {
        if (!verify(pin)) return false
        prefs.edit(commit = true) {
            remove(KEY_SALT)
            remove(KEY_HASH)
        }
        return true
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(value) }.getOrNull()

    companion object {
        const val MIN_LENGTH = 4

        private const val FILE_NAME = "security"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
    }
}
