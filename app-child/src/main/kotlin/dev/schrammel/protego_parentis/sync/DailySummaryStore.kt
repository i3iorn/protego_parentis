package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One app and how long it was used, as the parent's phone hears it. */
data class AppTime(val label: String, val minutes: Int)

/**
 * Today on the child's phone, as a parent's phone sees it (RPT-1).
 *
 * This is the one message that carries what the child *did* rather than what the
 * app did, and it exists because a parent opening this app wants one glance that
 * answers "is anything wrong". Sending nothing would mean a parent's screen could
 * only ever show requests and alerts, which is a monitoring product with the
 * monitoring removed.
 *
 * It is deliberately a summary, not a log: today's totals, the handful of apps
 * that account for most of it, and where the budget stands. No timestamps, no
 * session history, nothing about *when* anything happened. The child sees the
 * identical figures on their own dashboard, which is the promise the whole
 * product rests on, and the privacy screen names this message explicitly.
 */
data class DailySummary(
    val receivedAt: Instant,
    val usedMinutes: Int,
    /** Zero means no daily limit is set. */
    val allowedMinutes: Int,
    val bonusMinutes: Int,
    /**
     * Sent by the child device as its own exact figure, not recomputed here from
     * the other three fields — each of those is independently truncated to whole
     * minutes, so `allowed + bonus - used` can be off by up to two minutes from
     * what the child's own dashboard shows.
     */
    val remainingMinutes: Int,
    /** Ordinal of `BudgetLevel`, so the parent can colour it without the model. */
    val level: Int,
    val topApps: List<AppTime>,
) {
    val hasLimit: Boolean get() = allowedMinutes > 0
}

/**
 * The most recent summary heard from the child device.
 *
 * Only the latest is kept. A parent phone accumulating a day-by-day history would
 * be building a second copy of the child's usage records somewhere the child
 * cannot see or delete them, which is exactly the asymmetry this product is
 * supposed to avoid.
 */
class DailySummaryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val telemetry = TelemetryReplication(context)

    /**
     * The last picture the managed phone reported.
     *
     * Read from replicated telemetry rather than a summary message. The figures
     * are the same; what changed is that they arrive as a handful of registers
     * that only move when the numbers do, instead of a whole summary on a timer.
     *
     * [DailySummary.receivedAt] is now this phone's own clock rather than the
     * sending phone's: replication carries no wall-clock time, and the honest
     * meaning of the timestamp on a dashboard is "as of when I last heard",
     * which is exactly what this is.
     */
    val latest: DailySummary?
        get() {
            val reported = telemetry.read()
            if (!reported.hasReported) return null
            return DailySummary(
                receivedAt = lastHeardAt() ?: Instant.now(),
                usedMinutes = reported.usedMinutes ?: 0,
                allowedMinutes = reported.allowedMinutes ?: 0,
                bonusMinutes = reported.bonusMinutes ?: 0,
                remainingMinutes = reported.remainingMinutes ?: 0,
                level = reported.level ?: 0,
                topApps = telemetry.topApps(),
            )
        }

    private fun lastHeardAt(): Instant? =
        prefs.getLong(KEY_AT, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)

    /** Notes that fresh telemetry landed, for the "as of" line on the dashboard. */
    fun markHeard(at: Instant = Instant.now()) {
        prefs.edit { putLong(KEY_AT, at.toEpochMilli()) }
    }

    /** Forgets what this phone was told, when it leaves the family. */
    fun forget() {
        prefs.edit { clear() }
    }

    private companion object {
        /** Shared across instances: every screen constructs its own store. */
        val LOCK = Any()

        const val FILE_NAME = "daily_summary"

        const val KEY_AT = "at"
        const val KEY_USED = "used"
        const val KEY_ALLOWED = "allowed"
        const val KEY_BONUS = "bonus"
        const val KEY_REMAINING = "remaining"
        const val KEY_LEVEL = "level"
        const val KEY_APPS = "apps"
        const val KEY_LABEL = "label"
        const val KEY_MINUTES = "minutes"
    }
}
