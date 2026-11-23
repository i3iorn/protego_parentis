package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.schrammel.protego_parentis.R

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * The scaffolding every screen is built from: the scroll body, the surfaces a
 * list or a summary sits on, and the collapse behaviour that keeps a long list
 * scannable.
 *
 * The pieces with a narrower job live in their own files next door — text in
 * [ViewText], buttons in [ViewButtons], navigation rows in [ViewNavigation],
 * dialogs in [ViewDialogs], inputs in [ViewInputs], and the dashboard banners in
 * [ViewBanners]. This file is what all of those are assembled on top of.
 */

/**
 * A screen's scrolling body, padded and ready to have content added.
 *
 * The way back belongs to the screen's title, not to the body: it is part of
 * the header the screen puts at the top of this column (see [screenTitle]),
 * where a screen that rebuilds its content cannot wipe it.
 */
internal fun Activity.contentColumn(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val pad = dimen(R.dimen.screen_padding)
    setPadding(pad, pad, pad, dimen(R.dimen.space_4xl))
}

/**
 * The way back, as one small chevron in the screen's header.
 *
 * A glyph rather than a line of text: the header carries the screen's name on
 * its other side, and a whole line reading "Back" would take more room than
 * the header is meant to take. The app builds its screens in code and
 * deliberately carries no icon library, and a plain chevron needs no second
 * drawable to be correct in both themes.
 *
 * The glyph is decorative: the control itself speaks for itself as "Go back",
 * and it keeps the 48dp floor the touch targets elsewhere on these screens use.
 */
internal fun Activity.backButton(): TextView = TextView(this).apply {
    text = "‹"
    styleAs(R.style.TextAppearance_Dementor_Headline)
    setTextColor(ContextCompat.getColor(context, R.color.brand))
    gravity = Gravity.CENTER
    minimumWidth = dimen(R.dimen.touch_target)
    minimumHeight = dimen(R.dimen.touch_target)
    contentDescription = getString(R.string.back_button_description)
    withRipple()
    setOnClickListener { finish() }
}

/** Wraps [column] in a scroll view and installs it as the screen's content. */
internal fun Activity.showColumn(column: LinearLayout) {
    setContentView(
        ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            isFillViewport = true
            addView(column, LinearLayout.LayoutParams(MATCH, WRAP))
            keepFieldsVisible()
        },
    )
}

/**
 * Like [showColumn], but with pull-to-refresh wrapped around the scroll view.
 *
 * Only the parent dashboard uses this: it is the one screen a person opens
 * expecting news from the other phone, so the swipe gesture belongs there.
 * Every other screen scrolls normally.
 */
internal fun Activity.showRefreshableColumn(column: LinearLayout): SwipeRefreshLayout {
    val scroll = ScrollView(this).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        isFillViewport = true
        addView(column, LinearLayout.LayoutParams(MATCH, WRAP))
        keepFieldsVisible()
    }
    val refresh = SwipeRefreshLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        addView(scroll)
    }
    setContentView(refresh)
    return refresh
}

/**
 * Scrolls a focused text field into the visible half of the screen, so the
 * keyboard cannot cover what is being typed into.
 *
 * Two hooks, because the keyboard arrives in two different moments: when focus
 * moves (a tap on a field, or next-field navigation), and on every layout pass
 * while a field holds focus — which is what an `adjustResize` resize is. The
 * second hook is what actually rescues the common case: the field was visible
 * before the keyboard opened, and only the *resize* pushes it out of view.
 *
 * Fields are kept at the top quarter of the visible area rather than merely
 * "fully visible", so the field's own suggestions, error text below it, or the
 * next row are not sitting flush against the keyboard either.
 */
internal fun ScrollView.keepFieldsVisible() {
    var active: EditText? = null

    viewTreeObserver.addOnGlobalFocusChangeListener { _, new ->
        if (new is EditText) {
            active = new
            post { scrollFieldIntoView(new) }
        }
    }
    viewTreeObserver.addOnGlobalLayoutListener {
        active?.let { scrollFieldIntoView(it) }
    }
}

private fun ScrollView.scrollFieldIntoView(field: EditText) {
    // The field's top edge measured within this scroll view's content: walk up
    // the parent chain, summing offsets — nesting (cards, sections) is arbitrary.
    var nested: View = field
    var top = field.top
    while (nested.parent !== this && nested.parent is View) {
        nested = nested.parent as View
        top += nested.top
    }
    if (nested.parent !== this) return

    val margin = context.resources.getDimensionPixelSize(R.dimen.space_sm)
    val visibleBottom = scrollY + height - margin
    when {
        // Below the visible area: bring its bottom (and a margin) up.
        top + field.height > visibleBottom ->
            scrollTo(scrollX, (top + field.height - height + margin).coerceAtLeast(0))
        // Above the visible area: bring its top down.
        top < scrollY -> scrollTo(scrollX, top)
        // Visible but hugging the keyboard: lift it to the top quarter, so
        // whatever the field offers below itself (errors, suggestions) shows too.
        top + field.height + margin > scrollY + height - (height / 4) ->
            scrollTo(scrollX, (top - height / 4).coerceAtLeast(0))
    }
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

/** A hairline between rows, so a list reads as a list. */
internal fun Activity.divider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(MATCH, dimen(R.dimen.divider_height))
    setBackgroundResource(R.color.card_stroke)
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
 * Rows beyond this count start hidden behind "Show N more" — see [group].
 *
 * A card holding dozens of apps, devices, or history entries used to just get
 * taller than the screen instead of ever explaining itself; this keeps the
 * initial view scannable while everything is still one tap away, in place,
 * rather than behind a second screen.
 */
private const val COLLAPSED_ROW_LIMIT = 6

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
    @androidx.annotation.StringRes infoTitle: Int? = null,
    @androidx.annotation.StringRes infoMessage: Int? = null,
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
 * Disabling a [ViewGroup] does not disable its children — Android never made
 * that automatic — so a read-only rules screen (#43) that wants a whole
 * cluster of controls (a day picker, a radio group) inert has to say so
 * explicitly, one level down.
 */
internal fun ViewGroup.setChildrenEnabled(enabled: Boolean) {
    isEnabled = enabled
    for (index in 0 until childCount) getChildAt(index).isEnabled = enabled
}
