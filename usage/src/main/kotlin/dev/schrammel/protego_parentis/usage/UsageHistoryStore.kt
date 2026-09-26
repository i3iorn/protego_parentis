package dev.schrammel.protego_parentis.usage

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.security.SecretVault
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
class UsageHistoryStore(
    context: Context,
    /**
     * Seals the file at rest (#276). Which apps a child used and for how long,
     * day by day, is exactly as readable by an `adb pull` on a rooted phone as
     * the family passphrase SecretVault already protects. Null means sealing is
     * unavailable (no usable keystore): the history is then written as before,
     * because losing it would be worse than the sandbox-only protection it had.
     */
    private val seal: (String) -> String? = SecretVault::seal,
    private val open: (String) -> String? = SecretVault::open,
) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * The parsed history, cached against the file's last-modified stamp.
     *
     * [record] runs on the enforcement tick (via UsageHistoryRecorder's
     * closed-day pass) up to every fifteen seconds, and each call used to read
     * and parse the whole file whether or not anything changed. The cache is
     * invalidated by [write] itself and by any other process of this app
     * rewriting the file, since the stamp is read from the file each time.
     */
    private val cacheLock = Any()
    private var cachedStamp: Long = Long.MIN_VALUE
    private var cachedValue: List<UsageSnapshot> = emptyList()

    fun record(snapshot: UsageSnapshot) {
        synchronized(cacheLock) {
            val existing = read()
            val updated = (existing.filterNot { it.policyDay == snapshot.policyDay } + snapshot)
                .sortedBy { it.policyDay }
                .takeLast(Defaults.HISTORY_RETENTION_DAYS)
            // Writing the same day's identical totals again is the common case
            // on a short tick — skip the file rewrite entirely when nothing moved.
            if (updated == existing) return
            write(updated)
        }
    }

    fun all(): List<UsageSnapshot> = synchronized(cacheLock) { read() }

    fun lastRecordedDay(): LocalDate? = all().lastOrNull()?.policyDay

    /** Everything recorded within the inclusive range. */
    fun range(from: LocalDate, to: LocalDate): List<UsageSnapshot> =
        all().filter { it.policyDay >= from && it.policyDay <= to }

    private fun read(): List<UsageSnapshot> {
        if (!file.exists()) return emptyList()
        val stamp = file.lastModified()
        if (cachedStamp == stamp) return cachedValue
        val stored = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        // Unsealed files from before #276 are still read; the next write seals them.
        val raw = if (stored.startsWith(SEALED_PREFIX)) {
            open(stored.removePrefix(SEALED_PREFIX)) ?: return emptyList()
        } else {
            stored
        }
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val days = root.optJSONArray(KEY_DAYS) ?: return emptyList()

        val parsed = (0 until days.length())
            .mapNotNull { days.optJSONObject(it)?.let(::fromJson) }
            .sortedBy { it.policyDay }
        cachedStamp = stamp
        cachedValue = parsed
        return parsed
    }

    private fun write(days: List<UsageSnapshot>) {
        val json = JSONObject().put(KEY_DAYS, JSONArray(days.map(::toJson))).toString()
        val content = seal(json)?.let { SEALED_PREFIX + it }
            ?: json.also { Log.w(TAG, "Keystore unavailable; usage history written unsealed") }
        runCatching {
            // Write to a temporary file and swap: a process kill mid-write would
            // otherwise leave truncated JSON and lose the entire history.
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) {
                file.writeText(content)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "Could not write usage history", it) }
        // Drop the cache rather than pre-warming it: the write path does not go
        // through fromJson, which is where the read path drops rows (a package
        // with zero recorded time, a malformed row). The next read re-parses
        // exactly what is on disk, so cache and file can never disagree.
        synchronized(cacheLock) {
            cachedStamp = Long.MIN_VALUE
            cachedValue = emptyList()
        }
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
        synchronized(cacheLock) {
            cachedStamp = Long.MIN_VALUE
            cachedValue = emptyList()
        }
    }

    private companion object {
        const val TAG = "DementorHistory"
        const val FILE_NAME = "usage-history.json"
        const val SEALED_PREFIX = "sealed:"

        const val KEY_DAYS = "days"
        const val KEY_DATE = "date"
        const val KEY_TOTAL = "totalMillis"
        const val KEY_APPS = "apps"
    }
}
