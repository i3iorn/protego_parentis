package dev.schrammel.protego_parentis.pin

import android.content.Context
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.security.PinLock
import dev.schrammel.protego_parentis.sync.ReplicatedStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration

/**
 * [PinVerifier] exercised on its own — the actual point of pulling it out of
 * [dev.schrammel.protego_parentis.ui.PinActivity]. No Activity, no
 * [Background][dev.schrammel.protego_parentis.ui.Background], no
 * Robolectric activity harness: just a [Context], the way
 * [dev.schrammel.protego_parentis.security.PinLockTest] and
 * [dev.schrammel.protego_parentis.security.PinAttemptsTest] already exercise
 * the two classes this one composes.
 */
@RunWith(RobolectricTestRunner::class)
class PinVerifierTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun verifier() = PinVerifier(context)

    @After
    fun tearDown() {
        context.getSharedPreferences("security", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pin_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("audit", Context.MODE_PRIVATE).edit().clear().commit()
        Diagnostics.clear(context)
        ReplicatedStateStore(context).forgetAll()
    }

    @Test
    fun `no pin set yet`() {
        assertFalse(verifier().isSet)
    }

    @Test
    fun `a correct pin verifies and clears a prior run of failures`() {
        PinLock(context).set("123456")
        val verifier = verifier()
        repeat(2) { verifier.verify("000000") }

        assertTrue(verifier.verify("123456"))
        assertEquals(Duration.ZERO, verifier.lockoutRemaining())
    }

    @Test
    fun `a wrong pin fails and is recorded as a diagnostic`() {
        PinLock(context).set("123456")
        val verifier = verifier()

        assertFalse(verifier.verify("000000"))

        val log = Diagnostics.asText(context, header = "", empty = "")
        assertTrue(
            "a failed attempt should show up in the diagnostics log",
            log.contains(DiagnosticEvent.PIN_FAILED.name),
        )
    }

    @Test
    fun `enough consecutive wrong pins locks further attempts out`() {
        PinLock(context).set("123456")
        val verifier = verifier()

        // Well past any reasonable free-attempt allowance; the exact
        // threshold belongs to PinBackoff/PinAttempts, not this class.
        repeat(10) { verifier.verify("000000") }

        assertTrue(verifier.lockoutRemaining() > Duration.ZERO)
    }

    /**
     * The lockout is the verifier's own, not something each screen has to
     * remember to ask about first: even the right PIN is refused until it
     * runs out.
     */
    @Test
    fun `the correct pin is refused while locked out`() {
        PinLock(context).set("123456")
        val verifier = verifier()
        repeat(4) { verifier.verify("000000") }
        assertTrue(verifier.lockoutRemaining() > Duration.ZERO)

        assertFalse(verifier.verify("123456"))
    }

    @Test
    fun `a biometric success clears an existing lockout without checking a pin`() {
        PinLock(context).set("123456")
        val verifier = verifier()
        repeat(10) { verifier.verify("000000") }
        assertTrue(verifier.lockoutRemaining() > Duration.ZERO)

        verifier.recordBiometricSuccess()

        assertEquals(Duration.ZERO, verifier.lockoutRemaining())
    }

    @Test
    fun `setting a new pin makes it the one that verifies, and records the change`() {
        val verifier = verifier()

        verifier.setNewPin("246810")

        assertTrue(verifier().isSet)
        assertTrue(verifier().verify("246810"))

        Audit.awaitIdle()
        assertTrue(Audit.entries(context).any { it.action == AuditAction.PIN_CHANGED })

        val log = Diagnostics.asText(context, header = "", empty = "")
        assertTrue(log.contains(DiagnosticEvent.PIN_CHANGED.name))
    }
}
