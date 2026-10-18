package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.BudgetLevel

/**
 * The "one big number" card, shared by the child's dashboard and a parent's
 * synced summary.
 *
 * Both screens answer the same question first — how much time is left — and did
 * it with two hand-built copies that had to keep agreeing on the type scale, the
 * bar's height, the colour of a level and where the note sits. This is the one
 * copy they now share; the caller decides the words and what, if anything, goes
 * beneath.
 *
 * A null [progress] means "no limit set", which shows no bar at all rather than
 * a full or empty one — an unlimited day is not a measurement.
 *
 * @param subline the "used of" line under the bar, if the caller has one.
 * @param note a caption inside the card, such as the monitor-only note.
 */
internal fun Activity.budgetSummaryCard(
    headline: String,
    level: BudgetLevel,
    progress: Int?,
    subline: String? = null,
    note: String? = null,
): LinearLayout = card {
    addView(
        TextView(context).apply {
            text = headline
            styleAs(R.style.TextAppearance_Dementor_Display)
            // The number itself takes the state colour, not only the bar beneath
            // it. "Nearly out" should be readable from across a room.
            setTextColor(ContextCompat.getColor(context, colourFor(level)))
        },
    )

    progress?.let { used ->
        addView(
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams =
                    LinearLayout.LayoutParams(MATCH, dimen(R.dimen.budget_bar_height)).apply {
                        topMargin = dimen(R.dimen.space_md)
                    }
                max = 100
                this.progress = used
                progressTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, colourFor(level)))
            },
        )
    }

    subline?.let { addView(bodyText(it)) }
    note?.let { addView(captionText(it)) }
}
