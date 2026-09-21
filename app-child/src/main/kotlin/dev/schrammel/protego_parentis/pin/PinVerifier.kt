package dev.schrammel.protego_parentis.pin

import android.content.Context
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.security.PinAttempts
import dev.schrammel.protego_parentis.security.PinLock
import kotlin.time.Duration

/**
 * Checks a PIN, sets a new one, and keeps the [PinAttempts] lockout counter and
 * the diagnostics/audit trail that go with each attempt in step with whichever
 * of those two things just happened.
 *
 * Pulled out of [dev.schrammel.protego_parentis.ui.PinActivity], which used to
 * do all of this itself alongside building the screen. None of it needs an
 * `Activity` — [PinLock.verify]'s PBKDF2 comparison and [PinAttempts]'s
 * synchronous commits are already meant to run off the UI thread (see
 * [dev.schrammel.protego_parentis.ui.Background]), and a class that only needs
 * a [Context] can be exercised directly in a unit test instead of through
 * Robolectric's Activity harness. The Activity still owns *when* this runs
 * (off the UI thread, only while not already mid-check) and what the screen
 * says about the result; this owns whether the PIN was right and what gets
 * recorded because of it.
 *
 * Not in the `security` module alongside [PinLock] and [PinAttempts]: it also
 * calls [Audit], which lives in this module and cannot be depended on the
 * other way without introducing a cycle.
 */
class PinVerifier(
    context: Context,
    private val lock: PinLock = PinLock(context),
    private val attempts: PinAttempts = PinAttempts(context),
) {
    private val appContext = context.applicationContext

    /** Whether a PIN has ever been set. */
    val isSet: Boolean get() = lock.isSet

    /** How long until another attempt is accepted. Zero when not locked out. */
    fun lockoutRemaining(): Duration = attempts.lockoutRemaining()

    /**
     * Checks [pin] against the stored hash, recording the attempt either way
     * and a [DiagnosticEvent.PIN_FAILED] line when it did not match.
     */
    fun verify(pin: String): Boolean {
        val matched = lock.verify(pin)
        if (matched) {
            attempts.recordSuccess()
        } else {
            attempts.recordFailure()
            Diagnostics.record(appContext, DiagnosticEvent.PIN_FAILED)
        }
        return matched
    }

    /**
     * Records a successful unlock that did not go through [verify] —
     * biometrics, which authenticate independently of the PIN itself but
     * still clear the same lockout counter a correct PIN would.
     */
    fun recordBiometricSuccess() = attempts.recordSuccess()

    /**
     * Stores [pin] as the new PIN and records the change. Callers verify any
     * existing PIN themselves before calling this — this only ever sets.
     */
    fun setNewPin(pin: String) {
        lock.set(pin)
        attempts.recordSuccess()
        Diagnostics.record(appContext, DiagnosticEvent.PIN_CHANGED)
        Audit.record(appContext, AuditActor.PARENT, AuditAction.PIN_CHANGED)
    }
}
