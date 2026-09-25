package dev.schrammel.protego_parentis.rules

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.AppRule
import dev.schrammel.protego_parentis.model.BrowserPolicy
import dev.schrammel.protego_parentis.model.DailyGoal
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.ScheduleBlock
import dev.schrammel.protego_parentis.rules.LocalPolicyStore.Companion.KEY_RULES_MIGRATED
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Log tag, outside the companion so the reflection-based inventory test sees only KEY_ constants. */
private const val TAG = "LocalPolicyStore"

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
 * Every setter that writes into this store's own SharedPreferences file bumps
 * the policy version, which is the signal the enforcement service uses to
 * notice a change and re-evaluate. Setters that delegate to a sibling store
 * instead — [webFilterProfile], [webFilterCustomHost], [browserPolicy],
 * [webFilterCountVisits], [goal], [disabledFeatures] — write into that
 * store's own file and do not bump this version; their callers are
 * responsible for triggering re-evaluation themselves (see e.g.
 * `WebFilterActivity`'s own calls to `AppRegistry.ruleMutationSink.rulesChanged()`).
 * [bedtime] is the one setter here that does neither: see its own doc
 * comment for why it triggers enforcement directly instead.
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

    /** Builds the domain [Policy] the engine evaluates — delegates to the
     * pure-JVM assembler so storage and policy construction are separate
     * concerns. */
    fun load(): Policy = PolicyAssembler.assemble(
        appContext = appContext,
        subStores = PolicyAssembler.PolicySubStores(
            policyStore = this,
            scheduleStore = scheduleStore,
            contextRuleStore = contextRuleStore,
            webFilterStore = webFilterStore,
        ),
        version = version,
        timeZone = timeZone,
        dayBoundaryHour = dayBoundaryHour,
        childId = LOCAL_CHILD_ID,
        budgetMinutes = dailyLimitMinutes,
        categoryList = categories,
        networkBlocked = networkBlockedPackages,
    )

    /**
     * [load]'s `version`, `timeZone` and `dayBoundaryHour`, without building
     * the [Policy]. [load] decodes several stores and asks the package manager
     * for the safety allowlist; callers that only need to know whether the
     * rules moved, or where the policy day starts, read these instead —
     * [dev.schrammel.protego_parentis.sync.PolicyReplication.pushFromLocalPolicy]
     * on every poll, and the grant/visit paths that only place a moment in a day.
     */
    val version: Long get() = prefs.getLong(KEY_VERSION, INITIAL_VERSION)

    val timeZone: ZoneId get() = ZoneId.systemDefault()

    val dayBoundaryHour: Int get() = Defaults.DAY_BOUNDARY_HOUR

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
     * [dailyLimitMinutes], and [exemptPackages] exactly as they are, rather
     * than deleting them once migrated: #106 made their one remaining editor
     * (`LimitsActivity`) unreachable, #107 removed `ParentLimitActivity` and
     * `SET_DAILY_LIMIT` outright, and #162 deleted `LimitsActivity` too, but a
     * device mid-upgrade, or still replicating an old-format edit from a
     * sibling phone that has not upgraded yet, must not lose the rule
     * because the field it was read from vanished under it. This runs once,
     * so an edit made through the old fields after that point — replicated
     * in from an older build, most plausibly — will not be reflected here
     * again.
     */
    fun migrateToUnifiedRules() {
        if (prefs.getBoolean(KEY_RULES_MIGRATED, false)) return

        RuleMigration.scheduleRules(scheduleStore.schedules).forEach(contextRuleStore::upsert)
        RuleMigration.dailyLimitRule(appContext, dailyLimitMinutes)?.let(contextRuleStore::upsert)
        // AppRule.AlwaysAllowed was the only thing exemptPackages ever meant —
        // the same safety-allowlist tier a rule can now name for itself via
        // RuleEffect.SafetyExempt, so each exempt package becomes one rule
        // rather than a fixed field the rule engine cannot see. Left as
        // AppRule.AlwaysAllowed too (see appRules below), for the same
        // mid-upgrade reason the schedule/limit stores are left alone above.
        RuleMigration.exemptRules(appContext, exemptPackages).forEach(contextRuleStore::upsert)

        prefs.edit { putBoolean(KEY_RULES_MIGRATED, true) }
    }

    /**
     * Folds every stored per-app limit, block, hide, and category budget into
     * the unified [ContextRuleStore] rule format, once — the same job
     * [migrateToUnifiedRules] already did for schedules, the flat daily
     * limit, and exemptions, done separately and gated by its own flag
     * because that migration already ran (and set [KEY_RULES_MIGRATED]) on
     * every phone upgrading through this point: gating this behind the same
     * flag would mean an existing family's app limits, blocks, hidden apps,
     * and category budgets silently stopped being enforced the moment
     * [PolicyEngine] stopped reading [AppRule.Budgeted]/[AppRule.Blocked]/
     * `Category.budget` directly, with nothing left to convert them.
     *
     * Idempotent the same way: [ContextRuleStore.upsert] replaces by id, so
     * running this again writes the same rules back. Deliberately leaves
     * [appLimits], [blockedPackages], [hiddenPackages], and each category's
     * `limitMinutes` exactly as they are — a sibling phone still on a build
     * from before this migration, or one mid-upgrade, must not lose the rule
     * because the field it was read from vanished under it.
     */
    fun migrateAppRulesToUnifiedRules() {
        if (prefs.getBoolean(KEY_APP_RULES_MIGRATED, false)) return

        RuleMigration.appBudgetRules(appContext, appLimits).forEach(contextRuleStore::upsert)
        RuleMigration.appBlockRules(appContext, blockedPackages, hiddenPackages).forEach(contextRuleStore::upsert)
        RuleMigration.categoryBudgetRules(categories).forEach(contextRuleStore::upsert)

        prefs.edit { putBoolean(KEY_APP_RULES_MIGRATED, true) }
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
            // enforcement directly is needed instead, since a window may be
            // starting right now.
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
        // Decode-cached: the tick's load() reads this every pass — see DecodeCache.
        get() = categoriesCache.get(
            prefs.getString(KEY_CATEGORIES, null),
            StoredCategory::listFromJson
        )
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
            val raw = prefs.getString(KEY_APP_LIMITS, null)
                ?: return emptyMap()
            // Decode-cached: the tick's load() reads this every pass — see DecodeCache.
            return appLimitsCache.get(raw) { r ->
                val json = runCatching { JSONObject(r.orEmpty()) }.getOrNull()
                    ?: return@get emptyMap()
                json.keys().asSequence()
                    .associateWith { json.optInt(it, 0) }
                    .filterValues { it > 0 }
            }
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
     *
     * Joins an outer [atomically] transaction when one is open on this instance,
     * rather than committing and bumping the version on its own — see there.
     */
    private fun write(block: SharedPreferences.Editor.() -> Unit) = synchronized(prefs) {
        val open = batchEditor
        if (open != null) {
            open.block()
            return@synchronized
        }
        prefs.edit {
            block()
            putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, INITIAL_VERSION) + 1L)
        }
        // The mutation is the trigger, not the caller: every setter here is a
        // rule change, and enforcement must hear about it whether the write
        // came from a screen (which used to remember to call
        // AppRegistry.ruleMutationSink.rulesChanged() itself) or from sync.
        notifyRulesChanged()
    }

    /** Set only for the duration of [atomically]; null otherwise. */
    private var batchEditor: SharedPreferences.Editor? = null

    /**
     * Runs [block] as a single commit and a single version bump, however many
     * of this store's setters it calls.
     *
     * Applying a remote snapshot used to call each setter individually — each
     * one its own commit and its own version bump — so a concurrent
     * `load()` from the enforcement worker could observe a [Policy] built from
     * a mix of old and new field values partway through the snapshot, and the
     * enforcement service would re-evaluate once per intermediate version
     * rather than once for the whole update. `synchronized(prefs)` is
     * reentrant on the same thread, so setters called from [block] see
     * [batchEditor] already open and write into it instead of starting their
     * own transaction.
     */
    fun atomically(block: () -> Unit) = synchronized(prefs) {
        check(batchEditor == null) { "atomically() does not nest" }
        val editor = prefs.edit()
        batchEditor = editor
        try {
            block()
            editor.putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, INITIAL_VERSION) + 1L)
            editor.apply()
            // One kick per transaction, not one per setter inside it — the
            // setters' own notifyRulesChanged calls are suppressed by the open
            // batch (write() takes the batchEditor branch, which returns early).
            notifyRulesChanged()
        } finally {
            batchEditor = null
        }
    }

    private fun notifyRulesChanged() {
        runCatching { AppRegistry.ruleMutationSink.rulesChanged() }
            .onFailure { Log.w(TAG, "Could not notify enforcement of a rule change", it) }
    }

    companion object {
        // Shared across instances (see DecodeCache): screens and the per-poll
        // replication classes each build their own store, so a cache held
        // per instance would almost never be warm.
        private val categoriesCache = DecodeCache<List<StoredCategory>>()
        private val appLimitsCache = DecodeCache<Map<String, Int>>()

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

        /** Separate from [KEY_RULES_MIGRATED]: see [migrateAppRulesToUnifiedRules]'s own doc comment for why. */
        const val KEY_APP_RULES_MIGRATED = "app_rules_migrated_v1"

        /** The fixed id the daily limit becomes as a rule (#105). */
        const val MIGRATED_DAILY_LIMIT_ID = "migrated-daily-limit"

        /** The fixed id prefix a schedule becomes as a rule (#105). */
        const val MIGRATED_SCHEDULE_ID_PREFIX = "migrated-schedule-"

        /** The fixed id prefix an exempt package becomes as a rule (#105). */
        const val MIGRATED_EXEMPT_ID_PREFIX = "migrated-exempt-"

        /** The fixed id prefix a per-app limit becomes as a rule. */
        const val MIGRATED_APP_BUDGET_ID_PREFIX = "migrated-app-budget-"

        /** The fixed id prefix a per-app block or hide becomes as a rule. */
        const val MIGRATED_APP_BLOCK_ID_PREFIX = "migrated-app-block-"

        /** The fixed id prefix a category's budget becomes as a rule. */
        const val MIGRATED_CATEGORY_BUDGET_ID_PREFIX = "migrated-category-budget-"
    }
}
