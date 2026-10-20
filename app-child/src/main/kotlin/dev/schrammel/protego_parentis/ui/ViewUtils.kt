package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.StyleRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Resolves a dimension resource to pixels.
 *
 * These screens are built in code rather than XML, so every padding would
 * otherwise be a bare number repeated across a dozen files. Going through
 * `res/values/dimens.xml` keeps the spacing scale in one editable place.
 */
internal fun Context.dimen(@DimenRes id: Int): Int = resources.getDimensionPixelSize(id)

/** Applies one of the roles from `res/values/styles.xml`. */
internal fun TextView.styleAs(@StyleRes appearance: Int): TextView = apply {
    setTextAppearance(appearance)
}

/**
 * Makes a slider announce [spoken] rather than its raw percentage.
 *
 * A bare `SeekBar` reports its value to TalkBack as "50 percent" — useless for a
 * control whose whole purpose is choosing minutes. The delegate replaces both the
 * node's text (what focus announces) and its event text (what a change announces)
 * with the same string the visible label shows.
 */
internal fun View.bindAccessibleValue(spoken: () -> String) {
    ViewCompat.setAccessibilityDelegate(this, accessibleValueDelegate(spoken))
}

/**
 * The delegate [bindAccessibleValue] installs, exposed so its two overrides can
 * be tested directly without a live accessibility service — Robolectric will not
 * call them on its own.
 */
internal fun accessibleValueDelegate(spoken: () -> String): AccessibilityDelegateCompat =
    object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfoCompat
        ) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.text = spoken()
            info.contentDescription = spoken()
        }

        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            super.onPopulateAccessibilityEvent(host, event)
            event.text.clear()
            event.text.add(spoken())
        }
    }

/**
 * The shared configuration of every "how many minutes?" slider.
 *
 * A [android.widget.SeekBar] only counts in whole steps from zero, so a limit in
 * minutes has to be divided by the step size going in and multiplied coming out.
 * Doing that inline in three screens meant three chances to use a different step
 * size and quietly produce different rounding in each.
 */
internal object LimitSlider {
    const val STEP_MINUTES = 5
    const val MAX_STEPS = 48

    fun minutesFor(progress: Int): Int = progress * STEP_MINUTES

    fun progressFor(minutes: Int): Int =
        (minutes / STEP_MINUTES).coerceIn(0, MAX_STEPS)
}
