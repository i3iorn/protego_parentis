package dev.schrammel.dementor_watch.ui

import android.content.Context
import dev.schrammel.dementor_watch.R
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes as minutesOf

/**
 * Which shape a duration takes, worked out before any string is chosen for it.
 *
 * Kept pure and Android-free on purpose: this is where the actual thresholds
 * live ("under a minute" vs. rounding to zero, when hours/minutes each drop
 * out), and it is the part worth a plain JVM test — see [FormatTest]. The
 * localized string for each case is chosen in [formatDuration], which needs a
 * real `Context` and so cannot itself be unit-tested without one.
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

/** Human-readable durations: "2h 15m", "45m", "under a minute". */
fun Context.formatDuration(value: Duration): String = when (val shape = shapeOf(value)) {
    is DurationShape.NoLimit -> getString(R.string.format_no_limit)
    is DurationShape.UnderAMinute -> getString(R.string.format_under_a_minute)
    is DurationShape.HoursOnly -> getString(R.string.format_hours, shape.hours)
    is DurationShape.MinutesOnly -> getString(R.string.format_minutes, shape.minutes)
    is DurationShape.Both -> getString(R.string.format_hours_minutes, shape.hours, shape.minutes)
}

/** "1h 30m" for a limit setting, or "No limit" when unset. */
fun Context.formatLimit(minutes: Int): String =
    if (minutes <= 0) getString(R.string.format_no_limit_capitalized) else formatDuration(minutes.minutesOf)

fun formatTime(value: LocalTime): String = "%02d:%02d".format(value.hour, value.minute)
