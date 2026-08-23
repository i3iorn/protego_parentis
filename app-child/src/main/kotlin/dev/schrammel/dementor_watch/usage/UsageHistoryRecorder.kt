package dev.schrammel.dementor_watch.usage

import android.util.Log
import dev.schrammel.dementor_watch.model.Defaults
import dev.schrammel.dementor_watch.model.Policy
import dev.schrammel.dementor_watch.policy.PolicyClock
import java.time.Instant
import java.time.LocalDate

/**
 * Writes a day into history once it has closed.
 *
 * Runs on every evaluation but almost always does nothing: it only acts when the
 * policy day has rolled over since the last write. That keeps disk writes to
 * roughly one per day instead of one per tick.
 *
 * On a fresh install it backfills what Android still remembers, so the weekly view
 * is useful immediately rather than after a week of waiting.
 */
class UsageHistoryRecorder(
    private val collector: UsageCollector,
    private val store: UsageHistoryStore,
) {

    /** Returns the days newly written, if any. */
    fun recordClosedDays(policy: Policy, now: Instant): List<LocalDate> {
        val today = PolicyClock.policyDay(now, policy.timeZone, policy.dayBoundaryHour)
        val lastRecorded = store.lastRecordedDay()

        val firstToRecord = when (lastRecorded) {
            null -> today.minusDays(Defaults.HISTORY_BACKFILL_DAYS)
            else -> lastRecorded.plusDays(1)
        }

        // Today is never written: it is still changing, and is always recomputed
        // live so it stays correct to the minute.
        if (!firstToRecord.isBefore(today)) return emptyList()

        val written = mutableListOf<LocalDate>()
        var day = firstToRecord
        var guard = 0

        while (day.isBefore(today) && guard < MAX_DAYS_PER_PASS) {
            val snapshot = runCatching { collector.collectDay(policy, day) }.getOrNull()
            if (snapshot != null) {
                store.record(snapshot)
                written += day
            } else {
                Log.w(TAG, "Could not read usage for $day; leaving a gap")
            }
            day = day.plusDays(1)
            guard++
        }

        if (written.isNotEmpty()) Log.i(TAG, "Recorded ${written.size} day(s) of history")
        return written
    }

    private companion object {
        const val TAG = "DementorHistory"

        /** Stops a device whose clock jumped years forward from looping forever. */
        const val MAX_DAYS_PER_PASS = 40
    }
}
