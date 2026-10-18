package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R

/**
 * The buttons a screen can stand on its own: the ordinary action, the one main
 * action, and the one nobody should press by accident.
 */

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
 * The one button on a screen that does the thing the screen exists for.
 *
 * Every button in the app used the theme's default, which reads as secondary —
 * useful when there are several equally-weighted choices, misleading when there
 * is one obvious next step. Filled in the brand colour so the main action is the
 * one the eye lands on.
 */
internal fun Activity.primaryActionButton(): Button = actionButton().apply {
    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand))
    setTextColor(ContextCompat.getColor(context, R.color.on_brand))
}

/**
 * A button that does something the reader may regret: sending an emergency,
 * releasing the phone, leaving the family. Reads in the critical colour rather
 * than the brand's, so it is never mistaken for the ordinary next step.
 */
internal fun Activity.destructiveActionButton(): Button = actionButton().apply {
    backgroundTintList =
        ColorStateList.valueOf(ContextCompat.getColor(context, R.color.state_critical))
    setTextColor(ContextCompat.getColor(context, R.color.on_brand))
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
