package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import dev.schrammel.protego_parentis.R

/**
 * The dialogs a screen opens: one that explains, ones that ask before something
 * irreversible, ones that take a line of text, and ones that choose a single
 * number.
 */

/**
 * Answers "what does this do", with nothing to confirm — a single "OK" is the
 * whole affordance, unlike [confirmDialog], which exists to ask before an
 * action rather than to explain one that already happened or that a control
 * nearby is about to.
 */
internal fun Activity.infoDialog(@StringRes title: Int, @StringRes message: Int) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

/**
 * Asks before a consequential or irreversible action: title, message, a
 * destructive-sounding positive button, and a plain cancel.
 *
 * The shape every "revoke", "release", "leave", "clear" and "lock" confirmation
 * in this app already used independently — pulled into one place so a future
 * change to how that confirmation behaves (the exact wording of cancel, whether
 * it is a `TextView` or a `String` message) happens once.
 */
internal fun Activity.confirmDialog(
    @StringRes title: Int,
    message: String,
    @StringRes positiveText: Int,
    onConfirm: () -> Unit,
) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positiveText) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** As above, for a title that isn't a string resource — e.g. the name of the thing being deleted. */
internal fun Activity.confirmDialog(
    title: String,
    message: String,
    @StringRes positiveText: Int,
    onConfirm: () -> Unit,
) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positiveText) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun Activity.confirmDialog(
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes positiveText: Int,
    onConfirm: () -> Unit,
) = confirmDialog(title, getString(message), positiveText, onConfirm)

/**
 * Asks for one line of text: an optional explainer, a prefilled field, applied
 * only if what comes back is not blank.
 *
 * "Name this", "rename this", "type the network name" are all this same shape
 * with a different title and hint — see the places that used to build this by
 * hand for what varied and what didn't.
 */
internal fun Activity.promptForText(
    @StringRes title: Int,
    @StringRes hint: Int,
    prefill: String = "",
    message: String? = null,
    @StringRes positiveText: Int = android.R.string.ok,
    onChosen: (String) -> Unit,
) {
    val input = EditText(this).apply {
        this.hint = getString(hint)
        setText(prefill)
        setSingleLine()
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dimen(R.dimen.screen_padding)
        setPadding(pad, pad, pad, 0)
        message?.let { addView(captionText(it)) }
        addView(input)
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(content)
        .setPositiveButton(positiveText) { _, _ ->
            val chosen = input.text.toString().trim()
            if (chosen.isNotEmpty()) onChosen(chosen)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/**
 * A note dialog whose title and confirm button share the same text — "approve
 * or deny, optionally with a reason", the shape both the child's request inbox
 * and a parent's use. The note is handed back exactly as typed; trimming or
 * turning a blank note into `null` is left to the caller, because the two
 * existing callers did not agree on that and neither was wrong to want it.
 */
internal fun Activity.decisionDialog(@StringRes label: Int, onConfirmed: (note: String) -> Unit) {
    val note = EditText(this).apply { hint = getString(R.string.inbox_note_hint) }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dimen(R.dimen.screen_padding)
        setPadding(pad, pad, pad, 0)
        addView(note)
    }
    AlertDialog.Builder(this)
        .setTitle(label)
        .setView(content)
        .setPositiveButton(label) { _, _ -> onConfirmed(note.text.toString()) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/**
 * A value label above a slider, inside a confirmation dialog — for choosing one
 * number and nothing else. [max]/[initialProgress] are raw `SeekBar` steps;
 * [format] turns a step into what the label shows, so this works equally for a
 * minutes-via-[LimitSlider] caller and a plain percentage.
 */
internal fun Activity.sliderDialog(
    @StringRes title: Int,
    max: Int,
    initialProgress: Int,
    format: (Int) -> String,
    @StringRes positiveText: Int = android.R.string.ok,
    onChosen: (Int) -> Unit,
) = sliderDialog(getString(title), max, initialProgress, format, positiveText, onChosen)

/** As above, for a title that isn't a string resource — e.g. an app's own label. */
internal fun Activity.sliderDialog(
    title: String,
    max: Int,
    initialProgress: Int,
    format: (Int) -> String,
    @StringRes positiveText: Int = android.R.string.ok,
    onChosen: (Int) -> Unit,
) {
    val value = TextView(this).apply {
        styleAs(R.style.TextAppearance_Dementor_Metric)
        text = format(initialProgress)
    }
    val bar = SeekBar(this).apply {
        this.max = max
        progress = initialProgress
        // TalkBack hears the same value the label shows, not "50 percent".
        bindAccessibleValue { format(progress) }
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = format(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dimen(R.dimen.screen_padding)
                setPadding(pad, pad, pad, 0)
                addView(value)
                addView(bar)
            },
        )
        .setPositiveButton(positiveText) { _, _ -> onChosen(bar.progress) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
