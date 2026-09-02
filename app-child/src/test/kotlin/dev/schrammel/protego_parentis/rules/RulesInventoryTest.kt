package dev.schrammel.protego_parentis.rules

import dev.schrammel.protego_parentis.enforcement.NotificationKind
import dev.schrammel.protego_parentis.rules.RuleReach.LOCAL_ONLY
import dev.schrammel.protego_parentis.rules.RuleReach.NOT_A_RULE
import dev.schrammel.protego_parentis.rules.RuleReach.PARENT
import dev.schrammel.protego_parentis.rules.RuleReach.PENDING
import org.junit.Assert.assertEquals
import org.junit.Test

/** How far a stored setting reaches. See `docs/RULES-INVENTORY.md`. */
internal enum class RuleReach {
    /** A parent can change it from their own phone today. */
    PARENT,

    /** A rule, but only settable on the managed phone. What #37 is closing. */
    PENDING,

    /** Deliberately never remote. The reason belongs next to the entry. */
    LOCAL_ONLY,

    /** Persisted, but not a rule: bookkeeping, or state the app keeps for itself. */
    NOT_A_RULE,
}

/**
 * Every persisted setting in every rule store, classified.
 *
 * Nine rule stores are unreachable from a parent's phone, and the reason is not
 * that anybody decided so — it is that each was added without the question being
 * asked. Without something mechanical the same thing happens again during #37,
 * which is the project that exists to fix it.
 *
 * **This file is the source of truth**, and `docs/RULES-INVENTORY.md` is its
 * readable rendering. If the two disagree, this is right and the document is
 * stale.
 *
 * Keys are read by reflection rather than referenced directly, because a
 * constant referenced by name cannot catch the case that matters: a *new* one
 * nobody thought to reference. The stores keep them in private companions, which
 * `declaredFields` still reaches — the value goes in the assertion alongside the
 * name, so a rename and a silent change of the stored key both fail.
 */
class RulesInventoryTest {

    // ---------- stores keyed by preference name ----------

    private val localPolicy = mapOf(
        // Not a rule: the counter that tells a parent's copy from a stale one.
        "KEY_VERSION=version" to NOT_A_RULE,
        "KEY_DAILY_LIMIT=daily_limit_minutes" to PARENT,
        "KEY_BEDTIME_ENABLED=bedtime_enabled" to PARENT,
        "KEY_BEDTIME_START=bedtime_start_minute_of_day" to PARENT,
        "KEY_BEDTIME_END=bedtime_end_minute_of_day" to PARENT,
        "KEY_BEDTIME_DAYS=bedtime_days" to PARENT,
        "KEY_BLOCKED=blocked_packages" to PARENT,
        "KEY_HIDDEN=hidden_packages" to PARENT,
        "KEY_INSTALL_WINDOW=install_window_until" to PARENT,
        "KEY_NETWORK_BLOCKED=network_blocked_packages" to PARENT,
        "KEY_EXEMPT=exempt_packages" to PARENT,
        "KEY_CATEGORIES=categories" to PARENT,
        "KEY_APP_LIMITS=app_limits" to PARENT,
        "KEY_INSTALL_POLICY=install_policy" to PARENT,
        // The one deliberate exception. Flipping it with nobody standing at the
        // phone is the single change this app treats as too dangerous to make
        // from a distance — see LocalPolicyStore's own doc comment.
        "KEY_TAMPER=tamper_protection" to LOCAL_ONLY,
    )

    private val schedules = mapOf(
        "KEY_SCHEDULES=schedules" to PENDING,
        "KEY_ID=id" to PENDING,
        "KEY_NAME=name" to PENDING,
        "KEY_DAYS=days" to PENDING,
        "KEY_START=start" to PENDING,
        "KEY_END=end" to PENDING,
        // Named "allowed" on disk and `packages` in the model, deliberately: see
        // ScheduleStore.KEY_PACKAGES.
        "KEY_PACKAGES=allowed" to PENDING,
        "KEY_MODE=mode" to PENDING,
        "KEY_ENABLED=enabled" to PENDING,
    )

    private val contextRules = mapOf(
        "KEY_ANNOUNCE_ARRIVALS=announce_arrivals" to PENDING,
        "KEY_RULES=rules" to PENDING,
        "KEY_PLACES=places" to PENDING,
        "KEY_ID=id" to PENDING,
        "KEY_NAME=name" to PENDING,
        "KEY_ENABLED=enabled" to PENDING,
        "KEY_CONDITION=condition" to PENDING,
        "KEY_EFFECT=effect" to PENDING,
        "KEY_TARGET=target" to PENDING,
        "KEY_KIND=kind" to PENDING,
        "KEY_VALUE=value" to PENDING,
        "KEY_PARTS=parts" to PENDING,
        "KEY_MINUTES=minutes" to PENDING,
        // A place a family deliberately marked. Replicating these means
        // coordinates leaving the phone — see #41 before it does.
        "KEY_LAT=lat" to PENDING,
        "KEY_LON=lon" to PENDING,
        "KEY_RADIUS=radius" to PENDING,
    )

    private val webFilter = mapOf(
        "KEY_PROFILE=profile" to PENDING,
        "KEY_CUSTOM_HOST=custom_host" to PENDING,
        "KEY_SAFE_SEARCH=safe_search" to PENDING,
        "KEY_YOUTUBE=youtube" to PENDING,
        "KEY_INCOGNITO=incognito" to PENDING,
        "KEY_BLOCKED=blocked" to PENDING,
        "KEY_ALLOWED=allowed" to PENDING,
        "KEY_SUSPEND_BROWSERS=suspend_browsers" to PENDING,
        "KEY_COUNT_VISITS=count_visits" to PENDING,
    )

    private val autoApproval = mapOf(
        "KEY_RULES=rules" to PENDING,
        // A running total the phone keeps about itself, not a rule.
        "KEY_FIRED=fired_total" to NOT_A_RULE,
        "KEY_ID=id" to PENDING,
        "KEY_NAME=name" to PENDING,
        "KEY_ENABLED=enabled" to PENDING,
        "KEY_MAX_MINUTES=maxMinutes" to PENDING,
        "KEY_MAX_PER_WINDOW=maxPerWindow" to PENDING,
        "KEY_WINDOW=window" to PENDING,
    )

    private val goals = mapOf(
        "KEY_ENABLED=enabled" to PENDING,
        "KEY_MAX_MINUTES=max_minutes" to PENDING,
        "KEY_PRIZE=prize" to PENDING,
        "KEY_EFFECTIVE_FROM=effective_from" to PENDING,
        // Everything below is what has happened, not what may happen.
        "KEY_CELEBRATED=celebrated_days" to NOT_A_RULE,
        "KEY_PENDING_DAY=pending_day" to NOT_A_RULE,
        "KEY_PENDING_USED=pending_used" to NOT_A_RULE,
        "KEY_PENDING_TARGET=pending_target" to NOT_A_RULE,
        "KEY_PENDING_PRIZE=pending_prize" to NOT_A_RULE,
    )

    private val keyedStores = listOf(
        Triple("LocalPolicyStore", LocalPolicyStore::class.java, localPolicy),
        Triple("ScheduleStore", ScheduleStore::class.java, schedules),
        Triple("ContextRuleStore", ContextRuleStore::class.java, contextRules),
        Triple("WebFilterStore", WebFilterStore::class.java, webFilter),
        Triple("AutoApprovalStore", AutoApprovalStore::class.java, autoApproval),
        Triple("GoalStore", GoalStore::class.java, goals),
    )

    // ---------- stores whose settings are enum entries ----------

    private val features: Map<String, RuleReach> =
        Feature.entries.associate { it.name to PENDING }

    private val notifications: Map<String, RuleReach> =
        NotificationKind.entries.associate { it.name to PENDING }

    // ---------- the checks ----------

    @Test
    fun `every persisted key in every rule store is classified`() {
        keyedStores.forEach { (name, type, expected) ->
            assertEquals(
                unaccounted(name),
                expected.keys.sorted(),
                persistedKeys(type).sorted(),
            )
        }
    }

    /**
     * Enum-backed stores are covered by construction — the maps are built from
     * `entries` — so this only guards the shape that would break that: an entry
     * arriving under a name already present, or the enum being emptied.
     */
    @Test
    fun `every feature and notification is classified`() {
        assertEquals(Feature.entries.size, features.size)
        assertEquals(NotificationKind.entries.size, notifications.size)
    }

    /**
     * The ratchet. This number only goes down, and every milestone of #37 that
     * lands takes some of it — so a failure here is usually somebody's progress
     * asking to be written down.
     */
    @Test
    fun `the rules a parent cannot reach are the ones #37 is closing`() {
        val pending = keyedStores.sumOf { (_, _, fields) -> fields.count { it.value == PENDING } } +
            features.size +
            notifications.size

        assertEquals(
            "If you have just made a rule reachable from a parent's phone, " +
                "reclassify it above and lower this number. If it went up, a new " +
                "rule was added that a parent cannot set — which is the thing #37 exists to stop.",
            PENDING_AT_M0,
            pending,
        )
    }

    private fun persistedKeys(type: Class<*>): Set<String> =
        type.declaredFields
            .filter { it.name.startsWith("KEY_") }
            .map { field ->
                // Not trySetAccessible: unit tests compile against android.jar,
                // and Android's AccessibleObject never took that Java 9 addition.
                // The deprecation this looks like it should worry about is on the
                // getter, which a write access never calls — and Android did not
                // adopt that deprecation either.
                field.isAccessible = true
                "${field.name}=${field.get(null)}"
            }
            .toSet()

    private fun unaccounted(store: String) =
        "$store has a persisted key the inventory does not mention, or mentions one " +
            "it no longer has. Add it to this test and to docs/RULES-INVENTORY.md, " +
            "deciding as you go whether a parent should be able to set it."

    private companion object {
        /**
         * Settings a parent's phone cannot reach, at the start of #37.
         *
         * 9 schedules + 16 contextual rules and places + 9 web filter +
         * 7 auto-approval + 4 goals + 17 features + 16 notifications.
         */
        const val PENDING_AT_M0 = 78
    }
}
