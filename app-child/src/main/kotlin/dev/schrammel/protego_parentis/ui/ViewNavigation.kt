package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R

/**
 * The row a screen is navigated with: a title, a line saying what is behind it,
 * and a chevron — except where the row runs an action rather than opening a
 * screen, in which case there is no chevron to promise somewhere else to go.
 */

/**
 * A row that goes somewhere.
 *
 * Title, a line explaining what is behind it, and a chevron. The description is
 * not decoration: with a dozen destinations, "Categories" alone does not tell a
 * parent whether that is where the daily limit lives.
 *
 * [showChevron] is false for a row that runs a local action rather than opening
 * a screen ("Lock now", "Locate") — a chevron promises somewhere else to go,
 * which is exactly what tapping those does not do.
 */
internal fun Activity.navigationRow(
    title: String,
    subtitle: String,
    showChevron: Boolean = true,
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
    if (showChevron) {
        addView(
            TextView(context).apply {
                // A plain chevron rather than an icon asset: it takes its colour from
                // the theme, so it is correct in both without a second drawable. Set in
                // the quieter colour so it marks the row without shouting.
                text = "›"
                styleAs(R.style.TextAppearance_Dementor_Headline)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(dimen(R.dimen.space_md), 0, 0, 0)
                // Decorative: the title and subtitle beside it already carry the
                // row's meaning, and a screen reader would otherwise read the glyph
                // aloud as "greater than" at the end of every row.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                contentDescription = null
            },
        )
    }
}

/**
 * The same row, from string resources — the common case, since most rows are
 * static. Named `row` rather than another `navigationRow` overload because that
 * is what every screen using this shape already called it before this existed.
 */
internal fun Activity.row(
    @StringRes titleRes: Int,
    @StringRes detailRes: Int,
    showChevron: Boolean = true,
    onClick: () -> Unit,
): LinearLayout = navigationRow(getString(titleRes), getString(detailRes), showChevron, onClick)
