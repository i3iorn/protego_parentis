package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.RecurringWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Turns recurring [RecurringWindow] definitions into concrete windows.
 *
 * Windows that cross midnight are the reason this is not a one-liner: a bedtime
 * of 22:30–07:00 on Monday is active at 02:00 on Tuesday, so "is it active now"
 * has to consider yesterday's window as well as today's.
 *
 * Works on [RecurringWindow] rather than the domain `ScheduleBlock` since
 * #101, so a schedule and a
 * [dev.schrammel.protego_parentis.model.Condition.DuringWindow] rule resolve
 * through exactly this arithmetic. #105 migrated every stored schedule into
 * that same rule shape and switched enforcement to read only rules, which is
 * why nothing here takes a `ScheduleBlock` any more either.
 *
 * Internal: consumed only by [ContextRules] and [PolicyEngine], both in this
 * module — a caller outside it never needs window arithmetic directly, only
 * the [dev.schrammel.protego_parentis.model.Enforcement] it feeds into.
 */
internal object ScheduleResolver {

    /** The concrete start/end pair for a window starting on [date], or null if it does not run that day. */
    private fun windowStartingOn(window: RecurringWindow, date: LocalDate, now: ZonedDateTime): Window? {
        if (!window.startsOn(date)) return null
        val start = ZonedDateTime.of(date, window.start, now.zone)
        val endDate = if (window.crossesMidnight) date.plusDays(1) else date
        val end = ZonedDateTime.of(endDate, window.end, now.zone)
        return Window(start, end)
    }

    /**
     * Every window of [window] that could contain or follow [now], covering yesterday
     * (for midnight-crossers), today, and tomorrow.
     */
    private fun windowsAround(window: RecurringWindow, now: ZonedDateTime): List<Window> =
        (-1L..1L).mapNotNull { offset ->
            windowStartingOn(window, now.toLocalDate().plusDays(offset), now)
        }

    fun isActive(window: RecurringWindow, now: ZonedDateTime): Boolean =
        windowsAround(window, now).any { it.contains(now) }

    /** When the currently-running window ends, or null if it is not running. */
    fun endOfActiveWindow(window: RecurringWindow, now: ZonedDateTime): Instant? =
        windowsAround(window, now).firstOrNull { it.contains(now) }?.end?.toInstant()

    /**
     * The next moment any of [windows] starts or stops, which is when
     * enforcement would change on its own — an exact alarm instead of polling.
     *
     * A [dev.schrammel.protego_parentis.model.Condition.DuringWindow] rule
     * needs this at its own edges — without it the block would sit there
     * until something unrelated woke enforcement, which on a quiet phone can
     * be hours after the window ended.
     */
    fun nextWindowEdgeAfter(windows: List<RecurringWindow>, now: ZonedDateTime): Instant? {
        val today = now.toLocalDate()
        return windows.asSequence()
            .flatMap { window ->
                (-1L..Defaults.SCHEDULE_HORIZON_DAYS).asSequence().mapNotNull { offset ->
                    windowStartingOn(window, today.plusDays(offset), now)
                }
            }
            .flatMap { sequenceOf(it.start, it.end) }
            .filter { it.isAfter(now) }
            .minOrNull()
            ?.toInstant()
    }

    private data class Window(val start: ZonedDateTime, val end: ZonedDateTime) {
        /** Half-open: a window ending at 07:00 is over at 07:00, not still running. */
        fun contains(at: ZonedDateTime): Boolean = !at.isBefore(start) && at.isBefore(end)
    }
}
