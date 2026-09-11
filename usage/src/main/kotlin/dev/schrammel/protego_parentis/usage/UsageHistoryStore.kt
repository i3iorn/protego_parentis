package dev.schrammel.protego_parentis.usage

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.UsageSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

/**
 * Daily usage totals, kept beyond the few days Android retains.
 *
 * A plain JSON file rather than a database, because the access pattern is tiny
 * and does not justify one: at most one write per day, and reads of a few hundred
 * rows. Room becomes worthwhile when the raw session log and the offline upload
 * queue arrive, both of which need real queries and transactions.
 *
 * Only *closed* days are stored. Today is always recomputed live from
 * [UsageStatsManager][android.app.usage.UsageStatsManager], so it stays correct to
 * the minute and never has to be rewritten.
 */
class UsageHistoryStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun record(snapshot: UsageSnapshot) {
        val existing = all().filterNot { it.policyDay == snapshot.policyDay }
        val updated = (existing + snapshot)
            .sortedBy { it.policyDay }
            .takeLast(Defaults.HISTORY_RETENTION_DAYS)
        write(updated)
    }

    fun all(): List<UsageSnapshot> {
        if (!file.exists()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val days = root.optJSONArray(KEY_DAYS) ?: return emptyList()

        return (0 until days.length())
            .mapNotNull { days.optJSONObject(it)?.let(::fromJson) }
            .sortedBy { it.policyDay }
    }

    fun lastRecordedDay(): LocalDate? = all().lastOrNull()?.policyDay

    /** Everything recorded within the inclusive range. */
    fun range(from: LocalDate, to: LocalDate): List<UsageSnapshot> =
        all().filter { it.policyDay >= from && it.policyDay <= to }

    private fun write(days: List<UsageSnapshot>) {
        val root = JSONObject().put(KEY_DAYS, JSONArray(days.map(::toJson)))
        runCatching {
            // Write to a temporary file and swap: a process kill mid-write would
            // otherwise leave truncated JSON and lose the entire history.
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "Could not write usage history", it) }
    }

    private fun toJson(snapshot: UsageSnapshot): JSONObject = JSONObject().apply {
        put(KEY_DATE, snapshot.policyDay.toString())
        put(KEY_TOTAL, snapshot.deviceTotal.inWholeMilliseconds)
        put(
            KEY_APPS,
            JSONObject().apply {
                snapshot.byPackage.forEach { (pkg, time) ->
                    put(pkg.value, time.inWholeMilliseconds)
                }
            },
        )
    }

    private fun fromJson(json: JSONObject): UsageSnapshot? {
        val date = runCatching { LocalDate.parse(json.optString(KEY_DATE)) }.getOrNull()
            ?: return null
        val apps = json.optJSONObject(KEY_APPS)
        val byPackage = buildMap {
            apps?.keys()?.forEach { key ->
                val millis = apps.optLong(key, 0L)
                if (millis > 0L) put(PackageName(key), millis.milliseconds)
            }
        }
        return UsageSnapshot(
            policyDay = date,
            deviceTotal = json.optLong(KEY_TOTAL, 0L).milliseconds,
            byPackage = byPackage,
        )
    }

    /** Used by the release flow; history is the child's data and goes with them. */
    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val TAG = "DementorHistory"
        const val FILE_NAME = "usage-history.json"

        const val KEY_DAYS = "days"
        const val KEY_DATE = "date"
        const val KEY_TOTAL = "totalMillis"
        const val KEY_APPS = "apps"
    }
}
