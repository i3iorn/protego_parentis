package dev.schrammel.dementor_watch.ui

import android.content.Context

/**
 * Density-independent pixels to actual pixels.
 *
 * These screens are built in code rather than XML, so this conversion appears
 * everywhere. It was previously copy-pasted into every activity.
 */
internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

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
