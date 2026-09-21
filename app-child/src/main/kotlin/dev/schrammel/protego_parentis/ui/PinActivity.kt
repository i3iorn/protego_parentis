package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.pin.PinVerifier
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.BiometricGate
import dev.schrammel.protego_parentis.security.PinLock
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

    private lateinit var verifier: PinVerifier
    private lateinit var biometrics: BiometricGate

    private lateinit var prompt: TextView

    /** The "this is not yours to choose" line. Only ever shown on the managed phone. */
    private lateinit var warning: TextView
    private lateinit var input: EditText

    private var step = Step.UNLOCK
    private var chosen: String? = null

    /**
     * Set while a submitted PIN is being checked or stored off the UI thread
     * — see [verify] and [confirmNew]'s own doc comments for why that work
     * moved off it. Guards against a second tap queuing a second check while
     * the first is still in flight; before this moved off the UI thread, the
     * thread being blocked for the duration was what stopped that by
     * accident.
     */
    private var verifying = false

    /** True only when launched as step 2 of first-time setup, from RoleSetupActivity. */
    private val onboarding: Boolean by lazy { intent.getBooleanExtra(EXTRA_ONBOARDING, false) }

    /** Null until the layout is built; absent entirely when nothing is on offer. */
    private var biometricButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifier = PinVerifier(this)
        biometrics = BiometricGate(this)

        // The PIN screen must not appear in the recents thumbnail.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        step = when {
            !verifier.isSet -> Step.CHOOSE_NEW
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
            verifier.lockoutRemaining() <= Duration.ZERO

    private fun showBiometricPrompt() {
        val cipher = biometrics.cipher() ?: return

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    // BiometricPrompt delivers this on the main executor
                    // passed to its constructor below — same reason as
                    // verify()'s own doc comment for taking the commit off it.
                    verifying = true
                    Background.load(this@PinActivity, { verifier.recordBiometricSuccess() }) {
                        verifying = false
                        succeed()
                    }
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
            // PINs here are variable-length (min 4, no fixed max), so there is no
            // "complete" length to auto-submit at. Instead, submitting is one tap
            // away on the keyboard itself rather than only the on-screen button.
            setSingleLine(true)
            // #69: must come *after* setSingleLine. A password input type does
            // install PasswordTransformationMethod, but setSingleLine replaces
            // whatever is there with SingleLineTransformationMethod — so the
            // field above was showing every digit in the clear, on every device
            // and every keyboard, which is exactly what was reported. Setting it
            // last is the fix; moving it back up silently un-masks the PIN again.
            transformationMethod = PasswordTransformationMethod.getInstance()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, event ->
                val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
                if (isDone) onSubmit()
                isDone
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)

            if (onboarding) addView(stepIndicator(2))
            addView(
                TextView(context).apply {
                    setText(R.string.pin_title)
                    styleAs(R.style.TextAppearance_Dementor_Title)
                },
            )
            addView(prompt)
            warning = TextView(this@PinActivity).apply {
                setText(R.string.pin_create_child_warning)
                styleAs(R.style.TextAppearance_Dementor_Caption)
                setPadding(0, dimen(R.dimen.space_sm), 0, 0)
                visibility = View.GONE
            }
            addView(warning)
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

    /**
     * What to say, and on the managed phone, who to say it to.
     *
     * Choosing the first PIN on the phone being limited is the one moment in this
     * app where the wrong person doing the typing quietly breaks everything after
     * it. A PIN the young person picked unlocks every rule about them, and nothing
     * later in the product notices or can recover from it — so this screen names
     * the person who should be holding the phone, rather than saying "choose a
     * PIN" and hoping.
     *
     * Only on the managed device, and only while creating one. A parent setting a
     * PIN on their own handset already knows whose it is, and being told would
     * read as the app not knowing which phone it was on.
     */
    private fun updatePrompt() {
        val onManagedPhone = RoleStore(this).roleOrChild == DeviceRole.CHILD

        prompt.setText(
            when (step) {
                Step.UNLOCK -> R.string.pin_enter
                Step.VERIFY_EXISTING -> R.string.pin_enter_current
                Step.CHOOSE_NEW ->
                    if (onManagedPhone) R.string.pin_create_child else R.string.pin_create

                Step.CONFIRM_NEW -> R.string.pin_confirm
            },
        )

        warning.visibility =
            if (onManagedPhone && step == Step.CHOOSE_NEW) View.VISIBLE else View.GONE
    }

    private fun onSubmit() {
        if (verifying || rejectWhileLockedOut()) return

        val entered = input.text.toString()
        input.setText("")

        when (step) {
            Step.UNLOCK -> verify(entered) { matched -> if (matched) succeed() }

            Step.VERIFY_EXISTING -> verify(entered) { matched ->
                if (matched) {
                    step = Step.CHOOSE_NEW
                    updatePrompt()
                }
            }

            Step.CHOOSE_NEW -> chooseNew(entered)

            Step.CONFIRM_NEW -> confirmNew(entered)
        }
    }

    /**
     * Checks an entered PIN off the UI thread, recording the attempt either
     * way, and calls [onChecked] back with whether it matched.
     *
     * [PinVerifier.verify] runs [PinLock.verify]'s 150,000 rounds of PBKDF2 —
     * real, deliberate CPU cost, not an accident to optimise away — and
     * commits the attempt to disk synchronously by design too (see
     * [dev.schrammel.protego_parentis.security.PinAttempts]'s own doc
     * comment on why an async write would not do).
     * Both used to run inline on this screen's click listener — on a loaded
     * or slower device, tens to a couple hundred milliseconds of the UI
     * thread simply not responding, occasionally enough to feel like a hung
     * tap that a retry a moment later did not reproduce.
     */
    private fun verify(entered: String, onChecked: (Boolean) -> Unit) {
        verifying = true
        Background.load(this, { verifier.verify(entered) }) { matched ->
            verifying = false
            if (!matched) {
                if (!rejectWhileLockedOut()) toast(getString(R.string.pin_wrong))
            }
            onChecked(matched)
        }
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
            // Stays on CONFIRM_NEW rather than discarding chosen and starting over:
            // a typo on this second, still-masked entry is common, and re-typing
            // the whole PIN twice to fix one mistyped confirmation is friction with
            // no purpose.
            toast(getString(R.string.pin_mismatch))
            return
        }
        // Off the UI thread for the same reason verify() is — PinLock.set
        // hashes through the same PBKDF2 cost and commits synchronously.
        verifying = true
        // Only now is any previous PIN replaced.
        Background.load(this, { verifier.setNewPin(entered) }) {
            verifying = false
            succeed()
        }
    }

    /** True when a lockout is in force, having told the user how long is left. */
    private fun rejectWhileLockedOut(): Boolean {
        val waiting = verifier.lockoutRemaining()
        if (waiting <= Duration.ZERO) return false
        val seconds = waiting.inWholeSeconds.toInt()
        toast(resources.getQuantityString(R.plurals.pin_locked_out, seconds, seconds))
        return true
    }

    private fun succeed() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val EXTRA_CHANGE = "change"
        private const val EXTRA_ONBOARDING = "onboarding"

        fun intent(context: Context, change: Boolean = false, onboarding: Boolean = false): Intent =
            Intent(context, PinActivity::class.java)
                .putExtra(EXTRA_CHANGE, change)
                .putExtra(EXTRA_ONBOARDING, onboarding)
    }
}
