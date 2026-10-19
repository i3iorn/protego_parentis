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
internal fun Activity.infoDialog(@StringRes title: Int, @StringRes message: Int, learnMore: Boolean = false) {
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
    // "Learn more" opens the tutorial — the full "how rules combine" essay the
    // short body deliberately leaves out. Only the advanced editor asks for it:
    // the explanation belongs beside the condition tree it describes, and the
    // simpler screens already get a one-line answer (rules-UX phase F).
    if (learnMore) {
        dialog.setNeutralButton(R.string.context_learn_more) { _, _ ->
            startActivity(RulesTutorialActivity.intent(this))
        }
    }
    dialog.show()
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

/**
 * A list of choices grouped under quiet sub-headings, for a chooser that has grown
 * past "one flat list" — "Time", "Place", "Device", "Health". [sections] pairs an
 * optional header with its options; a null header is a group with no label. A
 * header is shown but not selectable, so tapping it does nothing rather than
 * picking whatever happened to sit beneath it.
 *
 * Built on [android.widget.BaseAdapter] rather than the plain `setItems` list so
 * the headers can be interleaved as real rows; `setItems` gives every row the same
 * weight and no way to separate kinds of choice.
 */
internal fun Activity.sectionedMenu(
    @StringRes title: Int,
    sections: List<Pair<Int?, List<Pair<String, () -> Unit>>>>,
) {
    val labels = mutableListOf<String>()
    val selectable = mutableListOf<Boolean>()
    val actions = mutableListOf<() -> Unit>()
    sections.forEach { (header, options) ->
        if (header != null) {
            labels += getString(header)
            selectable += false
            actions += {}
        }
        options.forEach { (label, action) ->
            labels += label
            selectable += true
            actions += action
        }
    }
    val pad = dimen(R.dimen.space_sm)
    val adapter = object : android.widget.BaseAdapter() {
        override fun getCount(): Int = labels.size
        override fun getItem(position: Int): Any = labels[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun isEnabled(position: Int): Boolean = selectable[position]
        override fun getView(
            position: Int,
            convertView: android.view.View?,
            parent: android.view.ViewGroup,
        ): android.view.View {
            val row = (convertView as? TextView) ?: TextView(parent.context)
            row.text = labels[position]
            row.styleAs(
                if (selectable[position]) {
                    R.style.TextAppearance_Dementor_ListItem
                } else {
                    R.style.TextAppearance_Dementor_Section
                },
            )
            row.setPadding(pad, pad, pad, pad)
            return row
        }
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setAdapter(adapter) { _, index -> actions[index]() }
        .show()
}
