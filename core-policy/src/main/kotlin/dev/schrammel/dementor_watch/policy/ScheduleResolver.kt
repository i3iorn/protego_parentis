package dev.schrammel.dementor_watch.policy

import dev.schrammel.dementor_watch.model.Defaults
import dev.schrammel.dementor_watch.model.ScheduleBlock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Turns recurring [ScheduleBlock] definitions into concrete windows.
 *
 * Windows that cross midnight are the reason this is not a one-liner: a bedtime
 * of 22:30–07:00 on Monday is active at 02:00 on Tuesday, so "is it active now"
 * has to consider yesterday's window as well as today's.
 */
object ScheduleResolver {

    /** The concrete start/end pair for a window starting on [date], or null if it does not run that day. */
    private fun windowStartingOn(block: ScheduleBlock, date: LocalDate, now: ZonedDateTime): Window? {
        if (date.dayOfWeek !in block.days) return null
        val start = ZonedDateTime.of(date, block.start, now.zone)
        val endDate = if (block.crossesMidnight) date.plusDays(1) else date
        val end = ZonedDateTime.of(endDate, block.end, now.zone)
        return Window(start, end)
    }

    /**
     * Every window of [block] that could contain or follow [now], covering yesterday
     * (for midnight-crossers), today, and tomorrow.
     */
    private fun windowsAround(block: ScheduleBlock, now: ZonedDateTime): List<Window> =
        (-1L..1L).mapNotNull { offset ->
            windowStartingOn(block, now.toLocalDate().plusDays(offset), now)
        }

    fun isActive(block: ScheduleBlock, now: ZonedDateTime): Boolean =
        windowsAround(block, now).any { it.contains(now) }

    /** When the currently-running window of [block] ends, or null if it is not running. */
    fun endOfActiveWindow(block: ScheduleBlock, now: ZonedDateTime): Instant? =
        windowsAround(block, now).firstOrNull { it.contains(now) }?.end?.toInstant()

    /**
     * The next moment any of [blocks] starts or stops, which is when enforcement
     * would change on its own. Used to set an exact alarm instead of polling.
     */
    fun nextEdgeAfter(blocks: List<ScheduleBlock>, now: ZonedDateTime): Instant? {
        val today = now.toLocalDate()
        return blocks.asSequence()
            .flatMap { block ->
                (-1L..Defaults.SCHEDULE_HORIZON_DAYS).asSequence().mapNotNull { offset ->
                    windowStartingOn(block, today.plusDays(offset), now)
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
