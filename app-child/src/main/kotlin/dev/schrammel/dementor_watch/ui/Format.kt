package dev.schrammel.dementor_watch.ui

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes as minutesOf

/** Human-readable durations: "2h 15m", "45m", "under a minute". */
object Format {

    fun duration(value: Duration): String {
        if (value.isInfinite()) return "no limit"
        val totalMinutes = value.inWholeMinutes
        if (totalMinutes <= 0L) return "under a minute"

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    /** "1h 30m" for a limit setting, or "No limit" when unset. */
    fun limit(minutes: Int): String =
        if (minutes <= 0) "No limit" else duration(minutes.minutesOf)

    fun time(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
}
