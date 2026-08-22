package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.AppRule
import dev.schrammel.dementor_watch.model.Category
import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.ChildId
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Policy
import dev.schrammel.dementor_watch.model.ScheduleBlock
import dev.schrammel.dementor_watch.model.ScheduleId
import dev.schrammel.dementor_watch.model.WeeklyBudget
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * The device's local copy of the rules.
 *
 * Stand-in for what will eventually be Room plus server sync. It is deliberately
 * the same shape as the real thing in one respect that matters: the engine reads
 * its rules from local storage and never from the network, so enforcement keeps
 * working with the phone in airplane mode.
 *
 * TODO(M1): replace with Room + Firestore sync, keeping local as the read path.
 */
class LocalPolicyStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): Policy {
        val schedules = buildList {
            if (bedtimeEnabled) {
                add(
                    ScheduleBlock(
                        id = ScheduleId("bedtime"),
                        name = "Bedtime",
                        days = DayOfWeek.entries.toSet(),
                        start = LocalTime.of(bedtimeStartHour, bedtimeStartMinute),
                        end = LocalTime.of(bedtimeEndHour, bedtimeEndMinute),
                        // Everything on the safety list survives bedtime anyway;
                        // this set is for apps a family additionally permits.
                        allowedPackages = emptySet(),
                        kind = ScheduleBlock.Kind.BEDTIME,
                    ),
                )
            }
        }

        // Per-app limits first, then exemptions on top: an exempt app ignores every
        // limit, so being on both lists must resolve to exempt rather than to a cap.
        val appRules = buildMap<PackageName, AppRule> {
            appLimits.forEach { (pkg, minutes) ->
                if (minutes > 0) {
                    put(PackageName(pkg), AppRule.Budgeted(WeeklyBudget.flat(minutes.minutes)))
                }
            }
            exemptPackages.forEach { pkg -> put(PackageName(pkg), AppRule.AlwaysAllowed) }
        }

        return Policy(
            version = prefs.getLong(KEY_VERSION, 1L),
            childId = ChildId("local"),
            timeZone = ZoneId.systemDefault(),
            deviceBudget = if (dailyLimitMinutes <= 0) {
                WeeklyBudget.UNLIMITED
            } else {
                WeeklyBudget.flat(dailyLimitMinutes.minutes)
            },
            appRules = appRules,
            categories = categories.map { stored ->
                Category(
                    id = CategoryId(stored.id),
                    name = stored.name,
                    budget = if (stored.limitMinutes <= 0) {
                        WeeklyBudget.UNLIMITED
                    } else {
                        WeeklyBudget.flat(stored.limitMinutes.minutes)
                    },
                    packages = stored.packages.map(::PackageName).toSet(),
                )
            },
            schedules = schedules,
            safetyAllowlist = SafetyAllowlist.of(appContext),
            dayBoundaryHour = DAY_BOUNDARY_HOUR,
            tamperProtection = tamperProtection,
        )
    }

    /**
     * Locks the device against tampering. Defaults to **off**: turning it on makes
     * the device very hard to recover, so it must be a deliberate choice rather
     * than something that happens the moment a device is provisioned.
     */
    var tamperProtection: Boolean
        get() = prefs.getBoolean(KEY_TAMPER, false)
        set(value) = write { putBoolean(KEY_TAMPER, value) }

    /** Per-app daily limits in minutes, keyed by package name. */
    var appLimits: Map<String, Int>
        get() {
            val raw = prefs.getString(KEY_APP_LIMITS, null) ?: return emptyMap()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
            return json.keys().asSequence().associateWith { json.optInt(it, 0) }
                .filterValues { it > 0 }
        }
        set(value) = write {
            putString(KEY_APP_LIMITS, JSONObject(value.filterValues { it > 0 }).toString())
        }

    fun setAppLimit(packageName: String, minutes: Int) {
        appLimits = appLimits.toMutableMap().apply {
            if (minutes > 0) put(packageName, minutes) else remove(packageName)
        }
    }

    /** Apps that ignore every limit. */
    var exemptPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXEMPT, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_EXEMPT, value) }

    var categories: List<StoredCategory>
        get() = StoredCategory.listFromJson(prefs.getString(KEY_CATEGORIES, null))
        set(value) = write { putString(KEY_CATEGORIES, StoredCategory.listToJson(value)) }

    fun upsertCategory(category: StoredCategory) {
        val existing = categories.filterNot { it.id == category.id }
        categories = existing + category
    }

    fun deleteCategory(id: String) {
        categories = categories.filterNot { it.id == id }
    }

    /** Minutes per day, or 0 for no limit. */
    var dailyLimitMinutes: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT_MINUTES)
        set(value) = write { putInt(KEY_DAILY_LIMIT, value.coerceIn(0, MAX_DAILY_LIMIT_MINUTES)) }

    var bedtimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEDTIME_ENABLED, false)
        set(value) = write { putBoolean(KEY_BEDTIME_ENABLED, value) }

    var bedtimeStartHour: Int
        get() = prefs.getInt(KEY_BEDTIME_START_HOUR, 22)
        set(value) = write { putInt(KEY_BEDTIME_START_HOUR, value.coerceIn(0, 23)) }

    var bedtimeStartMinute: Int
        get() = prefs.getInt(KEY_BEDTIME_START_MINUTE, 30)
        set(value) = write { putInt(KEY_BEDTIME_START_MINUTE, value.coerceIn(0, 59)) }

    var bedtimeEndHour: Int
        get() = prefs.getInt(KEY_BEDTIME_END_HOUR, 7)
        set(value) = write { putInt(KEY_BEDTIME_END_HOUR, value.coerceIn(0, 23)) }

    var bedtimeEndMinute: Int
        get() = prefs.getInt(KEY_BEDTIME_END_MINUTE, 0)
        set(value) = write { putInt(KEY_BEDTIME_END_MINUTE, value.coerceIn(0, 59)) }

    /**
     * Bumping the version on every edit is what lets the service notice a change
     * and re-evaluate, and it is what the audit log records.
     */
    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit {
            block()
            putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, 1L) + 1L)
        }
    }

    private companion object {
        const val FILE_NAME = "local_policy"
        const val KEY_VERSION = "version"
        const val KEY_DAILY_LIMIT = "daily_limit_minutes"
        const val KEY_BEDTIME_ENABLED = "bedtime_enabled"
        const val KEY_BEDTIME_START_HOUR = "bedtime_start_hour"
        const val KEY_BEDTIME_START_MINUTE = "bedtime_start_minute"
        const val KEY_BEDTIME_END_HOUR = "bedtime_end_hour"
        const val KEY_BEDTIME_END_MINUTE = "bedtime_end_minute"
        const val KEY_EXEMPT = "exempt_packages"
        const val KEY_CATEGORIES = "categories"
        const val KEY_APP_LIMITS = "app_limits"
        const val KEY_TAMPER = "tamper_protection"

        const val DEFAULT_DAILY_LIMIT_MINUTES = 120
        const val MAX_DAILY_LIMIT_MINUTES = 24 * 60
        const val DAY_BOUNDARY_HOUR = 4
    }
}
