package dev.schrammel.protego_parentis.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.policy.PolicyClock
import dev.schrammel.protego_parentis.policy.usage.RawUsageEvent
import dev.schrammel.protego_parentis.policy.usage.UsageEventKind
import dev.schrammel.protego_parentis.policy.usage.UsageReplay
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration

/** Usage for the current policy day, plus whatever is on screen right now. */
data class CollectedUsage(
    val snapshot: UsageSnapshot,
    val foregroundPackage: PackageName?,
)

/**
 * Reads foreground time out of [UsageStatsManager].
 *
 * Works from the raw event stream rather than the pre-aggregated daily stats,
 * because those buckets are calendar days and our day boundary is 04:00. Replaying
 * the events is the only way to get the policy day right.
 *
 * This class only translates platform events into
 * [dev.schrammel.protego_parentis.policy.usage.RawUsageEvent]; the arithmetic lives
 * in [UsageReplay], where it is unit-tested.
 *
 * Nothing is persisted yet: the OS keeps several days of events, which covers the
 * current policy day on its own. Local storage becomes necessary for history and
 * for the offline upload queue, not for this.
 */
class UsageCollector(context: Context) {

    private val appContext = context.applicationContext
    private val usageStats = appContext.getSystemService(UsageStatsManager::class.java)

    /** Today so far, up to [now]. */
    fun collect(policy: Policy, now: Instant): CollectedUsage {
        val day = PolicyClock.policyDay(now, policy.timeZone, policy.dayBoundaryHour)
        val dayStart = PolicyClock.dayStart(day, policy.timeZone, policy.dayBoundaryHour)

        return snapshotFor(policy, day, dayStart.toEpochMilli(), now.toEpochMilli())
    }

    /**
     * A complete past day, for the history store.
     *
     * Only meaningful for days that have already closed, and only while Android
     * still holds their events — it keeps roughly a week, which is why backfill is
     * bounded rather than open-ended.
     */
    fun collectDay(policy: Policy, day: LocalDate): UsageSnapshot {
        val start = PolicyClock.dayStart(day, policy.timeZone, policy.dayBoundaryHour)
        val end = PolicyClock.dayEnd(day, policy.timeZone, policy.dayBoundaryHour)

        return snapshotFor(policy, day, start.toEpochMilli(), end.toEpochMilli()).snapshot
    }

    private fun snapshotFor(
        policy: Policy,
        day: LocalDate,
        fromMillis: Long,
        toMillis: Long,
    ): CollectedUsage {
        val replayed = UsageReplay.replay(
            events = readEvents(fromMillis, toMillis),
            untilMillis = toMillis,
        )

        // Safety-exempt apps do not count against the device budget — a long call
        // must not eat the day's screen time.
        val deviceTotal = replayed.totals
            .filterKeys { !policy.isSafetyExempt(it) }
            .values
            .fold(Duration.ZERO, Duration::plus)

        return CollectedUsage(
            snapshot = UsageSnapshot(day, deviceTotal, replayed.totals),
            foregroundPackage = replayed.openPackage,
        )
    }

    private fun readEvents(fromMillis: Long, toMillis: Long): List<RawUsageEvent> {
        val events = usageStats.queryEvents(fromMillis, toMillis)
        val event = UsageEvents.Event()
        val collected = mutableListOf<RawUsageEvent>()

        while (events.getNextEvent(event)) {
            val kind = kindOf(event.eventType) ?: continue
            collected += RawUsageEvent(
                kind = kind,
                packageName = event.packageName.orEmpty(),
                atMillis = event.timeStamp,
            )
        }
        return collected
    }

    private fun kindOf(eventType: Int): UsageEventKind? = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventKind.APP_RESUMED

        // ACTIVITY_STOPPED is deliberately not treated as a close. Moving between
        // two screens of one app emits pause(first), resume(second), stop(first),
        // so a stop would end the session immediately after it reopened and the
        // app would stop being counted while still on screen.
        UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventKind.APP_PAUSED

        // Android does not reliably pause the foreground app when the screen goes
        // off. Without these, a phone left face-down accrues hours.
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.KEYGUARD_SHOWN,
        -> UsageEventKind.SCREEN_OFF

        // The mirror image, and just as necessary: unlocking back into the app you
        // were already using does not always emit a fresh resume.
        UsageEvents.Event.SCREEN_INTERACTIVE,
        UsageEvents.Event.KEYGUARD_HIDDEN,
        -> UsageEventKind.SCREEN_ON

        UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventKind.SHUTDOWN

        else -> null
    }
}
