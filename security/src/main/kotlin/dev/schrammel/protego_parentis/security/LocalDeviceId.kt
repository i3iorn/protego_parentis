package dev.schrammel.protego_parentis.security

import androidx.annotation.VisibleForTesting

/**
 * Who this phone is, when a caller needs to say so but not to sign anything.
 *
 * Eight places wrote `runCatching { KeystoreSigner().deviceId.value }.getOrNull()`
 * inline — every replication adapter, the audit log, the conversation. They all
 * meant the same thing and all handled the same failure the same way, so the
 * question of what to do when the identity is unavailable was answered eight
 * times over instead of once. It is answered here now.
 *
 * Null means the keystore could not produce an identity — a phone whose keys
 * have been invalidated, or one being tested. Callers already treat that as
 * "write nothing", which is the right answer: a replicated op with no origin
 * cannot be attributed, and an unattributable op is worse than a missing one in
 * a design where the shared history records which device wrote what.
 *
 * ## The override
 *
 * [useInTests] exists because the classes that depend on this are the ones this
 * project has never been able to test. `KeystoreSigner` needs the
 * `AndroidKeyStore` provider, which Robolectric does not implement, so under
 * test every one of those eight call sites silently returns null and the code
 * after it does nothing. A test written against that would pass while proving
 * nothing at all.
 *
 * It is deliberately narrow, and worth being careful about, because device
 * identity is what the roster and the shared history rest on: a phone that
 * could choose its own id could write as another device. The setter used to
 * be `internal`, back when this class and every one of its test call sites
 * lived in the same module; extracting `security` into its own Gradle module
 * broke that, since `app-child`'s own tests are the majority of those call
 * sites and `internal` is module-scoped, not source-set-scoped. It is public
 * now, but [VisibleForTesting] still says what it has always said: this is
 * not a configuration point, nothing in the app calls it, and it must not
 * become one just because the compiler no longer stops it.
 *
 * This does **not** widen what can be forged in a real family. The identity
 * that matters on the wire is the one that *signs* a message, and signing still
 * goes through [KeystoreSigner] with a key that cannot leave the phone. An
 * override here changes what this device calls itself locally; it cannot make a
 * peer accept it.
 */
object LocalDeviceId {

    @Volatile
    private var testOverride: String? = null

    /** This phone's id, or null if the keystore cannot give one. */
    fun current(): String? =
        testOverride ?: runCatching { KeystoreSigner().deviceId.value }.getOrNull()

    /**
     * Pretends to be [id] for the rest of the process.
     *
     * Pass null to go back to the real keystore. Tests that set this must clear
     * it again, since the process outlives any one of them.
     */
    @VisibleForTesting
    fun useInTests(id: String?) {
        testOverride = id
    }
}
