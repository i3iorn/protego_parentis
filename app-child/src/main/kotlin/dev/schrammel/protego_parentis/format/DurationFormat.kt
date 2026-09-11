package dev.schrammel.protego_parentis.format

import android.content.Context
import dev.schrammel.protego_parentis.R
import kotlin.time.Duration

/**
 * Which shape a duration takes, worked out before any string is chosen for it.
 *
 * Kept pure and Android-free on purpose: this is where the actual thresholds
 * live ("under a minute" vs. rounding to zero, when hours/minutes each drop
 * out), and it is the part worth a plain JVM test — see [DurationFormatTest].
 * The localized string for each case is chosen in [formatDuration], which
 * needs a real `Context` and so cannot itself be unit-tested without one.
 */
internal sealed interface DurationShape {
    data object NoLimit : DurationShape
    data object UnderAMinute : DurationShape
    data class HoursOnly(val hours: Int) : DurationShape
    data class MinutesOnly(val minutes: Int) : DurationShape
    data class Both(val hours: Int, val minutes: Int) : DurationShape
}

internal fun shapeOf(value: Duration): DurationShape {
    if (value.isInfinite()) return DurationShape.NoLimit
    val totalMinutes = value.inWholeMinutes
    if (totalMinutes <= 0L) return DurationShape.UnderAMinute

    val hours = (totalMinutes / 60).toInt()
    val minutes = (totalMinutes % 60).toInt()
    return when {
        hours == 0 -> DurationShape.MinutesOnly(minutes)
        minutes == 0 -> DurationShape.HoursOnly(hours)
        else -> DurationShape.Both(hours, minutes)
    }
}

/**
 * Human-readable durations: "2h 15m", "45m", "under a minute".
 *
 * Lives outside `ui` (and is used by enforcement's notifiers as well as every
 * screen that shows a budget) precisely because it isn't a UI concern — it is
 * pure formatting that happens to need a `Context` for localized strings.
 */
fun Context.formatDuration(value: Duration): String = when (val shape = shapeOf(value)) {
    is DurationShape.NoLimit -> getString(R.string.format_no_limit)
    is DurationShape.UnderAMinute -> getString(R.string.format_under_a_minute)
    is DurationShape.HoursOnly -> getString(R.string.format_hours, shape.hours)
    is DurationShape.MinutesOnly -> getString(R.string.format_minutes, shape.minutes)
    is DurationShape.Both -> getString(R.string.format_hours_minutes, shape.hours, shape.minutes)
}
