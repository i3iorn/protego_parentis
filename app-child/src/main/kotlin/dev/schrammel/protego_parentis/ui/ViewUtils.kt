package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.annotation.StyleRes

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
 * The shared configuration of every "how many minutes?" slider.
 *
 * A [android.widget.SeekBar] only counts in whole steps from zero, so a limit in
 * minutes has to be divided by the step size going in and multiplied coming out.
 * Doing that inline in three screens meant three chances to use a different step
 * size and quietly produce different rounding in each.
 */
internal object LimitSlider {

    /** Five-minute granularity: fine enough to be useful, coarse enough to drag. */
    const val STEP_MINUTES = 5

    /** 96 steps of five minutes: nought to eight hours. */
    const val MAX_STEPS = 96

    fun minutesFor(progress: Int): Int = progress * STEP_MINUTES

    fun progressFor(minutes: Int): Int =
        (minutes / STEP_MINUTES).coerceIn(0, MAX_STEPS)
}
