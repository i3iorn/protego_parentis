package dev.schrammel.protego_parentis.glucose

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.protego_parentis.model.GlucoseReading
import dev.schrammel.protego_parentis.security.SecretVault
import java.time.Instant

/**
 * Where this phone reads glucose from, and the newest reading it has.
 *
 * Local to the managed phone and never replicated: the token is a
 * credential to the family's health data, and only the phone that enforces
 * the rules needs it. Sealed with [SecretVault] like the family passphrase;
 * if the keystore can't seal it, nothing is saved and [save] says so.
 */
class NightscoutStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val baseUrl: String? get() = prefs.getString(KEY_URL, null)

    fun token(): String? = prefs.getString(KEY_TOKEN_SEALED, null)?.let(SecretVault::open)

    val isConfigured: Boolean get() = baseUrl != null && prefs.contains(KEY_TOKEN_SEALED)

    /** Saves the connection, forgetting anything read from a previous one. False if the token couldn't be sealed. */
    fun save(baseUrl: String, token: String): Boolean {
        val sealed = SecretVault.seal(token) ?: return false
        prefs.edit {
            clear()
            putString(KEY_URL, baseUrl)
            putString(KEY_TOKEN_SEALED, sealed)
        }
        return true
    }

    fun clear() = prefs.edit { clear() }

    var units: GlucoseUnit?
        get() = prefs.getString(KEY_UNITS, null)?.let { name -> GlucoseUnit.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit { if (value == null) remove(KEY_UNITS) else putString(KEY_UNITS, value.name) }

    var unitsCheckedAt: Instant?
        get() = instant(KEY_UNITS_CHECKED)
        set(value) = putInstant(KEY_UNITS_CHECKED, value)

    var latest: GlucoseReading?
        get() {
            val mgdl = prefs.getInt(KEY_MGDL, -1).takeIf { it > 0 } ?: return null
            val at = instant(KEY_READ_AT) ?: return null
            return GlucoseReading(mgdl, at)
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_MGDL)
                remove(KEY_READ_AT)
            } else {
                putInt(KEY_MGDL, value.mgdl)
                putLong(KEY_READ_AT, value.at.toEpochMilli())
            }
        }

    /** When a fetch last started, successful or not, so a failing server isn't retried every tick. */
    var lastAttemptAt: Instant?
        get() = instant(KEY_ATTEMPT)
        set(value) = putInstant(KEY_ATTEMPT, value)

    /** What went wrong on the last fetch, or null if it worked. */
    var lastProblem: NightscoutProblem?
        get() = prefs.getString(KEY_PROBLEM, null)?.let { name -> NightscoutProblem.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit { if (value == null) remove(KEY_PROBLEM) else putString(KEY_PROBLEM, value.name) }

    private fun instant(key: String): Instant? =
        prefs.getLong(key, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    private fun putInstant(key: String, value: Instant?) =
        prefs.edit { if (value == null) remove(key) else putLong(key, value.toEpochMilli()) }

    private companion object {
        const val FILE_NAME = "nightscout"
        const val KEY_URL = "url"
        const val KEY_TOKEN_SEALED = "token_sealed"
        const val KEY_UNITS = "units"
        const val KEY_UNITS_CHECKED = "units_checked_at"
        const val KEY_MGDL = "mgdl"
        const val KEY_READ_AT = "read_at"
        const val KEY_ATTEMPT = "attempt_at"
        const val KEY_PROBLEM = "problem"
    }
}

enum class NightscoutProblem {
    /** The server answered 401/403: the token is wrong or lacks the readable role. */
    NOT_ALLOWED,

    /** No answer, or not one that made sense. */
    UNREACHABLE,

    /** The server answered, but has no recent sensor readings. */
    NO_READINGS,
}
