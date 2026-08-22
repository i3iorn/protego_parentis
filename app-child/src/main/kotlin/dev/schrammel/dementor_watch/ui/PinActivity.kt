package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.security.PinLock

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
    private var confirming: String? = null

    private lateinit var prompt: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock = PinLock(this)
        setContentView(buildLayout())
        updatePrompt()
    }

    private fun buildLayout(): LinearLayout {
        prompt = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, dp(16))
        }

        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }

        val submit = Button(this).apply {
            setText(R.string.pin_continue)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) }
            setOnClickListener { onSubmit() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)

            addView(
                TextView(context).apply {
                    setText(R.string.pin_title)
                    textSize = 28f
                },
            )
            addView(prompt.also { it.setPadding(0, dp(12), 0, dp(16)) })
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
                succeed()
            }

            lock.verify(entered) -> succeed()

            else -> toast(getString(R.string.pin_wrong))
        }
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
