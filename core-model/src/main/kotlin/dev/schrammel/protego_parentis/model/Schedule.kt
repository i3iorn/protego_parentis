package dev.schrammel.protego_parentis.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * A recurring window during which some apps are paused.
 *
 * Which apps is decided by [mode] reading [packages], and the two readings are
 * opposites — see [Mode]. One set and one flag rather than two lists, so there
 * is a single place that answers "does this window apply to this app".
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
    /**
     * The apps [mode] is about. Exceptions in [Mode.EXCLUSIVE], targets in
     * [Mode.INCLUSIVE].
     *
     * Not named for either reading, because it is genuinely both. A name like
     * `allowedPackages` was accurate while exclusive was the only mode and would
     * now be a lie half the time — which is how the meaning of a stored set gets
     * quietly inverted.
     */
    val packages: Set<PackageName> = emptySet(),
    val mode: Mode = Mode.EXCLUSIVE,
    val kind: Kind = Kind.CUSTOM,
    /**
     * Null means every week — what every schedule meant before this field
     * existed, so nothing needs migrating. Set, it names the first date this
     * window is active; every second 7-day step from there is active too, and
     * the weeks in between are skipped (#83 — "every other week on Fridays").
     * Counted in exact 7-day steps from this date, not calendar weeks, so a
     * schedule needs no notion of which day a week is deemed to start on.
     */
    val parityAnchor: LocalDate? = null,
) {
    init {
        require(name.isNotBlank()) { "schedule must have a name to show on the block screen" }
        require(days.isNotEmpty()) { "schedule '$name' has no active days" }
        require(start != end) { "schedule '$name' would cover the entire day; use 00:00-23:59 if that is intended" }
    }

    /** True when this window runs past midnight into the next calendar day. */
    val crossesMidnight: Boolean get() = !end.isAfter(start)

    /**
     * Whether a window starting on [date] falls in an active week under
     * [parityAnchor] — always true when there is no anchor. Parity is exact
     * 7-day steps from the anchor, so it holds however far before or after it
     * [date] falls, and whichever day of the week the anchor itself is on.
     */
    fun appliesToWeekOf(date: LocalDate): Boolean {
        val anchor = parityAnchor ?: return true
        val weeksBetween = Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), 7L)
        return weeksBetween % 2 == 0L
    }

    /**
     * Whether this window pauses [pkg], while it is running.
     *
     * The one place the two modes are told apart. Everything else — the engine,
     * the editor's summary line — asks this rather than reading [mode] itself, so
     * there is no second implementation to disagree with this one.
     */
    fun appliesTo(pkg: PackageName): Boolean = when (mode) {
        Mode.EXCLUSIVE -> pkg !in packages
        Mode.INCLUSIVE -> pkg in packages
    }

    /**
     * How [packages] is read.
     *
     * [EXCLUSIVE] is the original and remains the default, because every schedule
     * ever saved by this app was written under it. Reading an existing schedule as
     * [INCLUSIVE] would invert its meaning entirely: a bedtime that paused
     * everything but the alarm clock would begin pausing the alarm clock and
     * nothing else.
     */
    enum class Mode {
        /** Pause everything except [packages]. Bedtime: the phone winds down. */
        EXCLUSIVE,

        /** Pause only [packages]. Homework: the games go, the rest stays. */
        INCLUSIVE,
    }

    enum class Kind { BEDTIME, SCHOOL, CUSTOM }
}
