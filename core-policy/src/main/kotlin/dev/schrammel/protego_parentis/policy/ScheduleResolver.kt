package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.ScheduleBlock
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
 * Works on [RecurringWindow] rather than [ScheduleBlock] since #101, so a
 * schedule and a [dev.schrammel.protego_parentis.model.Condition.DuringWindow]
 * rule resolve through exactly this arithmetic. The [ScheduleBlock] overloads
 * remain for the callers that still hold one, and simply read its
 * [ScheduleBlock.window].
 */
object ScheduleResolver {

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

    fun isActive(block: ScheduleBlock, now: ZonedDateTime): Boolean = isActive(block.window, now)

    /** When the currently-running window ends, or null if it is not running. */
    fun endOfActiveWindow(window: RecurringWindow, now: ZonedDateTime): Instant? =
        windowsAround(window, now).firstOrNull { it.contains(now) }?.end?.toInstant()

    fun endOfActiveWindow(block: ScheduleBlock, now: ZonedDateTime): Instant? =
        endOfActiveWindow(block.window, now)

    /**
     * The next moment any of [blocks] starts or stops, which is when enforcement
     * would change on its own. Used to set an exact alarm instead of polling.
     *
     * Deliberately still takes [ScheduleBlock] rather than gaining a
     * [RecurringWindow] overload: the two would erase to the same JVM
     * signature, making an `emptyList()` argument ambiguous at every call
     * site. Waking for a
     * [dev.schrammel.protego_parentis.model.Condition.DuringWindow] rule's own
     * edges is #103's business, once a rule-driven block reports when it lifts.
     */
    fun nextEdgeAfter(blocks: List<ScheduleBlock>, now: ZonedDateTime): Instant? {
        val today = now.toLocalDate()
        return blocks.asSequence()
            .flatMap { block ->
                (-1L..Defaults.SCHEDULE_HORIZON_DAYS).asSequence().mapNotNull { offset ->
                    windowStartingOn(block.window, today.plusDays(offset), now)
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
