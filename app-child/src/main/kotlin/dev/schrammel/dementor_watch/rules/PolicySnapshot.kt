package dev.schrammel.dementor_watch.rules

import dev.schrammel.dementor_watch.model.InstallPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant

/**
 * Every rule a parent's phone may set remotely, in one round trip.
 *
 * Deliberately not the domain [dev.schrammel.dementor_watch.model.Policy]: that
 * carries fields no message should ever overwrite — [dev.schrammel.dementor_watch.model.Policy.safetyAllowlist]
 * is computed from what is actually installed, not something to be told, and
 * [dev.schrammel.dementor_watch.model.Policy.childId] and time zone are this
 * phone's own identity. This is the narrower set [LocalPolicyStore] itself owns
 * and edits — the same fields the rules screens on the child's own phone already
 * change one at a time.
 *
 * Tamper protection is deliberately absent. Flipping it remotely without anyone
 * standing at the phone is the one rule change this app treats as too dangerous
 * to make from a distance — see its own doc comment in [LocalPolicyStore].
 */
data class PolicySnapshot(
    val version: Long,
    val dailyLimitMinutes: Int,
    val bedtimeEnabled: Boolean,
    val bedtimeStartMinute: Int,
    val bedtimeEndMinute: Int,
    val bedtimeDays: Set<Int>,
    val categories: List<StoredCategory>,
    val appLimits: Map<String, Int>,
    val exemptPackages: Set<String>,
    val blockedPackages: Set<String>,
    val hiddenPackages: Set<String>,
    val networkBlockedPackages: Set<String>,
    val installPolicy: String,
    val installWindowUntil: Long?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_VERSION, version)
        put(KEY_DAILY_LIMIT, dailyLimitMinutes)
        put(KEY_BEDTIME_ENABLED, bedtimeEnabled)
        put(KEY_BEDTIME_START, bedtimeStartMinute)
        put(KEY_BEDTIME_END, bedtimeEndMinute)
        put(KEY_BEDTIME_DAYS, JSONArray(bedtimeDays.toList()))
        put(KEY_CATEGORIES, JSONArray(categories.map(StoredCategory::toJson)))
        put(KEY_APP_LIMITS, JSONObject(appLimits))
        put(KEY_EXEMPT, JSONArray(exemptPackages.toList()))
        put(KEY_BLOCKED, JSONArray(blockedPackages.toList()))
        put(KEY_HIDDEN, JSONArray(hiddenPackages.toList()))
        put(KEY_NETWORK_BLOCKED, JSONArray(networkBlockedPackages.toList()))
        put(KEY_INSTALL_POLICY, installPolicy)
        installWindowUntil?.let { put(KEY_INSTALL_WINDOW, it) }
    }

    fun encode(): String = toJson().toString()

    companion object {
        private const val KEY_VERSION = "version"
        private const val KEY_DAILY_LIMIT = "dailyLimitMinutes"
        private const val KEY_BEDTIME_ENABLED = "bedtimeEnabled"
        private const val KEY_BEDTIME_START = "bedtimeStart"
        private const val KEY_BEDTIME_END = "bedtimeEnd"
        private const val KEY_BEDTIME_DAYS = "bedtimeDays"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_APP_LIMITS = "appLimits"
        private const val KEY_EXEMPT = "exemptPackages"
        private const val KEY_BLOCKED = "blockedPackages"
        private const val KEY_HIDDEN = "hiddenPackages"
        private const val KEY_NETWORK_BLOCKED = "networkBlockedPackages"
        private const val KEY_INSTALL_POLICY = "installPolicy"
        private const val KEY_INSTALL_WINDOW = "installWindowUntil"

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

            val bedtimeDays = json.optJSONArray(KEY_BEDTIME_DAYS)?.let { array ->
                (0 until array.length()).map { array.optInt(it) }
            }.orEmpty().toSet()

            return PolicySnapshot(
                version = json.optLong(KEY_VERSION, 0L),
                dailyLimitMinutes = json.optInt(KEY_DAILY_LIMIT, 0),
                bedtimeEnabled = json.optBoolean(KEY_BEDTIME_ENABLED, false),
                bedtimeStartMinute = json.optInt(KEY_BEDTIME_START, 0),
                bedtimeEndMinute = json.optInt(KEY_BEDTIME_END, 0),
                bedtimeDays = bedtimeDays,
                categories = categories,
                appLimits = appLimits,
                exemptPackages = strings(KEY_EXEMPT),
                blockedPackages = strings(KEY_BLOCKED),
                hiddenPackages = strings(KEY_HIDDEN),
                networkBlockedPackages = strings(KEY_NETWORK_BLOCKED),
                installPolicy = json.optString(KEY_INSTALL_POLICY).ifBlank { InstallPolicy.NOTIFY.name },
                installWindowUntil = json.optLong(KEY_INSTALL_WINDOW, 0L).takeIf { it > 0L },
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
    val setting = bedtime
    return PolicySnapshot(
        version = load().version,
        dailyLimitMinutes = dailyLimitMinutes,
        bedtimeEnabled = setting.enabled,
        bedtimeStartMinute = setting.start.toMinuteOfDay(),
        bedtimeEndMinute = setting.end.toMinuteOfDay(),
        bedtimeDays = setting.days.map(DayOfWeek::getValue).toSet(),
        categories = categories,
        appLimits = appLimits,
        exemptPackages = exemptPackages,
        blockedPackages = blockedPackages,
        hiddenPackages = hiddenPackages,
        networkBlockedPackages = networkBlockedPackages,
        installPolicy = installPolicy.name,
        installWindowUntil = installWindowUntil?.toEpochMilli(),
    )
}

/** Writes every field of [snapshot] onto this phone's own rules. */
fun LocalPolicyStore.applySnapshot(snapshot: PolicySnapshot) {
    dailyLimitMinutes = snapshot.dailyLimitMinutes
    bedtime = BedtimeSetting(
        enabled = snapshot.bedtimeEnabled,
        start = snapshot.bedtimeStartMinute.toLocalTime(),
        end = snapshot.bedtimeEndMinute.toLocalTime(),
        days = snapshot.bedtimeDays.mapNotNull { value -> DayOfWeek.entries.firstOrNull { it.value == value } }
            .toSet()
            .ifEmpty { BedtimeSetting.ALL_DAYS },
    )
    categories = snapshot.categories
    appLimits = snapshot.appLimits
    exemptPackages = snapshot.exemptPackages
    blockedPackages = snapshot.blockedPackages
    hiddenPackages = snapshot.hiddenPackages
    networkBlockedPackages = snapshot.networkBlockedPackages
    installPolicy = runCatching { InstallPolicy.valueOf(snapshot.installPolicy) }
        .getOrDefault(InstallPolicy.NOTIFY)
    installWindowUntil = snapshot.installWindowUntil?.let(Instant::ofEpochMilli)
}
