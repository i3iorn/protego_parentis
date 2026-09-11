package dev.schrammel.protego_parentis.rules

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.AppRule
import dev.schrammel.protego_parentis.model.BrowserPolicy
import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DailyGoal
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
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
    private val goalStore = GoalStore(appContext)
    private val featureFlags = FeatureFlags(appContext)

    // ---------- the assembled policy ----------

    /** Builds the domain [Policy] the engine evaluates. */
    fun load(): Policy = Policy(
        version = prefs.getLong(KEY_VERSION, INITIAL_VERSION),
        childId = ChildId(LOCAL_CHILD_ID),
        timeZone = ZoneId.systemDefault(),
        deviceBudget = budgetOf(dailyLimitMinutes),
        appRules = appRules(),
        categories = categories.map(::toDomain),
        // Bedtime is a StoredSchedule like any other now (see ScheduleStore's
        // own doc comment) — active() already includes it once enabled, with
        // no special-casing needed here any more.
        schedules = scheduleStore.active(),
        contextRules = contextRuleStore.rules,
        safetyAllowlist = SafetyAllowlist.of(appContext),
        dnsFilter = webFilterStore.filter(),
        browserPolicy = webFilterStore.browserPolicy,
        networkBlockedPackages = networkBlockedPackages.map(::PackageName).toSet(),
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

    // ---------- migration (#105) ----------

    /**
     * Folds every stored schedule, the flat daily limit, and every exempt
     * package into the unified [ContextRuleStore] rule format, once.
     *
     * Idempotent by construction rather than by locking: [ContextRuleStore.upsert]
     * replaces by id, so running this again writes the exact same rules back.
     * That is also what makes it safe to interrupt — a crash before
     * [KEY_RULES_MIGRATED] is written just means the whole conversion runs
     * again next time, redundantly but harmlessly, rather than half of it
     * being stuck undone. The flag itself is written directly rather than
     * through [write], since it is bookkeeping about this device having run
     * the conversion, not a rule change a parent's phone needs to hear about.
     *
     * A schedule with [StoredSchedule.isComplete] false is skipped, the same
     * way [ScheduleStore.active] already treats it as not a real schedule —
     * it was never enforced before this either.
     *
     * Deliberately leaves [scheduleStore]'s own schedules, this store's
     * [dailyLimitMinutes], and [exemptPackages] exactly as they are:
     * `SchedulesActivity` and `LimitsActivity`/`ParentLimitActivity` still
     * edit them directly until #106 replaces those screens with one that
     * writes rules instead, and a family mid-upgrade must not lose whichever
     * screen it opens first. This runs once, so an edit made through the old
     * screens after that point will not be reflected here again — accepted
     * as a short-lived gap #106 closes immediately after.
     */
    fun migrateToUnifiedRules() {
        if (prefs.getBoolean(KEY_RULES_MIGRATED, false)) return

        scheduleStore.schedules.forEach { stored ->
            val block = stored.toDomain() ?: return@forEach
            contextRuleStore.upsert(
                ContextRule(
                    id = "$MIGRATED_SCHEDULE_ID_PREFIX${stored.id}",
                    name = stored.name,
                    enabled = stored.enabled,
                    condition = Condition.DuringWindow(block.window),
                    effect = RuleEffect.Block,
                    target = when (stored.mode) {
                        ScheduleBlock.Mode.INCLUSIVE -> RuleTarget.Apps(block.packages)
                        ScheduleBlock.Mode.EXCLUSIVE -> RuleTarget.EverythingExcept(block.packages)
                    },
                ),
            )
        }

        val limit = dailyLimitMinutes
        if (limit > 0) {
            contextRuleStore.upsert(
                ContextRule(
                    id = MIGRATED_DAILY_LIMIT_ID,
                    name = appContext.getString(R.string.rules_row_limits),
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.SetDeviceBudget(WeeklyBudget.flat(limit.minutes)),
                    target = RuleTarget.Everything,
                ),
            )
        }

        // AppRule.AlwaysAllowed was the only thing exemptPackages ever meant —
        // the same safety-allowlist tier a rule can now name for itself via
        // RuleEffect.SafetyExempt, so each exempt package becomes one rule
        // rather than a fixed field the rule engine cannot see. Left as
        // AppRule.AlwaysAllowed too (see appRules below), for the same
        // mid-upgrade reason the schedule/limit stores are left alone above.
        exemptPackages.forEach { pkg ->
            contextRuleStore.upsert(
                ContextRule(
                    id = "$MIGRATED_EXEMPT_ID_PREFIX$pkg",
                    name = appContext.getString(R.string.rules_exempt_header),
                    enabled = true,
                    condition = Condition.Always,
                    effect = RuleEffect.SafetyExempt,
                    target = RuleTarget.Apps(setOf(PackageName(pkg))),
                ),
            )
        }

        prefs.edit { putBoolean(KEY_RULES_MIGRATED, true) }
    }

    // ---------- bedtime ----------

    /**
     * Bedtime, backed by the one fixed-id entry [ScheduleStore] treats
     * specially — see its own doc comment. This property's shape is
     * unchanged from when it read a dedicated set of fields, so nothing that
     * already called it (this device's own rules screen included) needed to
     * change; only where the answer comes from moved.
     */
    var bedtime: BedtimeSetting
        get() {
            val schedule = scheduleStore.find(ScheduleStore.BEDTIME_ID) ?: return BedtimeSetting.OFF
            return BedtimeSetting(
                enabled = schedule.enabled,
                start = schedule.start,
                end = schedule.end,
                days = schedule.days.mapNotNull(::dayOf).toSet(),
            )
        }
        set(value) {
            scheduleStore.upsert(
                StoredSchedule(
                    id = ScheduleStore.BEDTIME_ID,
                    name = appContext.getString(R.string.rules_bedtime_name),
                    days = value.days.map { it.value }.toSet(),
                    startMinuteOfDay = value.start.toMinuteOfDay(),
                    endMinuteOfDay = value.end.toMinuteOfDay(),
                    // Everything on the safety list survives bedtime anyway; a
                    // family additionally permitting something during it was
                    // never offered here, so this stays fixed rather than
                    // exposing a control bedtime's own card never had.
                    packages = emptySet(),
                    mode = ScheduleBlock.Mode.EXCLUSIVE,
                    kind = ScheduleBlock.Kind.BEDTIME,
                    enabled = value.enabled,
                ),
            )
            // ScheduleStore is a different SharedPreferences file from this
            // store's own, so the version bump every other setter here relies
            // on to signal a change never happens for this one — starting
            // enforcement directly, the same way SchedulesActivity already
            // does for every other schedule edit, is what SchedulesActivity's
            // own comment calls out: a window may be starting right now.
            AppRegistry.ruleMutationSink.rulesChanged()
        }

    // ---------- web filter (#42) ----------

    /** Delegates to [WebFilterStore], so a remote edit and the child's own filter screen agree. */
    var webFilterProfile: DnsProfile
        get() = webFilterStore.profile
        set(value) { webFilterStore.profile = value }

    var webFilterCustomHost: String
        get() = webFilterStore.customHost
        set(value) { webFilterStore.customHost = value }

    var browserPolicy: BrowserPolicy
        get() = webFilterStore.browserPolicy
        set(value) { webFilterStore.browserPolicy = value }

    var webFilterCountVisits: Boolean
        get() = webFilterStore.countVisits
        set(value) { webFilterStore.countVisits = value }

    // ---------- daily goal (#42) ----------

    /**
     * Delegates to [GoalStore]. [DailyGoal.effectiveFrom] on the value returned
     * is today's date if this device has never saved one, but is never itself
     * settable through this property — [GoalStore.save] derives it from
     * whether [DailyGoal.enabled] or [DailyGoal.maxUsage] actually changed, the
     * same restart-detection every existing caller already goes through, and a
     * remote edit gets no special path around that.
     */
    var goal: DailyGoal
        get() = goalStore.load(LocalDate.now())
        set(value) = goalStore.save(
            enabled = value.enabled,
            maxMinutes = value.maxUsage.inWholeMinutes.toInt(),
            prize = value.prize,
            today = LocalDate.now(),
        )

    // ---------- features (#42) ----------

    /**
     * The optional [Feature]s this phone currently has switched off. Delegates
     * to [FeatureFlags]; the three non-optional entries never appear here; they
     * were never able to be turned off locally either.
     */
    var disabledFeatures: Set<Feature>
        get() = Feature.entries.filter { it.optional && !featureFlags.isEnabled(it) }.toSet()
        set(value) = Feature.entries.filter { it.optional }
            .forEach { feature -> featureFlags.setEnabled(feature, feature !in value) }

    // ---------- categories ----------

    var categories: List<StoredCategory>
        get() = StoredCategory.listFromJson(prefs.getString(KEY_CATEGORIES, null))
        set(value) = write { putString(KEY_CATEGORIES, StoredCategory.listToJson(value)) }

    fun upsertCategory(category: StoredCategory) = synchronized(prefs) {
        categories = categories.filterNot { it.id == category.id } + category
    }

    fun deleteCategory(id: String) = synchronized(prefs) {
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

    fun setAppLimit(packageName: String, minutes: Int) = synchronized(prefs) {
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

    /**
     * Apps barred from the network but still able to open (WF-4).
     *
     * A lighter touch than blocking: the app runs and says for itself that it is
     * offline, rather than refusing to start with a system dialog nobody wrote.
     */
    var networkBlockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_NETWORK_BLOCKED, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_NETWORK_BLOCKED, value) }

    fun setNetworkBlocked(packageName: String, blocked: Boolean) = synchronized(prefs) {
        networkBlockedPackages = if (blocked) {
            networkBlockedPackages + packageName
        } else {
            networkBlockedPackages - packageName
        }
    }

    /** Harsher than blocked: the icon disappears entirely. */
    var hiddenPackages: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        set(value) = write { putStringSet(KEY_HIDDEN, value) }

    fun setBlocked(packageName: String, blocked: Boolean) = synchronized(prefs) {
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
     *
     * Deliberately outside [PolicySnapshot] and every other remote rule change —
     * see there for why. Flipping this on is a decision to be made standing at
     * the phone it affects, not from wherever a parent happens to be.
     */
    var tamperProtection: Boolean
        // Defaulted to on, matching Policy's own default, which this disagreed
        // with. The store is what actually runs, so the disagreement meant a
        // fresh install carried none of the lockdown while anyone reading the
        // model would have believed otherwise.
        get() = prefs.getBoolean(KEY_TAMPER, true)
        set(value) = write { putBoolean(KEY_TAMPER, value) }

    // ---------- plumbing ----------

    private fun Int.clampToDay(): Int =
        coerceIn(0, Defaults.MAX_DAILY_LIMIT.inWholeMinutes.toInt())

    private fun dayOf(value: Int): DayOfWeek? =
        DayOfWeek.entries.firstOrNull { it.value == value }

    /**
     * Every write bumps the version. The enforcement service compares it to decide
     * whether anything needs re-applying, and the audit log records it.
     *
     * Synchronized on [prefs] itself: rule edits arrive both from the UI thread
     * and from the sync executor thread (a parent's remote change), and without a
     * lock two concurrent writers could each read the same version before either
     * commits, so one writer's bump — or its whole edit, for the compound
     * read-modify-write setters above — would be silently lost.
     */
    private fun write(block: SharedPreferences.Editor.() -> Unit) = synchronized(prefs) {
        prefs.edit {
            block()
            putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, INITIAL_VERSION) + 1L)
        }
    }

    private companion object {
        const val FILE_NAME = "local_policy"
        const val INITIAL_VERSION = 1L
        const val LOCAL_CHILD_ID = "local"

        const val KEY_VERSION = "version"
        const val KEY_DAILY_LIMIT = "daily_limit_minutes"
        const val KEY_BLOCKED = "blocked_packages"
        const val KEY_HIDDEN = "hidden_packages"
        const val KEY_INSTALL_WINDOW = "install_window_until"
        const val KEY_NETWORK_BLOCKED = "network_blocked_packages"
        const val KEY_EXEMPT = "exempt_packages"
        const val KEY_CATEGORIES = "categories"
        const val KEY_APP_LIMITS = "app_limits"
        const val KEY_INSTALL_POLICY = "install_policy"
        const val KEY_TAMPER = "tamper_protection"
        const val KEY_RULES_MIGRATED = "rules_migrated_v1"

        /** The fixed id the daily limit becomes as a rule (#105). */
        const val MIGRATED_DAILY_LIMIT_ID = "migrated-daily-limit"

        /** The fixed id prefix a schedule becomes as a rule (#105). */
        const val MIGRATED_SCHEDULE_ID_PREFIX = "migrated-schedule-"

        /** The fixed id prefix an exempt package becomes as a rule (#105). */
        const val MIGRATED_EXEMPT_ID_PREFIX = "migrated-exempt-"
    }
}
