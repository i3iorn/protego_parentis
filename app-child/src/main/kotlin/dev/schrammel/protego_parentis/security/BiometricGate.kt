package dev.schrammel.protego_parentis.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Why the fingerprint or face option is not on offer. */
enum class BiometricAvailability {
    /** Ready to use. */
    READY,

    /** The phone has a sensor, but nobody has enrolled a finger or a face. */
    NOT_ENROLLED,

    /** No usable sensor, or one the phone will not vouch for. */
    UNSUPPORTED,

    /**
     * Was set up, and the phone has since invalidated it.
     *
     * Android does this when the enrolled biometrics change — a finger added or
     * removed. It is the protection that matters most here: on the phone being
     * limited, a finger enrolled *after* a parent turned this on would otherwise
     * silently become a key to the rules.
     */
    INVALIDATED,
}

/**
 * Unlocking the parent gate with a fingerprint or a face (TR-4).
 *
 * Always an addition to the PIN, never a replacement. Biometrics fail — wet
 * hands, a sensor under a case, a face in the dark — and the PIN is both the
 * fallback and the way back in when the phone decides the enrolled biometrics
 * have changed. It is also the only one of the two that can be *given* to a
 * second parent.
 *
 * The prompt is backed by a keystore key that requires authentication to use, so
 * a success is proof the phone authenticated somebody rather than a callback that
 * said so. The key is marked invalid on biometric enrolment, which is what makes
 * this safe to offer at all on the child's own device: a finger added later
 * cannot quietly become a key to the rules.
 *
 * What it cannot do is tell one enrolled finger from another. On a phone whose
 * owner has their own finger enrolled, that finger opens this. That is why the
 * setting is off by default and why the screen offering it says so plainly.
 */
class BiometricGate(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val manager = BiometricManager.from(appContext)

    /** Whether the family has turned this on. Off until someone with the PIN does. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        private set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /**
     * Turns the option on, creating the key that backs it.
     *
     * Returns false when the key cannot be made, which is the honest answer on a
     * phone whose keystore refuses — better than a setting that looks on and does
     * nothing when someone needs it.
     */
    fun enable(): Boolean {
        if (availability() == BiometricAvailability.UNSUPPORTED) return false
        val created = runCatching { createKey() }.isSuccess
        isEnabled = created
        if (created) prefs.edit { putBoolean(KEY_INVALIDATED, false) }
        return created
    }

    fun disable() {
        isEnabled = false
        prefs.edit { putBoolean(KEY_INVALIDATED, false) }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    /**
     * What this phone can currently offer.
     *
     * A question, with no answer that changes anything and no keystore work to
     * answer it. It used to reach for a cipher — which is where invalidation is
     * noticed — so asking whether the option was available could turn it off, and
     * a screen that asked three times did three rounds of keystore work on the
     * main thread to render one checkbox. Invalidation is noticed where a cipher
     * is genuinely needed: at the prompt.
     */
    fun availability(): BiometricAvailability {
        val verdict = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return when {
            verdict == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricAvailability.NOT_ENROLLED

            verdict != BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.UNSUPPORTED

            // Retired by the phone since it was set up. Reported until somebody
            // turns it on again, rather than only on the render that happened to
            // notice — a setting that switches itself off in silence is one people
            // conclude is broken.
            wasInvalidated -> BiometricAvailability.INVALIDATED

            else -> BiometricAvailability.READY
        }
    }

    /** Whether the phone retired the key since it was set up. Cleared by a decision. */
    private val wasInvalidated: Boolean
        get() = prefs.getBoolean(KEY_INVALIDATED, false)

    /** True when the unlock screen should offer the button. */
    fun canUnlock(): Boolean = isEnabled && availability() == BiometricAvailability.READY

    /**
     * A cipher locked to the key, for handing to the prompt.
     *
     * Null when the key has been invalidated, which is not an error but the answer
     * to "can this still be used": the biometrics changed, so it cannot, and the
     * caller falls back to the PIN.
     */
    fun cipher(): Cipher? = runCatching {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: return null
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }.getOrElse { error ->
        if (error is KeyPermanentlyInvalidatedException) {
            // Enrolled biometrics changed. The option turns itself off rather than
            // sitting there failing, and the PIN is what gets somebody back in.
            isEnabled = false
            prefs.edit { putBoolean(KEY_INVALIDATED, true) }
            runCatching { keyStore().deleteEntry(ALIAS) }
        }
        null
    }

    fun promptInfo(title: String, subtitle: String, cancel: String): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(cancel)
            // Strong only. A device-credential fallback here would let the phone's
            // own screen lock stand in for the parent's PIN, and on the child's
            // phone that lock is the child's.
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

    private fun createKey() {
        val store = keyStore()
        runCatching { store.deleteEntry(ALIAS) }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // The point of the whole arrangement: using this key requires
                    // an authentication, so the prompt cannot be waved through.
                    .setUserAuthenticationRequired(true)
                    // And it dies if the enrolled biometrics change, so a finger
                    // added after a parent set this up does not inherit it.
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val FILE_NAME = "biometric"
        const val KEY_ENABLED = "enabled"
        const val KEY_INVALIDATED = "invalidated"

        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "dementor-parent-gate"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
