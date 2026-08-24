package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.dementor_watch.R

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

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
 * A raised surface holding a list of rows.
 *
 * Rows sitting straight on the window background had nothing marking where the
 * list started or ended. Grouping them onto a bordered surface is the difference
 * between a list and a pile.
 */
internal fun Activity.group(rows: List<View>): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundResource(R.drawable.card)
    val side = dimen(R.dimen.card_padding)
    setPadding(side, 0, side, 0)
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)

    rows.forEachIndexed { index, row ->
        if (index > 0) addView(divider())
        addView(row)
    }
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

/** A hairline between rows, so a list reads as a list. */
internal fun Activity.divider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(MATCH, dimen(R.dimen.divider_height))
    setBackgroundResource(R.color.card_stroke)
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
