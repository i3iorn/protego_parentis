package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.diagnostics.DiagnosticEvent
import dev.schrammel.dementor_watch.diagnostics.Diagnostics
import dev.schrammel.dementor_watch.security.BiometricGate
import dev.schrammel.dementor_watch.security.PinAttempts
import dev.schrammel.dementor_watch.security.PinLock
import kotlin.time.Duration

/**
 * Sets, checks or replaces the parent's PIN.
 *
 * Deliberately gates *changing* the rules, not viewing them. The teen's dashboard
 * stays open: they are meant to see exactly what is measured and what is enforced,
 * and a product that hides its own rules from the person living under them is the
 * kind that gets worked around rather than accepted.
 *
 * Replacing a PIN verifies the old one first and overwrites only once the new one
 * is confirmed. An earlier version cleared the stored PIN *before* asking for the
 * replacement, so backing out at that moment left the app with no PIN at all — and
 * invited whoever opened a settings screen next to set their own.
 *
 * A fingerprint or face can stand in for the PIN when the family has turned that
 * on, but only for unlocking. Choosing or confirming a new PIN always needs the
 * PIN itself: a biometric cannot tell you what to set, and letting one authorise
 * the change would mean whoever the phone recognises could replace the code.
 */
class PinActivity : DementorActivity() {

    /** Where we are in the sequence; which sequence depends on how we were launched. */
    private enum class Step { UNLOCK, VERIFY_EXISTING, CHOOSE_NEW, CONFIRM_NEW }

    private lateinit var lock: PinLock
    private lateinit var attempts: PinAttempts
    private lateinit var biometrics: BiometricGate

    private lateinit var prompt: TextView
    private lateinit var input: EditText

    private var step = Step.UNLOCK
    private var chosen: String? = null

    /** Null until the layout is built; absent entirely when nothing is on offer. */
    private var biometricButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock = PinLock(this)
        attempts = PinAttempts(this)
        biometrics = BiometricGate(this)

        // The PIN screen must not appear in the recents thumbnail.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        step = when {
            !lock.isSet -> Step.CHOOSE_NEW
            intent.getBooleanExtra(EXTRA_CHANGE, false) -> Step.VERIFY_EXISTING
            else -> Step.UNLOCK
        }

        setContentView(buildLayout())
        updatePrompt()

        // Offered immediately rather than behind a tap: on a phone where this is
        // on, it is the expected way in, and making someone ask for it twice is
        // slower than typing the PIN would have been.
        if (canOfferBiometrics()) showBiometricPrompt()
    }

    /**
     * Only for unlocking, and only while the PIN is not throttled.
     *
     * The throttle exists to make guessing expensive; a second door with no
     * throttle on it would make the first one decorative.
     */
    private fun canOfferBiometrics(): Boolean =
        step == Step.UNLOCK &&
            biometrics.canUnlock() &&
            attempts.lockoutRemaining() <= Duration.ZERO

    private fun showBiometricPrompt() {
        val cipher = biometrics.cipher() ?: return

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    attempts.recordSuccess()
                    succeed()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Cancelling is not a failure, and it must not count towards
                    // the PIN throttle: the screen simply waits for the code.
                    biometricButton?.visibility =
                        if (canOfferBiometrics()) View.VISIBLE else View.GONE
                }
            },
        ).authenticate(
            biometrics.promptInfo(
                title = getString(R.string.pin_biometric_title),
                subtitle = getString(R.string.pin_biometric_subtitle),
                cancel = getString(R.string.pin_biometric_use_pin),
            ),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    private fun buildLayout(): LinearLayout {
        prompt = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Body)
            setPadding(0, dimen(R.dimen.space_md), 0, dimen(R.dimen.space_lg))
        }

        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)

            addView(
                TextView(context).apply {
                    setText(R.string.pin_title)
                    styleAs(R.style.TextAppearance_Dementor_Title)
                },
            )
            addView(prompt)
            addView(input)
            addView(
                Button(context).apply {
                    setText(R.string.pin_continue)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_xl) }
                    setOnClickListener { onSubmit() }
                },
            )

            if (step == Step.UNLOCK && biometrics.canUnlock()) {
                addView(
                    Button(context).apply {
                        setText(R.string.pin_biometric_retry)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            .apply { topMargin = dimen(R.dimen.space_sm) }
                        visibility = View.GONE
                        setOnClickListener { showBiometricPrompt() }
                        biometricButton = this
                    },
                )
            }
        }
    }

    private fun updatePrompt() {
        prompt.setText(
            when (step) {
                Step.UNLOCK -> R.string.pin_enter
                Step.VERIFY_EXISTING -> R.string.pin_enter_current
                Step.CHOOSE_NEW -> R.string.pin_create
                Step.CONFIRM_NEW -> R.string.pin_confirm
            },
        )
    }

    private fun onSubmit() {
        if (rejectWhileLockedOut()) return

        val entered = input.text.toString()
        input.setText("")

        when (step) {
            Step.UNLOCK -> if (verify(entered)) succeed()

            Step.VERIFY_EXISTING -> if (verify(entered)) {
                step = Step.CHOOSE_NEW
                updatePrompt()
            }

            Step.CHOOSE_NEW -> chooseNew(entered)

            Step.CONFIRM_NEW -> confirmNew(entered)
        }
    }

    /** Checks an entered PIN, recording the attempt either way. */
    private fun verify(entered: String): Boolean {
        if (lock.verify(entered)) {
            attempts.recordSuccess()
            return true
        }
        attempts.recordFailure()
        Diagnostics.record(this, DiagnosticEvent.PIN_FAILED)
        if (!rejectWhileLockedOut()) toast(getString(R.string.pin_wrong))
        return false
    }

    private fun chooseNew(entered: String) {
        if (entered.length < PinLock.MIN_LENGTH) {
            toast(
                resources.getQuantityString(
                    R.plurals.pin_too_short,
                    PinLock.MIN_LENGTH,
                    PinLock.MIN_LENGTH,
                ),
            )
            return
        }
        chosen = entered
        step = Step.CONFIRM_NEW
        updatePrompt()
    }

    private fun confirmNew(entered: String) {
        if (entered != chosen) {
            chosen = null
            step = Step.CHOOSE_NEW
            updatePrompt()
            toast(getString(R.string.pin_mismatch))
            return
        }
        // Only now is any previous PIN replaced.
        lock.set(entered)
        attempts.recordSuccess()
        Diagnostics.record(this, DiagnosticEvent.PIN_CHANGED)
        Audit.record(this, AuditActor.PARENT, AuditAction.PIN_CHANGED)
        succeed()
    }

    /** True when a lockout is in force, having told the user how long is left. */
    private fun rejectWhileLockedOut(): Boolean {
        val waiting = attempts.lockoutRemaining()
        if (waiting <= Duration.ZERO) return false
        val seconds = waiting.inWholeSeconds.toInt()
        toast(resources.getQuantityString(R.plurals.pin_locked_out, seconds, seconds))
        return true
    }

    private fun succeed() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val EXTRA_CHANGE = "change"

        fun intent(context: Context, change: Boolean = false): Intent =
            Intent(context, PinActivity::class.java).putExtra(EXTRA_CHANGE, change)
    }
}
