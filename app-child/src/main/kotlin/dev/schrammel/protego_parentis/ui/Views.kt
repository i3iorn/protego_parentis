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
import dev.schrammel.protego_parentis.ui.rules.RuleWriteKind

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

    withRipple()
    setOnClickListener { onClick() }
}

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
 * The small "ⓘ" a heading carries when what it groups is not obvious from its
 * label alone — plain text rather than a drawable, the same choice
 * [navigationRow]'s chevron made, so it takes its colour from the theme with
 * no second asset to keep in sync with it.
 */
internal fun Activity.infoButton(@StringRes title: Int, @StringRes message: Int): TextView =
    TextView(this).apply {
        text = "ⓘ"
        styleAs(R.style.TextAppearance_Dementor_ListItem)
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        val pad = dimen(R.dimen.space_sm)
        setPadding(pad, 0, 0, 0)
        contentDescription = getString(title)
        withRipple()
        setOnClickListener { infoDialog(title, message) }
    }

/**
 * [heading] with an [infoButton] beside it. Takes the heading view itself
 * rather than building one, since [sectionHeader] and [ContextRuleEditActivity]'s
 * own differently-styled local heading both need this same pairing.
 */
internal fun Activity.headingWithInfo(
    heading: TextView,
    @StringRes infoTitle: Int,
    @StringRes infoMessage: Int,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(heading.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
    addView(infoButton(infoTitle, infoMessage))
}

/**
 * A titled group of rows, added to [column] only if there are any.
 *
 * The empty case is the whole reason this exists. Rows are now conditional — a
 * family that has put a feature away does not see its row — and a heading standing
 * over an empty card looks like something failed to load. Nothing to show means
 * nothing is shown, heading included.
 *
 * This is for a section whose whole existence is conditional — "waiting
 * devices", "refused devices" — not one that always exists but can be
 * currently empty, such as "trusted devices" or "all apps": those want their
 * header every time, with an explanatory line (`devices_none`, `apps_none`)
 * standing in for the list, precisely so an empty family/catalog reads as
 * "checked, there are none" rather than "this section vanished." Screens
 * doing that add their header directly instead of calling this.
 *
 * A null [title] gives an unlabelled group, for the first block on a screen where
 * the screen title has already said what these are.
 */
internal fun Activity.addSection(
    column: LinearLayout,
    title: String?,
    rows: List<View>,
    @StringRes infoTitle: Int? = null,
    @StringRes infoMessage: Int? = null,
) {
    if (rows.isEmpty()) return
    if (title != null) {
        column.addView(
            if (infoTitle != null && infoMessage != null) {
                headingWithInfo(sectionHeader(title), infoTitle, infoMessage)
            } else {
                sectionHeader(title)
            },
        )
    }
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
 * Two evenly-weighted buttons side by side — "approve this or deny it",
 * "allow this or block it". Independently hand-built at three call sites
 * before this existed; pulled in once that passed this project's own
 * third-occurrence threshold.
 */
internal fun Activity.twoButtonRow(
    @StringRes firstLabel: Int,
    onFirst: () -> Unit,
    @StringRes secondLabel: Int,
    onSecond: () -> Unit,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = dimen(R.dimen.space_sm)
    }
    addView(
        Button(context).apply {
            setText(firstLabel)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = dimen(R.dimen.space_xs)
            }
            setOnClickListener { onFirst() }
        },
    )
    addView(
        Button(context).apply {
            setText(secondLabel)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setOnClickListener { onSecond() }
        },
    )
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
 * Something happened since this phone last opened the shared history.
 *
 * Null the same way [familyBanner] is: most opens have nothing to report, and a
 * screen should not carry a permanent card saying so.
 */
internal fun Activity.activityBanner(unseenCount: Int, onOpen: () -> Unit): View? {
    if (unseenCount == 0) return null

    return card {
        addView(
            TextView(context).apply {
                text = resources.getQuantityString(R.plurals.activity_banner_title, unseenCount, unseenCount)
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(captionText(getString(R.string.activity_banner_detail)))
        addView(
            actionButton().apply {
                setText(R.string.activity_banner_open)
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

/** How long typing has to pause before [searchField] actually re-renders. */
private const val SEARCH_DEBOUNCE_MILLIS = 150L

/**
 * The filter row above a list of apps.
 *
 * One row, used by every screen that lists apps, so the same job feels the same
 * everywhere. [onQuery] is expected to re-render from a list already in
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

/** Role, PIN, join — first-time setup's fixed sequence, for [stepIndicator]. */
internal const val ONBOARDING_STEPS = 3

/**
 * "Step 2 of 3," for the handful of screens that only ever run back-to-back
 * during first-time setup (role, PIN, join). Each of those screens otherwise
 * looks like a complete, self-contained thing, and backing out of the middle
 * one gives no sense of whether that abandons one step or the whole setup.
 */
internal fun Activity.stepIndicator(current: Int, total: Int = ONBOARDING_STEPS): TextView =
    captionText(getString(R.string.onboarding_step, current, total))

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
 * Marks this view clickable with the system's default ripple/highlight — the
 * feedback a real control gives, rather than a plain view that happens to have
 * a listener. Pulled out once three call sites ([navigationRow], [showMoreRow],
 * and `HomeActivity`'s own openable app rows) had each rebuilt it by hand.
 */
internal fun View.withRipple(): View = apply {
    val outValue = android.util.TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
    setBackgroundResource(outValue.resourceId)
    isClickable = true
    isFocusable = true
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
    withRipple()
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

/**
 * A short message, gone on its own. The common case for "that worked" or "that
 * didn't". [duration] defaults to the ordinary case; pass `Toast.LENGTH_LONG`
 * for a message worth more time to read, such as an error explaining itself.
 */
internal fun Activity.toast(message: String, duration: Int = Toast.LENGTH_SHORT) =
    Toast.makeText(this, message, duration).show()

internal fun Activity.toast(@StringRes messageRes: Int, duration: Int = Toast.LENGTH_SHORT) =
    toast(getString(messageRes), duration)

/**
 * The acknowledgement a rule editor gives after writing through a
 * [dev.schrammel.protego_parentis.ui.rules.RuleWriteKind] store: a local write
 * is already live and needs no word, a remote one only reaches the child once
 * sync carries it there. Five screens used to hand-roll this branch; the store
 * knows which kind it is, so they ask it instead.
 */
internal fun Activity.acknowledgeRuleWrite(store: RuleWriteKind) {
    if (store.writesRemotely) toast(R.string.context_save_sent_remote)
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
