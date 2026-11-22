package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R

/**
 * The two pieces of feedback every screen needs and several used to hand-roll:
 * what an empty list says, and what stays on screen after an action instead of
 * a toast that is gone by the time someone looks back up.
 */

/**
 * An empty list that explains itself: one line of why it is empty, one of what
 * to do about it, centred rather than sitting in the top-left corner like an
 * accident. Several screens had hand-rolled versions; this is the one shape.
 */
internal fun Activity.emptyState(title: String, detail: String? = null): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = dimen(R.dimen.screen_padding)
        setPadding(pad, pad * 2, pad, pad * 2)
        addView(
            TextView(this@emptyState).apply {
                text = title
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
                gravity = Gravity.CENTER
            },
        )
        if (!detail.isNullOrBlank()) {
            addView(
                captionText(detail).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dimen(R.dimen.space_sm), 0, 0)
                },
            )
        }
    }

/**
 * A banner that stays put after an action, tinted by outcome: green for
 * something that succeeded, red for something that failed. Toasts vanish —
 * the same lesson the form-error captions learned — and a parent who tapped
 * Approve and looked up deserves to still see that it happened.
 */
internal fun Activity.infoBanner(bannerText: String, ok: Boolean): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card)
        val color = ContextCompat.getColor(this@infoBanner, if (ok) R.color.state_ok else R.color.state_critical)
        addView(
            View(this@infoBanner).apply {
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(MATCH, dimen(R.dimen.space_xs))
            },
        )
        val pad = dimen(R.dimen.card_padding)
        setPadding(pad, pad, pad, pad)
        addView(
            TextView(this@infoBanner).apply {
                this.text = bannerText
                styleAs(R.style.TextAppearance_Dementor_ListItem)
                setTextColor(color)
            },
        )
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dimen(R.dimen.space_md)
        }
    }
