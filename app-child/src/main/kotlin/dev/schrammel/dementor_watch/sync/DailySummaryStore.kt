package dev.schrammel.dementor_watch.sync

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
    /** Ordinal of `BudgetLevel`, so the parent can colour it without the model. */
    val level: Int,
    val topApps: List<AppTime>,
) {
    val hasLimit: Boolean get() = allowedMinutes > 0

    val remainingMinutes: Int
        get() = (allowedMinutes + bonusMinutes - usedMinutes).coerceAtLeast(0)
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

    var latest: DailySummary?
        get() {
            val at = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L } ?: return null
            return DailySummary(
                receivedAt = Instant.ofEpochMilli(at),
                usedMinutes = prefs.getInt(KEY_USED, 0),
                allowedMinutes = prefs.getInt(KEY_ALLOWED, 0),
                bonusMinutes = prefs.getInt(KEY_BONUS, 0),
                level = prefs.getInt(KEY_LEVEL, 0),
                topApps = decodeApps(prefs.getString(KEY_APPS, null)),
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit { clear() }
                return
            }
            // Out-of-order arrival is normal; the poll window overlaps and the
            // server promises nothing about ordering.
            val known = latest
            if (known != null && value.receivedAt.isBefore(known.receivedAt)) return

            prefs.edit {
                putLong(KEY_AT, value.receivedAt.toEpochMilli())
                putInt(KEY_USED, value.usedMinutes)
                putInt(KEY_ALLOWED, value.allowedMinutes)
                putInt(KEY_BONUS, value.bonusMinutes)
                putInt(KEY_LEVEL, value.level)
                putString(KEY_APPS, encodeApps(value.topApps))
            }
        }

    private fun encodeApps(apps: List<AppTime>): String = JSONArray(
        apps.map { app ->
            JSONObject().apply {
                put(KEY_LABEL, app.label)
                put(KEY_MINUTES, app.minutes)
            }
        },
    ).toString()

    private fun decodeApps(raw: String?): List<AppTime> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val label = json.optString(KEY_LABEL).takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            AppTime(label, json.optInt(KEY_MINUTES, 0))
        }
    }

    private companion object {
        const val FILE_NAME = "daily_summary"

        const val KEY_AT = "at"
        const val KEY_USED = "used"
        const val KEY_ALLOWED = "allowed"
        const val KEY_BONUS = "bonus"
        const val KEY_LEVEL = "level"
        const val KEY_APPS = "apps"
        const val KEY_LABEL = "label"
        const val KEY_MINUTES = "minutes"
    }
}
