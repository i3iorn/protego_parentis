package dev.schrammel.dementor_watch.policy.usage

import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.UsageSnapshot
import java.time.LocalDate
import kotlin.time.Duration

/** One app's share of a period. */
data class AppTotal(
    val pkg: PackageName,
    val total: Duration,
)

/** What a stretch of days adds up to. */
data class PeriodSummary(
    /** Oldest first, with missing days filled in as zero. */
    val days: List<UsageSnapshot>,
    val total: Duration,
    val dailyAverage: Duration,
    val busiestDay: UsageSnapshot?,
    val topApps: List<AppTotal>,
) {
    val isEmpty: Boolean get() = total == Duration.ZERO
}

/**
 * Rolls daily snapshots up into the numbers a report shows.
 *
 * Pure, so the weekly view can be tested without a device or a database. The
 * fiddly part is [fill]: a day with no recorded usage is a real zero and has to
 * appear in the chart, because silently dropping it makes a week off look
 * identical to a week that was never measured.
 */
object UsageAggregates {

    fun summarise(days: List<UsageSnapshot>, topAppLimit: Int = 5): PeriodSummary {
        val ordered = days.sortedBy { it.policyDay }
        val total = ordered.fold(Duration.ZERO) { acc, day -> acc + day.deviceTotal }

        val perApp = HashMap<PackageName, Duration>()
        ordered.forEach { day ->
            day.byPackage.forEach { (pkg, time) ->
                perApp[pkg] = (perApp[pkg] ?: Duration.ZERO) + time
            }
        }

        return PeriodSummary(
            days = ordered,
            total = total,
            dailyAverage = if (ordered.isEmpty()) Duration.ZERO else total / ordered.size,
            busiestDay = ordered.maxByOrNull { it.deviceTotal }?.takeIf { it.deviceTotal > Duration.ZERO },
            topApps = perApp.entries
                .map { AppTotal(it.key, it.value) }
                .sortedByDescending { it.total }
                .take(topAppLimit),
        )
    }

    /**
     * Returns exactly one snapshot per day in [from]..[to], inclusive, oldest
     * first. Days with nothing recorded come back as zero rather than absent.
     */
    fun fill(days: List<UsageSnapshot>, from: LocalDate, to: LocalDate): List<UsageSnapshot> {
        require(!from.isAfter(to)) { "from ($from) must not be after to ($to)" }
        val byDay = days.associateBy { it.policyDay }
        return generateSequence(from) { previous ->
            previous.plusDays(1).takeIf { !it.isAfter(to) }
        }.map { day -> byDay[day] ?: UsageSnapshot.empty(day) }.toList()
    }

    /**
     * How much more (positive) or less (negative) time [current] holds than
     * [previous]. Used for the week-over-week line in the digest.
     */
    fun delta(current: List<UsageSnapshot>, previous: List<UsageSnapshot>): Duration =
        current.fold(Duration.ZERO) { acc, day -> acc + day.deviceTotal } -
            previous.fold(Duration.ZERO) { acc, day -> acc + day.deviceTotal }
}
