package dev.schrammel.dementor_watch.rules

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.model.AppRule
import dev.schrammel.dementor_watch.model.Category
import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.ChildId
import dev.schrammel.dementor_watch.model.Defaults
import dev.schrammel.dementor_watch.model.InstallPolicy
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.model.Policy
import dev.schrammel.dementor_watch.model.ScheduleBlock
import dev.schrammel.dementor_watch.model.ScheduleId
import dev.schrammel.dementor_watch.model.WeeklyBudget
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * When the phone becomes uninteresting.
 *
 * The three fields travel together deliberately: a start with no end, or an end
 * saved without its start, is not a state the UI should be able to produce.
 */
data class BedtimeSetting(
    val enabled: Boolean,
    val start: LocalTime,
    val end: LocalTime,
    /**
     * The nights it runs, by the day the window *starts*.
     *
     * A school-night bedtime is the common case and was previously impossible to
     * express: the window ran all seven days or not at all, which meant a family
     * either had no bedtime or had a Friday one.
     */
    val days: Set<DayOfWeek> = ALL_DAYS,
) {
    /** A bedtime with no nights is off, whatever the switch says. */
    val isActive: Boolean get() = enabled && days.isNotEmpty()

    companion object {
        val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()

        val OFF = BedtimeSetting(
            enabled = false,
            start = Defaults.BEDTIME_START,
            end = Defaults.BEDTIME_END,
        )
    }
}

/**
 * The device's local copy of the rules.
 *
 * Stand-in for what will eventually be Room plus server sync. It is deliberately
 * the same shape as the real thing in one respect that matters: the engine reads
 * its rules from local storage and never from the network, so enforcement keeps
 * working with the phone in airplane mode.
 *
 * Every setter bumps the policy version, which is the signal the enforcement
 * service uses to notice a change and re-evaluate.
 *
 * TODO(M1): replace with Room + Firestore sync, keeping local as the read path.
 */
class LocalPolicyStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val contextRuleStore = ContextRuleStore(appContext)
    private val scheduleStore = ScheduleStore(appContext)
    private val webFilterStore = WebFilterStore(appContext)

    // ---------- the assembled policy ----------

    /** Builds the domain [Policy] the engine evaluates. */
    fun load(): Policy = Policy(
        version = prefs.getLong(KEY_VERSION, INITIAL_VERSION),
        childId = ChildId(LOCAL_CHILD_ID),
        timeZone = ZoneId.systemDefault(),
        deviceBudget = budgetOf(dailyLimitMinutes),
        appRules = appRules(),
        categories = categories.map(::toDomain),
        schedules = schedules(),
        contextRules = contextRuleStore.rules,
        safetyAllowlist = SafetyAllowlist.of(appContext),
        dnsFilter = webFilterStore.filter(),
        browserPolicy = webFilterStore.browserPolicy,
        installPolicy = installPolicy,
        installWindowUntil = installWindowUntil,
        tamperProtection = tamperProtection,
        dayBoundaryHour = Defaults.DAY_BOUNDARY_HOUR,
    )

    /**
     * Per-app limits first, then exemptions on top: an exempt app ignores every
     * limit, so being on both lists must resolve to exempt rather than to a cap.
     */
    private fun appRules(): Map<PackageName, AppRule> = buildMap {
        appLimits.forEach { (pkg, minutes) ->
            put(PackageName(pkg), AppRule.Budgeted(budgetOf(minutes)))
        }
        exemptPackages.forEach { pkg -> put(PackageName(pkg), AppRule.AlwaysAllowed) }

        // Last, so an explicit block wins over an exemption. "Ignores limits" and
        // "is not on this phone" can both be set from different screens, and the
        // more specific of the two has to be the one that survives.
        blockedPackages.forEach { pkg -> put(PackageName(pkg), AppRule.Blocked(hidden = false)) }
        hiddenPackages.forEach { pkg -> put(PackageName(pkg), AppRule.Blocked(hidden = true)) }
    }

    private fun schedules(): List<ScheduleBlock> = buildList {
        val setting = bedtime
        if (setting.isActive && setting.start != setting.end) {
            add(
                ScheduleBlock(
                    id = ScheduleId(BEDTIME_ID),
                    name = appContext.getString(R.string.rules_bedtime_name),
                    days = setting.days,
                    start = setting.start,
                    end = setting.end,
                    // Everything on the safety list survives bedtime anyway; this
                    // set is for apps a family additionally permits.
                    allowedPackages = emptySet(),
                    kind = ScheduleBlock.Kind.BEDTIME,
                ),
            )
        }
        addAll(scheduleStore.active())
    }

    private fun toDomain(stored: StoredCategory) = Category(
        id = CategoryId(stored.id),
        name = stored.name,
        budget = budgetOf(stored.limitMinutes),
        packages = stored.packages.map(::PackageName).toSet(),
        subcategories = stored.subcategories.map(::CategoryId).toSet(),
    )

    /** Zero means "no limit"; the sliders have no other way to express it. */
    private fun budgetOf(minutes: Int): WeeklyBudget =
        if (minutes <= 0) WeeklyBudget.UNLIMITED else WeeklyBudget.flat(minutes.minutes)

    // ---------- device limit ----------

    /** Minutes per day, or 0 for no limit. */
    var dailyLimitMinutes: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT, Defaults.DAILY_LIMIT.inWholeMinutes.toInt())
        set(value) = write { putInt(KEY_DAILY_LIMIT, value.clampToDay()) }

    // ---------- bedtime ----------

    var bedtime: BedtimeSetting
        get() = BedtimeSetting(
            enabled = prefs.getBoolean(KEY_BEDTIME_ENABLED, BedtimeSetting.OFF.enabled),
            start = prefs.readTime(KEY_BEDTIME_START, Defaults.BEDTIME_START),
            end = prefs.readTime(KEY_BEDTIME_END, Defaults.BEDTIME_END),
            // Absent for anyone upgrading from a build where bedtime ran nightly.
            // Defaulting to every night keeps their bedtime exactly as it was.
            days = prefs.getStringSet(KEY_BEDTIME_DAYS, null)
                ?.mapNotNull { stored -> stored.toIntOrNull()?.let(::dayOf) }
                ?.toSet()
                ?: BedtimeSetting.ALL_DAYS,
        )
        set(value) = write {
            putBoolean(KEY_BEDTIME_ENABLED, value.enabled)
            putInt(KEY_BEDTIME_START, value.start.toMinuteOfDay())
            putInt(KEY_BEDTIME_END, value.end.toMinuteOfDay())
            putStringSet(KEY_BEDTIME_DAYS, value.days.map { it.value.toString() }.toSet())
        }

    // ---------- categories ----------

    var categories: List<StoredCategory>
        get() = StoredCategory.listFromJson(prefs.getString(KEY_CATEGORIES, null))
        set(value) = write { putString(KEY_CATEGORIES, StoredCategory.listToJson(value)) }

    fun upsertCategory(category: StoredCategory) {
        categories = categories.filterNot { it.id == category.id } + category
    }

    fun deleteCategory(id: String) {
        categories = categories.filterNot { it.id == id }
    }

    // ---------- per-app rules ----------

    /** Per-app daily limits in minutes, keyed by package name. */
    var appLimits: Map<String, Int>
        get() {
            val raw = prefs.getString(KEY_APP_LIMITS, null) ?: return emptyMap()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
            return json.keys().asSequence()
                .associateWith { json.optInt(it, 0) }
                .filterValues { it > 0 }
        }
        set(value) = write {
            putString(KEY_APP_LIMITS, JSONObject(value.filterValues { it > 0 }).toString())
        }

    fun setAppLimit(packageName: String, minutes: Int) {
        appLimits = appLimits.toMutableMap().apply {
            if (minutes > 0) put(packageName, minutes.clampToDay()) else remove(packageName)
        }
    }

    /** Apps that ignore every limit. */
    var exemptPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXEMPT, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_EXEMPT, value) }

    // ---------- device control ----------

    /** Apps a parent has put off-limits. The icon greys out; the data survives. */
    var blockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_BLOCKED, value) }

    /** Harsher than blocked: the icon disappears entirely. */
    var hiddenPackages: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_HIDDEN, value) }

    fun setBlocked(packageName: String, blocked: Boolean) {
        blockedPackages = if (blocked) blockedPackages + packageName else blockedPackages - packageName
        if (blocked) hiddenPackages = hiddenPackages - packageName
    }

    /**
     * When a parent-opened installation window closes, or null if none is open.
     *
     * Stored rather than derived so the audit log and both screens can agree on
     * when it was opened and when it ran out.
     */
    var installWindowUntil: Instant?
        get() = prefs.getLong(KEY_INSTALL_WINDOW, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
        set(value) = write {
            if (value == null) remove(KEY_INSTALL_WINDOW) else putLong(KEY_INSTALL_WINDOW, value.toEpochMilli())
        }

    var installPolicy: InstallPolicy
        get() = runCatching {
            InstallPolicy.valueOf(prefs.getString(KEY_INSTALL_POLICY, null) ?: "")
        }.getOrDefault(InstallPolicy.NOTIFY)
        set(value) = write { putString(KEY_INSTALL_POLICY, value.name) }

    /**
     * Locks the device against tampering. Defaults to **off**: turning it on makes
     * the device very hard to recover, so it must be a deliberate choice rather
     * than something that happens the moment a device is provisioned.
     */
    var tamperProtection: Boolean
        get() = prefs.getBoolean(KEY_TAMPER, false)
        set(value) = write { putBoolean(KEY_TAMPER, value) }

    // ---------- plumbing ----------

    private fun Int.clampToDay(): Int =
        coerceIn(0, Defaults.MAX_DAILY_LIMIT.inWholeMinutes.toInt())

    private fun LocalTime.toMinuteOfDay(): Int = hour * MINUTES_PER_HOUR + minute

    private fun dayOf(value: Int): DayOfWeek? =
        DayOfWeek.entries.firstOrNull { it.value == value }

    private fun SharedPreferences.readTime(key: String, fallback: LocalTime): LocalTime {
        val stored = getInt(key, NOT_SET)
        if (stored !in 0 until MINUTES_PER_DAY) return fallback
        return LocalTime.of(stored / MINUTES_PER_HOUR, stored % MINUTES_PER_HOUR)
    }

    /**
     * Every write bumps the version. The enforcement service compares it to decide
     * whether anything needs re-applying, and the audit log records it.
     */
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit {
            block()
            putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, INITIAL_VERSION) + 1L)
        }
    }

    private companion object {
        const val FILE_NAME = "local_policy"
        const val INITIAL_VERSION = 1L
        const val LOCAL_CHILD_ID = "local"
        const val BEDTIME_ID = "bedtime"
        const val NOT_SET = -1

        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * 60

        const val KEY_VERSION = "version"
        const val KEY_DAILY_LIMIT = "daily_limit_minutes"
        const val KEY_BEDTIME_ENABLED = "bedtime_enabled"
        const val KEY_BEDTIME_START = "bedtime_start_minute_of_day"
        const val KEY_BEDTIME_END = "bedtime_end_minute_of_day"
        const val KEY_BEDTIME_DAYS = "bedtime_days"
        const val KEY_BLOCKED = "blocked_packages"
        const val KEY_HIDDEN = "hidden_packages"
        const val KEY_INSTALL_WINDOW = "install_window_until"
        const val KEY_EXEMPT = "exempt_packages"
        const val KEY_CATEGORIES = "categories"
        const val KEY_APP_LIMITS = "app_limits"
        const val KEY_INSTALL_POLICY = "install_policy"
        const val KEY_TAMPER = "tamper_protection"
    }
}
