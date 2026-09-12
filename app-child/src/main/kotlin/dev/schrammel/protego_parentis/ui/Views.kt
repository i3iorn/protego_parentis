package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * The pieces every screen is built from.
 *
 * These exist because the app had grown into stacks of default buttons: each
 * screen was a column of identical grey rectangles with no hierarchy, which reads
 * as a debug menu rather than a product. Navigation is now a labelled row with a
 * description, the way a settings screen normally works, and buttons are reserved
 * for actions that actually do something.
 */

/** A screen's scrolling body, padded and ready to have content added. */
internal fun Activity.contentColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val pad = dimen(R.dimen.screen_padding)
    setPadding(pad, pad, pad, dimen(R.dimen.space_4xl))
}

/** Wraps [column] in a scroll view and installs it as the screen's content. */
internal fun Activity.showColumn(column: LinearLayout) {
    setContentView(
        ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            isFillViewport = true
            addView(column, LinearLayout.LayoutParams(MATCH, WRAP))
        },
    )
}

/** The screen's name. One per screen, at the top. */
internal fun Activity.screenTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    styleAs(R.style.TextAppearance_Dementor_Title)
    setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_lg))
}

/**
 * A group heading.
 *
 * Set in small spaced capitals in the quieter colour, so it reads as a label for
 * what follows rather than as another line of text competing with the title.
 */
internal fun Activity.sectionHeader(text: String): TextView = TextView(this).apply {
    this.text = text
    styleAs(R.style.TextAppearance_Dementor_Section)
    setPadding(0, dimen(R.dimen.section_spacing), 0, dimen(R.dimen.space_sm))
}

/**
 * Rows beyond this count start hidden behind "Show N more" — see [group].
 *
 * A card holding dozens of apps, devices, or history entries used to just get
 * taller than the screen instead of ever explaining itself; this keeps the
 * initial view scannable while everything is still one tap away, in place,
 * rather than behind a second screen.
 */
private const val COLLAPSED_ROW_LIMIT = 10

/**
 * A raised surface holding a list of rows.
 *
 * Rows sitting straight on the window background had nothing marking where the
 * list started or ended. Grouping them onto a bordered surface is the difference
 * between a list and a pile.
 *
 * Past [COLLAPSED_ROW_LIMIT] rows, the rest sit behind a "Show N more" row that
 * reveals them into this same card on tap — never a new screen, since nothing
 * about the list itself changed, only how much of it is currently shown.
 */
internal fun Activity.group(rows: List<View>): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundResource(R.drawable.card)
    val side = dimen(R.dimen.card_padding)
    setPadding(side, 0, side, 0)
    // Same margin as [card], which draws the same surface. Without it a group
    // sits flush against whatever precedes it wherever no section header
    // intervenes, and two card edges touching read as one broken card.
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = dimen(R.dimen.space_md)
    }

    addCollapsibleRows(this, rows, divider = { divider() })
}

/**
 * Adds [rows] to [container], showing at most [COLLAPSED_ROW_LIMIT] until a
 * "Show N more" line reveals the rest in place.
 *
 * The one mechanism behind both [group]'s carded lists (which pass [divider] to
 * draw the hairline between rows the card style already uses) and a screen's own
 * flat list of already-self-contained rows or cards, which has no divider of its
 * own to match and passes none.
 */
internal fun Activity.addCollapsibleRows(container: LinearLayout, rows: List<View>, divider: (() -> View)? = null) {
    val visible = rows.take(COLLAPSED_ROW_LIMIT)
    val hidden = rows.drop(COLLAPSED_ROW_LIMIT)

    visible.forEachIndexed { index, row ->
        if (index > 0) divider?.let { container.addView(it()) }
        container.addView(row)
    }

    if (hidden.isNotEmpty()) {
        // Separates the last visible row from the "show more" row itself. On
        // expansion this same divider ends up separating that last visible row
        // from the first hidden one, which is exactly where it belongs — so the
        // loop below must not add its own leading divider on top of it.
        divider?.let { container.addView(it()) }
        lateinit var moreRow: View
        moreRow = showMoreRow(hidden.size) {
            container.removeView(moreRow)
            hidden.forEachIndexed { index, row ->
                if (index > 0) divider?.let { container.addView(it()) }
                container.addView(row)
            }
        }
        container.addView(moreRow)
    }
}

/**
 * The tappable line standing in for whatever [group] hid past
 * [COLLAPSED_ROW_LIMIT]. Plain text in the brand colour rather than a button or
 * a [navigationRow]'s chevron — a chevron says "this goes somewhere else",
 * which is exactly what tapping this does not do.
 */
private fun Activity.showMoreRow(hiddenCount: Int, onClick: () -> Unit): TextView = TextView(this).apply {
    text = resources.getQuantityString(R.plurals.show_more_rows, hiddenCount, hiddenCount)
    styleAs(R.style.TextAppearance_Dementor_Body)
    setTextColor(ContextCompat.getColor(context, R.color.brand))
    gravity = Gravity.CENTER
    val vertical = dimen(R.dimen.space_md)
    setPadding(0, vertical, 0, vertical)

    val outValue = android.util.TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
    setBackgroundResource(outValue.resourceId)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

/**
 * A titled group of rows, added to [column] only if there are any.
 *
 * The empty case is the whole reason this exists. Rows are now conditional — a
 * family that has put a feature away does not see its row — and a heading standing
 * over an empty card looks like something failed to load. Nothing to show means
 * nothing is shown, heading included.
 *
 * A null [title] gives an unlabelled group, for the first block on a screen where
 * the screen title has already said what these are.
 */
internal fun Activity.addSection(column: LinearLayout, title: String?, rows: List<View>) {
    if (rows.isEmpty()) return
    if (title != null) column.addView(sectionHeader(title))
    column.addView(group(rows))
}

/**
 * A button standing on its own in a screen's column.
 *
 * A bare `Button` constructed in code has no layout params, which means no margin,
 * which means it sits flush against the paragraph explaining it. Every screen that
 * did that looked like the text and the button had collided.
 */
internal fun Activity.actionButton(): Button = Button(this).apply {
    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
        topMargin = dimen(R.dimen.space_md)
    }
}

/**
 * The card that says a family is not finished being set up.
 *
 * Null when there is nothing to say, which is the usual case — a family that works
 * should not carry a permanent card explaining that it works.
 *
 * [onOpen] always leads to the devices screen, because in every state that
 * produces a card, that is the screen with the button on it.
 */
internal fun Activity.familyBanner(snapshot: FamilySnapshot, onOpen: () -> Unit): View? {
    val title: String
    val detail: String

    when (snapshot.state) {
        FamilyState.SOMEONE_WAITING -> {
            title = resources.getQuantityString(
                R.plurals.family_waiting_title,
                snapshot.waiting,
                snapshot.waiting,
            )
            detail = getString(R.string.family_waiting_detail)
        }

        FamilyState.NOT_YET_PAIRED -> {
            title = getString(R.string.family_unpaired_title)
            detail = getString(R.string.family_unpaired_detail)
        }

        FamilyState.ALONE, FamilyState.READY -> return null
    }

    return card {
        addView(
            TextView(context).apply {
                text = title
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(captionText(detail))
        addView(
            actionButton().apply {
                setText(R.string.family_open_devices)
                setOnClickListener { onOpen() }
            },
        )
    }
}

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

/**
 * The filter row above a list of apps.
 *
 * One row, used by every screen that lists apps, so the same job feels the same
 * everywhere. [onQuery] fires on each keystroke and is expected to re-render from
 * a list already in memory — see [AppSearch]; re-reading `PackageManager` per
 * character would undo the background load these screens do precisely to stay
 * responsive.
 */
internal fun Activity.searchField(
    prefill: String = "",
    onQuery: (String) -> Unit,
): EditText = EditText(this).apply {
    hint = getString(R.string.app_search_hint)
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
    addTextChangedListener(SimpleWatcher(onQuery))
}

/**
 * "Nothing matched", which is not the same as an empty list.
 *
 * A screen with no apps at all and a screen whose filter excluded them need
 * different things from the reader, so they never share a line.
 */
internal fun Activity.noSearchResults(): TextView = bodyText(getString(R.string.app_search_none))

/**
 * Disabling a [ViewGroup] does not disable its children — Android never made
 * that automatic — so a read-only rules screen (#43) that wants a whole
 * cluster of controls (a day picker, a radio group) inert has to say so
 * explicitly, one level down.
 */
internal fun ViewGroup.setChildrenEnabled(enabled: Boolean) {
    isEnabled = enabled
    for (index in 0 until childCount) getChildAt(index).isEnabled = enabled
}

/**
 * What a [ParentGatedActivity.supportsReadOnly] screen shows in place of any
 * write affordance, before the PIN is entered — see the decision recorded on
 * #43. [onUnlock] is expected to call [ParentGatedActivity.requestUnlock].
 */
internal fun Activity.readOnlyBanner(onUnlock: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dimen(R.dimen.space_hairline), 0, dimen(R.dimen.space_md))
        addView(captionText(getString(R.string.rules_read_only_hint)))
        addView(
            actionButton().apply {
                setText(R.string.rules_unlock_to_edit)
                setOnClickListener { onUnlock() }
            },
        )
    }

internal fun Activity.bodyText(text: String): TextView = TextView(this).apply {
    this.text = text
    styleAs(R.style.TextAppearance_Dementor_Body)
    setPadding(0, dimen(R.dimen.space_hairline), 0, dimen(R.dimen.space_hairline))
}

internal fun Activity.captionText(text: String): TextView = TextView(this).apply {
    this.text = text
    styleAs(R.style.TextAppearance_Dementor_Caption)
    setPadding(0, dimen(R.dimen.space_hairline), 0, dimen(R.dimen.space_xs))
}

/** A grouped surface, for a summary that should read as one thing. */
internal fun Activity.card(build: LinearLayout.() -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        val pad = dimen(R.dimen.card_padding)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dimen(R.dimen.space_md)
        }
        build()
    }

/**
 * A row that goes somewhere.
 *
 * Title, a line explaining what is behind it, and a chevron. The description is
 * not decoration: with a dozen destinations, "Categories" alone does not tell a
 * parent whether that is where the daily limit lives.
 */
internal fun Activity.navigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    val vertical = dimen(R.dimen.space_md)
    setPadding(0, vertical, 0, vertical)

    // Gives the row a touch response, so it looks like the control it is.
    val outValue = android.util.TypedValue()
    context.theme.resolveAttribute(
        android.R.attr.selectableItemBackground,
        outValue,
        true,
    )
    setBackgroundResource(outValue.resourceId)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }

    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(
                TextView(context).apply {
                    text = title
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
            addView(
                TextView(context).apply {
                    text = subtitle
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                },
            )
        },
    )
    addView(
        TextView(context).apply {
            // A plain chevron rather than an icon asset: it takes its colour from
            // the theme, so it is correct in both without a second drawable. Set in
            // the quieter colour so it marks the row without shouting.
            text = "›"
            styleAs(R.style.TextAppearance_Dementor_Headline)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dimen(R.dimen.space_md), 0, 0, 0)
        },
    )
}

/**
 * The same row, from string resources — the common case, since most rows are
 * static. Named `row` rather than another `navigationRow` overload because that
 * is what every screen using this shape already called it before this existed.
 */
internal fun Activity.row(
    @StringRes titleRes: Int,
    @StringRes detailRes: Int,
    onClick: () -> Unit,
): LinearLayout = navigationRow(getString(titleRes), getString(detailRes), onClick)

/** A hairline between rows, so a list reads as a list. */
internal fun Activity.divider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(MATCH, dimen(R.dimen.divider_height))
    setBackgroundResource(R.color.card_stroke)
}

/** A short message, gone on its own. The common case for "that worked" or "that didn't". */
internal fun Activity.toast(message: String) =
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

internal fun Activity.toast(@StringRes messageRes: Int) = toast(getString(messageRes))

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

/** A label and a value on one line, for a summary. */
internal fun Activity.statRow(label: String, value: String): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))

        addView(
            TextView(context).apply {
                text = label
                styleAs(R.style.TextAppearance_Dementor_Body)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        addView(
            TextView(context).apply {
                text = value
                styleAs(R.style.TextAppearance_Dementor_Body)
            },
        )
    }
