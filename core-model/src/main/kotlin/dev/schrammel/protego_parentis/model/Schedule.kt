package dev.schrammel.protego_parentis.model

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A recurring window during which only [allowedPackages] may run.
 *
 * [days] is the set of weekdays on which the window *starts*. A window whose
 * [end] is not after its [start] runs past midnight into the following day —
 * which is the normal case for bedtime, so it is modelled directly rather than
 * requiring the parent to author two blocks.
 */
data class ScheduleBlock(
    val id: ScheduleId,
    val name: String,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val allowedPackages: Set<PackageName> = emptySet(),
    val kind: Kind = Kind.CUSTOM,
) {
    init {
        require(name.isNotBlank()) { "schedule must have a name to show on the block screen" }
        require(days.isNotEmpty()) { "schedule '$name' has no active days" }
        require(start != end) { "schedule '$name' would cover the entire day; use 00:00-23:59 if that is intended" }
    }

    /** True when this window runs past midnight into the next calendar day. */
    val crossesMidnight: Boolean get() = !end.isAfter(start)

    enum class Kind { BEDTIME, SCHOOL, CUSTOM }
}
