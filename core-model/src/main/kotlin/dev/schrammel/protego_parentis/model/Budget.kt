package dev.schrammel.protego_parentis.model

import java.time.DayOfWeek
import kotlin.time.Duration

/**
 * A daily allowance that can differ per weekday.
 *
 * [Duration.INFINITE] means "no limit" — modelling unlimited as a duration rather
 * than as null keeps every downstream arithmetic path total, so there is no
 * null-check to forget in the engine.
 */
data class WeeklyBudget(
    val default: Duration,
    val overrides: Map<DayOfWeek, Duration> = emptyMap(),
) {
    init {
        require(!default.isNegative()) { "default budget must not be negative" }
        require(overrides.values.none { it.isNegative() }) { "budget overrides must not be negative" }
    }

    fun forDay(day: DayOfWeek): Duration = overrides[day] ?: default

    val isUnlimited: Boolean get() = default.isInfinite() && overrides.values.all { it.isInfinite() }

    companion object {
        val UNLIMITED = WeeklyBudget(Duration.INFINITE)

        /** Same allowance every day. */
        fun flat(amount: Duration) = WeeklyBudget(amount)

        /** A weekday/weekend split, the shape most families actually want. */
        fun schoolWeek(schoolNight: Duration, weekend: Duration) = WeeklyBudget(
            default = schoolNight,
            overrides = mapOf(
                DayOfWeek.FRIDAY to weekend,
                DayOfWeek.SATURDAY to weekend,
                DayOfWeek.SUNDAY to weekend,
            ),
        )
    }
}
