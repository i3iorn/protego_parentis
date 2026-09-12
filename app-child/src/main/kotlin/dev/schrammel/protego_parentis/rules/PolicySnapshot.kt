package dev.schrammel.protego_parentis.rules

import dev.schrammel.protego_parentis.model.BrowserPolicy
import dev.schrammel.protego_parentis.model.DailyGoal
import dev.schrammel.protego_parentis.model.InstallPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * Every rule a parent's phone may set remotely, in one round trip.
 *
 * Deliberately not the domain [dev.schrammel.protego_parentis.model.Policy]: that
 * carries fields no message should ever overwrite — [dev.schrammel.protego_parentis.model.Policy.safetyAllowlist]
 * is computed from what is actually installed, not something to be told, and
 * [dev.schrammel.protego_parentis.model.Policy.childId] and time zone are this
 * phone's own identity. This is the narrower set [LocalPolicyStore] itself owns
 * and edits — the same fields the rules screens on the child's own phone already
 * change one at a time.
 *
 * Tamper protection is deliberately absent. Flipping it remotely without anyone
 * standing at the phone is the one rule change this app treats as too dangerous
 * to make from a distance — see its own doc comment in [LocalPolicyStore].
 *
 * Bedtime is deliberately absent too, as of #12: it travels as an ordinary
 * [StoredSchedule] through [dev.schrammel.protego_parentis.sync.PolicyReplication.writeSchedules]/
 * [dev.schrammel.protego_parentis.sync.PolicyReplication.readSchedules] now,
 * the same channel every other schedule already used, rather than as four
 * fields duplicated here. See [LocalPolicyStore.bedtime].
 *
 * Web filtering, the daily goal, and which optional [Feature]s are switched
 * off joined as of #42 — all flat scalars or small sets, the same shape as
 * [dailyLimitMinutes] and the packages sets below, so they ride this same
 * channel rather than getting one of their own the way schedules needed.
 * `AutoApprovalStore` is deliberately absent, for the same reason bedtime is:
 * a list of records, the schedules/contextual-rules shape rather than this
 * one, so it travels through
 * [dev.schrammel.protego_parentis.sync.PolicyReplication.writeAutoApprovals]/
 * [dev.schrammel.protego_parentis.sync.PolicyReplication.readAutoApprovals]
 * instead. `NotificationPreferences` was decided to stay local-only — see
 * `docs/RULES-INVENTORY.md`.
 */
data class PolicySnapshot(
    val version: Long,
    val dailyLimitMinutes: Int,
    val categories: List<StoredCategory>,
    val appLimits: Map<String, Int>,
    val exemptPackages: Set<String>,
    val blockedPackages: Set<String>,
    val hiddenPackages: Set<String>,
    val networkBlockedPackages: Set<String>,
    val installPolicy: String,
    val installWindowUntil: Long?,
    val webFilterProfile: String,
    val webFilterCustomHost: String,
    val webFilterSafeSearch: Boolean,
    val webFilterYoutube: Boolean,
    val webFilterIncognito: Boolean,
    val webFilterBlockedUrls: Set<String>,
    val webFilterAllowedUrls: Set<String>,
    val webFilterSuspendBrowsers: Boolean,
    val webFilterCountVisits: Boolean,
    val goalEnabled: Boolean,
    val goalMaxMinutes: Int,
    val goalPrize: String,
    val disabledFeatures: Set<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_VERSION, version)
        put(KEY_DAILY_LIMIT, dailyLimitMinutes)
        put(KEY_CATEGORIES, JSONArray(categories.map(StoredCategory::toJson)))
        put(KEY_APP_LIMITS, JSONObject(appLimits))
        put(KEY_EXEMPT, JSONArray(exemptPackages.toList()))
        put(KEY_BLOCKED, JSONArray(blockedPackages.toList()))
        put(KEY_HIDDEN, JSONArray(hiddenPackages.toList()))
        put(KEY_NETWORK_BLOCKED, JSONArray(networkBlockedPackages.toList()))
        put(KEY_INSTALL_POLICY, installPolicy)
        installWindowUntil?.let { put(KEY_INSTALL_WINDOW, it) }
        put(KEY_WEB_PROFILE, webFilterProfile)
        put(KEY_WEB_CUSTOM_HOST, webFilterCustomHost)
        put(KEY_WEB_SAFE_SEARCH, webFilterSafeSearch)
        put(KEY_WEB_YOUTUBE, webFilterYoutube)
        put(KEY_WEB_INCOGNITO, webFilterIncognito)
        put(KEY_WEB_BLOCKED_URLS, JSONArray(webFilterBlockedUrls.toList()))
        put(KEY_WEB_ALLOWED_URLS, JSONArray(webFilterAllowedUrls.toList()))
        put(KEY_WEB_SUSPEND_BROWSERS, webFilterSuspendBrowsers)
        put(KEY_WEB_COUNT_VISITS, webFilterCountVisits)
        put(KEY_GOAL_ENABLED, goalEnabled)
        put(KEY_GOAL_MAX_MINUTES, goalMaxMinutes)
        put(KEY_GOAL_PRIZE, goalPrize)
        put(KEY_DISABLED_FEATURES, JSONArray(disabledFeatures.toList()))
    }

    fun encode(): String = toJson().toString()

    companion object {
        private const val KEY_VERSION = "version"
        private const val KEY_DAILY_LIMIT = "dailyLimitMinutes"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_APP_LIMITS = "appLimits"
        private const val KEY_EXEMPT = "exemptPackages"
        private const val KEY_BLOCKED = "blockedPackages"
        private const val KEY_HIDDEN = "hiddenPackages"
        private const val KEY_NETWORK_BLOCKED = "networkBlockedPackages"
        private const val KEY_INSTALL_POLICY = "installPolicy"
        private const val KEY_INSTALL_WINDOW = "installWindowUntil"
        private const val KEY_WEB_PROFILE = "webFilterProfile"
        private const val KEY_WEB_CUSTOM_HOST = "webFilterCustomHost"
        private const val KEY_WEB_SAFE_SEARCH = "webFilterSafeSearch"
        private const val KEY_WEB_YOUTUBE = "webFilterYoutube"
        private const val KEY_WEB_INCOGNITO = "webFilterIncognito"
        private const val KEY_WEB_BLOCKED_URLS = "webFilterBlockedUrls"
        private const val KEY_WEB_ALLOWED_URLS = "webFilterAllowedUrls"
        private const val KEY_WEB_SUSPEND_BROWSERS = "webFilterSuspendBrowsers"
        private const val KEY_WEB_COUNT_VISITS = "webFilterCountVisits"
        private const val KEY_GOAL_ENABLED = "goalEnabled"
        private const val KEY_GOAL_MAX_MINUTES = "goalMaxMinutes"
        private const val KEY_GOAL_PRIZE = "goalPrize"
        private const val KEY_DISABLED_FEATURES = "disabledFeatures"

        fun fromJson(json: JSONObject): PolicySnapshot? {
            fun strings(key: String): Set<String> =
                json.optJSONArray(key)?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty().toSet()

            val appLimits = json.optJSONObject(KEY_APP_LIMITS)?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optInt(it, 0) }.filterValues { it > 0 }
            }.orEmpty()

            val categories = json.optJSONArray(KEY_CATEGORIES)?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(StoredCategory::fromJson) }
            }.orEmpty()

            return PolicySnapshot(
                version = json.optLong(KEY_VERSION, 0L),
                dailyLimitMinutes = json.optInt(KEY_DAILY_LIMIT, 0),
                categories = categories,
                appLimits = appLimits,
                exemptPackages = strings(KEY_EXEMPT),
                blockedPackages = strings(KEY_BLOCKED),
                hiddenPackages = strings(KEY_HIDDEN),
                networkBlockedPackages = strings(KEY_NETWORK_BLOCKED),
                installPolicy = json.optString(KEY_INSTALL_POLICY).ifBlank { InstallPolicy.NOTIFY.name },
                installWindowUntil = json.optLong(KEY_INSTALL_WINDOW, 0L).takeIf { it > 0L },
                webFilterProfile = json.optString(KEY_WEB_PROFILE).ifBlank { DnsProfile.OFF.name },
                webFilterCustomHost = json.optString(KEY_WEB_CUSTOM_HOST),
                webFilterSafeSearch = json.optBoolean(KEY_WEB_SAFE_SEARCH, false),
                webFilterYoutube = json.optBoolean(KEY_WEB_YOUTUBE, false),
                webFilterIncognito = json.optBoolean(KEY_WEB_INCOGNITO, false),
                webFilterBlockedUrls = strings(KEY_WEB_BLOCKED_URLS),
                webFilterAllowedUrls = strings(KEY_WEB_ALLOWED_URLS),
                webFilterSuspendBrowsers = json.optBoolean(KEY_WEB_SUSPEND_BROWSERS, false),
                webFilterCountVisits = json.optBoolean(KEY_WEB_COUNT_VISITS, false),
                goalEnabled = json.optBoolean(KEY_GOAL_ENABLED, false),
                goalMaxMinutes = json.optInt(KEY_GOAL_MAX_MINUTES, 0),
                goalPrize = json.optString(KEY_GOAL_PRIZE),
                disabledFeatures = strings(KEY_DISABLED_FEATURES),
            )
        }

        fun decode(raw: String?): PolicySnapshot? {
            if (raw.isNullOrBlank()) return null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            return fromJson(json)
        }
    }
}

/** Reads the current rules off this phone, in the shape a message can carry. */
fun LocalPolicyStore.snapshot(): PolicySnapshot {
    val filter = browserPolicy
    val currentGoal = goal
    return PolicySnapshot(
        version = load().version,
        dailyLimitMinutes = dailyLimitMinutes,
        categories = categories,
        appLimits = appLimits,
        exemptPackages = exemptPackages,
        blockedPackages = blockedPackages,
        hiddenPackages = hiddenPackages,
        networkBlockedPackages = networkBlockedPackages,
        installPolicy = installPolicy.name,
        installWindowUntil = installWindowUntil?.toEpochMilli(),
        webFilterProfile = webFilterProfile.name,
        webFilterCustomHost = webFilterCustomHost,
        webFilterSafeSearch = filter.forceSafeSearch,
        webFilterYoutube = filter.restrictYouTube,
        webFilterIncognito = filter.blockIncognito,
        webFilterBlockedUrls = filter.blockedUrls.toSet(),
        webFilterAllowedUrls = filter.allowedUrls.toSet(),
        webFilterSuspendBrowsers = filter.suspendUnmanagedBrowsers,
        webFilterCountVisits = webFilterCountVisits,
        goalEnabled = currentGoal.enabled,
        goalMaxMinutes = currentGoal.maxUsage.inWholeMinutes.toInt(),
        goalPrize = currentGoal.prize,
        disabledFeatures = disabledFeatures.map { it.name }.toSet(),
    )
}

/**
 * Writes every field of [snapshot] onto this phone's own rules.
 *
 * Bedtime is not among them any more — it arrives (and is applied) through
 * [dev.schrammel.protego_parentis.sync.PolicyReplication.pullSchedulesIntoLocal],
 * which [dev.schrammel.protego_parentis.sync.SyncReceiver] already calls
 * alongside this on every policy change, the same way every other schedule
 * already did before bedtime joined them.
 */
fun LocalPolicyStore.applySnapshot(snapshot: PolicySnapshot) = atomically {
    dailyLimitMinutes = snapshot.dailyLimitMinutes
    categories = snapshot.categories
    appLimits = snapshot.appLimits
    exemptPackages = snapshot.exemptPackages
    blockedPackages = snapshot.blockedPackages
    hiddenPackages = snapshot.hiddenPackages
    networkBlockedPackages = snapshot.networkBlockedPackages
    installPolicy = runCatching { InstallPolicy.valueOf(snapshot.installPolicy) }
        .getOrDefault(InstallPolicy.NOTIFY)
    installWindowUntil = snapshot.installWindowUntil?.let(Instant::ofEpochMilli)
    // Everything below lives in a separate SharedPreferences file of its own
    // (webFilterStore/goalStore/featureFlags) and so cannot join this single
    // commit — [LocalPolicyStore.atomically]'s guarantee covers this store's
    // own file, which is also the only one `Policy.version` is read from.
    webFilterProfile = DnsProfile.parse(snapshot.webFilterProfile)
    webFilterCustomHost = snapshot.webFilterCustomHost
    browserPolicy = BrowserPolicy(
        forceSafeSearch = snapshot.webFilterSafeSearch,
        restrictYouTube = snapshot.webFilterYoutube,
        blockIncognito = snapshot.webFilterIncognito,
        blockedUrls = snapshot.webFilterBlockedUrls.toList(),
        allowedUrls = snapshot.webFilterAllowedUrls.toList(),
        suspendUnmanagedBrowsers = snapshot.webFilterSuspendBrowsers,
    )
    webFilterCountVisits = snapshot.webFilterCountVisits
    goal = DailyGoal(
        enabled = snapshot.goalEnabled,
        maxUsage = snapshot.goalMaxMinutes.minutes,
        prize = snapshot.goalPrize,
        // Ignored by GoalStore.save's own restart-detection — see LocalPolicyStore.goal.
        effectiveFrom = LocalDate.now(),
    )
    disabledFeatures = snapshot.disabledFeatures
        .mapNotNull { name -> Feature.entries.firstOrNull { it.name == name } }
        .toSet()
}
