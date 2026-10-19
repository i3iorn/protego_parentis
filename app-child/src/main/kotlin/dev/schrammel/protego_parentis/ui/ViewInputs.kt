package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.schrammel.protego_parentis.R

/**
 * The inputs a screen is built from: the filter field above a list of apps, and
 * the minutes slider used to set a limit in place.
 */

/**
 * Minimal text watcher, because only the final value is ever wanted.
 *
 * Shared rather than per-file: it was private to the rule editor until the app
 * lists needed the same thing, and a second copy is how two fields start
 * behaving differently.
 */
internal class SimpleWatcher(private val onChanged: (String) -> Unit) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) = onChanged(s?.toString().orEmpty())
}

/** How long typing has to pause before [searchField] actually re-renders. */
private const val SEARCH_DEBOUNCE_MILLIS = 150L

/**
 * The filter row above a list.
 *
 * One row, used by every screen that lists something, so the same job feels the
 * same everywhere. [hint] names what the field searches — "Search apps" on the
 * app screens, "Search rules" on the rules list — because a field that always
 * says "Search apps" over a list of rules quietly claims to be a different
 * screen. [onQuery] is expected to re-render from a list already in
 * memory — see [AppSearch]; re-reading `PackageManager` per character would
 * undo the background load these screens do precisely to stay responsive.
 *
 * Debounced rather than fired on every keystroke: re-rendering here means
 * tearing down and rebuilding one row per matching app, and on a phone with a
 * couple hundred installed apps that is real layout work — doing it on every
 * single character typed is close enough to the ANR watchdog's threshold to
 * be the actual cause of "the app hangs while I'm typing" rather than a
 * theoretical one. The field itself never waits: the character the parent
 * just typed shows immediately, because that is the platform's own
 * `EditText` rendering, not this callback.
 */
internal fun Activity.searchField(
    prefill: String = "",
    hint: String = getString(R.string.search_hint),
    onQuery: (String) -> Unit,
): EditText = EditText(this).apply {
    this.hint = hint
    // No suggestions: half these queries are package names, and autocorrect
    // turning "deskclock" into two words helps nobody.
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    setSingleLine()
    if (prefill.isNotEmpty()) {
        // Before the listener is attached, so restoring a query does not count
        // as the parent typing. Caret at the end rather than at zero: on the
        // screens that rebuild the whole column after an edit, they should be
        // able to keep typing.
        setText(prefill)
        setSelection(prefill.length)
    }
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        bottomMargin = dimen(R.dimen.space_sm)
    }
    // Reads the field itself when it fires rather than closing over the
    // keystroke's own string, so a burst of typing always ends in exactly one
    // render of the latest query — never one queued render per character.
    val renderLatest = Runnable { onQuery(text?.toString().orEmpty()) }
    addTextChangedListener(
        SimpleWatcher {
            removeCallbacks(renderLatest)
            postDelayed(renderLatest, SEARCH_DEBOUNCE_MILLIS)
        },
    )
}

/**
 * A value label above a minutes slider, as a block inside a screen's own column
 * rather than a dialog — see [sliderDialog] for that version.
 *
 * [onChanged] fires only on a user drag (never on the initial layout pass).
 */
internal fun Activity.limitSliderBlock(
    initialMinutes: Int,
    onChanged: (Int) -> Unit = {},
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL

    val value = TextView(context).apply {
        styleAs(R.style.TextAppearance_Dementor_Metric)
        text = formatLimit(initialMinutes)
    }
    addView(value)
    addView(
        SeekBar(context).apply {
            max = LimitSlider.MAX_STEPS
            progress = LimitSlider.progressFor(initialMinutes)
            // Speak minutes, not the raw step count.
            bindAccessibleValue { formatLimit(LimitSlider.minutesFor(progress)) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val minutes = LimitSlider.minutesFor(progress)
                    value.text = formatLimit(minutes)
                    if (fromUser) onChanged(minutes)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        },
    )
}
