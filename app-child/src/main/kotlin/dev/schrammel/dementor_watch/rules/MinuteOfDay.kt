package dev.schrammel.dementor_watch.rules

import java.time.LocalTime

/**
 * Minutes since midnight — how bedtime and schedule windows are stored, because a
 * plain `Int` survives `SharedPreferences` and JSON without a serializer of its
 * own for [LocalTime].
 */
internal const val MINUTES_PER_HOUR = 60
internal const val MINUTES_PER_DAY = 24 * 60

internal fun LocalTime.toMinuteOfDay(): Int = hour * MINUTES_PER_HOUR + minute

/** The [LocalTime] a minute-of-day value names, clamping anything out of range. */
internal fun Int.toLocalTime(): LocalTime {
    val clamped = coerceIn(0, MINUTES_PER_DAY - 1)
    return LocalTime.of(clamped / MINUTES_PER_HOUR, clamped % MINUTES_PER_HOUR)
}
