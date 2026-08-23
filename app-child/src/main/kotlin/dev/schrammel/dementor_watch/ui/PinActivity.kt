package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.security.PinAttempts
import dev.schrammel.dementor_watch.security.PinLock
import kotlin.time.Duration

/**
 * Sets or checks the parent's PIN.
 *
 * Deliberately gates *changing* the rules, not viewing them. The teen's dashboard
 * stays open: they are meant to see exactly what is measured and what is enforced,
 * and a product that hides its own rules from the person living under them is the
 * kind that gets worked around rather than accepted.
 */
class PinActivity : AppCompatActivity() {

    private lateinit var lock: PinLock
    private lateinit var attempts: PinAttempts
    private var confirming: String? = null

    private lateinit var prompt: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock = PinLock(this)
        attempts = PinAttempts(this)
        // The PIN screen must not appear in the recents thumbnail.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContentView(buildLayout())
        updatePrompt()
    }

    private fun buildLayout(): LinearLayout {
        prompt = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Subtitle)
            setPadding(0, 0, 0, dimen(R.dimen.space_lg))
        }

        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val submit = Button(this).apply {
            setText(R.string.pin_continue)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dimen(R.dimen.space_xl) }
            setOnClickListener { onSubmit() }
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
            addView(prompt.also { it.setPadding(0, dimen(R.dimen.space_md), 0, dimen(R.dimen.space_lg)) })
            addView(input)
            addView(submit)
        }
    }

    private fun updatePrompt() {
        prompt.setText(
            when {
                !lock.isSet && confirming == null -> R.string.pin_create
                confirming != null -> R.string.pin_confirm
                else -> R.string.pin_enter
            },
        )
    }

    private fun onSubmit() {
        if (rejectWhileLockedOut()) return

        val entered = input.text.toString()
        input.setText("")

        when {
            // First run: choose a PIN, then type it again.
            !lock.isSet && confirming == null -> {
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
                confirming = entered
                updatePrompt()
            }

            confirming != null -> {
                if (entered != confirming) {
                    confirming = null
                    updatePrompt()
                    toast(getString(R.string.pin_mismatch))
                    return
                }
                lock.set(entered)
                attempts.recordSuccess()
                succeed()
            }

            lock.verify(entered) -> {
                attempts.recordSuccess()
                succeed()
            }

            else -> {
                attempts.recordFailure()
                if (!rejectWhileLockedOut()) toast(getString(R.string.pin_wrong))
            }
        }
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

        fun intent(context: Context) = Intent(context, PinActivity::class.java)
    }
}
