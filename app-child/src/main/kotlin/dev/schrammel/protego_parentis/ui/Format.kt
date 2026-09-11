package dev.schrammel.protego_parentis.ui

import android.content.Context
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.format.formatDuration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes as minutesOf

/** "1h 30m" for a limit setting, or "No limit" when unset. */
fun Context.formatLimit(minutes: Int): String =
    if (minutes <= 0) getString(R.string.format_no_limit_capitalized) else formatDuration(minutes.minutesOf)

fun formatTime(value: LocalTime): String = "%02d:%02d".format(value.hour, value.minute)

/** The same clock-face time, for an instant rather than a bare wall-clock value. */
fun formatTime(instant: Instant): String =
    formatTime(instant.atZone(ZoneId.systemDefault()).toLocalTime())
