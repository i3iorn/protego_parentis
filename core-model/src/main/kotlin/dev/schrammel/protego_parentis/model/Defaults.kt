package dev.schrammel.protego_parentis.model

import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How far in advance the teenager is warned that time is running out.
 *
 * Part of [Policy] rather than a constant in the engine, because it is a family
 * preference: a younger child may want a longer run-up, and a fifteen-year-old
 * mid-match may want none at all. Making it data also means the ladder can be
 * tested at values other than the shipped ones.
 */
data class WarningThresholds(
    /** Remaining time at which a one-off "time is nearly up" notification fires. */
    val notice: Duration = Defaults.NOTICE_THRESHOLD,
    /** Remaining time at which a persistent banner and a request button appear. */
    val grace: Duration = Defaults.GRACE_THRESHOLD,
) {
    init {
        require(notice > Duration.ZERO && grace > Duration.ZERO) {
            "warning thresholds must be positive"
        }
        require(notice >= grace) {
            "the notice ($notice) must not come after the grace warning ($grace)"
        }
    }

    companion object {
        val STANDARD = WarningThresholds()
    }
}

/**
 * Default values for the whole product, in one place.
 *
 * These were previously spread across companion objects in eight different files,
 * which made it impossible to answer "what does this app actually do out of the
 * box?" without reading all of them. Anything here is a considered product
 * decision and carries the reasoning with it.
 */
object Defaults {

    /**
     * The hour at which the day's counters reset.
     *
     * Not midnight: a session running at 23:50 would otherwise be split across
     * two days, and someone up late would get a fresh allowance at midnight,
     * which is exactly the wrong moment to hand out more screen time.
     */
    const val DAY_BOUNDARY_HOUR = 4

    /** Starting device allowance for a new setup. */
    val DAILY_LIMIT: Duration = 120.minutes

    /** The most a slider can grant, and the ceiling on any stored limit. */
    val MAX_DAILY_LIMIT: Duration = (24 * 60).minutes

    /** Starting per-category allowance when a category is created. */
    val CATEGORY_LIMIT: Duration = 60.minutes

    val NOTICE_THRESHOLD: Duration = 10.minutes
    val GRACE_THRESHOLD: Duration = 5.minutes

    /** Default bedtime window, applied when a family first switches it on. */
    val BEDTIME_START: LocalTime = LocalTime.of(22, 30)
    val BEDTIME_END: LocalTime = LocalTime.of(7, 0)

    /**
     * How far ahead schedule resolution looks for the next start or end.
     * Fifteen days guarantees a full cycle is covered even for a fortnightly
     * block (#83) — its own skipped week plus the week its next occurrence
     * starts in, with a day of slack either side.
     */
    const val SCHEDULE_HORIZON_DAYS = 15L

    /**
     * How long daily totals are kept: thirteen months, so a full year plus the
     * current month is always comparable.
     */
    const val HISTORY_RETENTION_DAYS = 400

    /**
     * How often the child device says it is still working (TR-2).
     *
     * Matched to the enforcement tick's ceiling rather than chosen independently:
     * the heartbeat rides along with a poll that was going to happen anyway, so a
     * shorter interval would buy nothing and cost radio wake-ups.
     */
    const val HEARTBEAT_INTERVAL_MINUTES = 15L

    /**
     * How quiet is too quiet.
     *
     * Two hours, not thirty minutes. A phone in a school bag with no signal is the
     * normal case, and an alert that fires every school day is one a parent turns
     * off — at which point the feature protects nothing.
     */
    const val HEARTBEAT_SILENCE_MINUTES = 120L

    /** Battery level at which a parent is told the phone may be about to go quiet. */
    const val LOW_BATTERY_PERCENT = 15

    /**
     * How long installs stay unlocked when a parent opens the gate (AC-3).
     *
     * Long enough to find an app and install it, short enough that forgetting to
     * close it again is not the same as turning the gate off.
     */
    const val INSTALL_WINDOW_MINUTES = 30L

    /**
     * How much history to reconstruct on first run. Android keeps roughly a week
     * of raw events; asking for more yields empty days that look like real zeros.
     */
    const val HISTORY_BACKFILL_DAYS = 7L
}
