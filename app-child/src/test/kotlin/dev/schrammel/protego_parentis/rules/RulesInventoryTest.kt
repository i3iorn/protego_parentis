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
        // Bedtime moved out entirely as of #12 — it's a StoredSchedule now,
        // see the schedules map below, not a LocalPolicyStore field.
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
        "KEY_SCHEDULES=schedules" to PARENT,
        "KEY_ID=id" to PARENT,
        "KEY_NAME=name" to PARENT,
        "KEY_DAYS=days" to PARENT,
        "KEY_START=start" to PARENT,
        "KEY_END=end" to PARENT,
        // Named "allowed" on disk and `packages` in the model, deliberately: see
        // ScheduleStore.KEY_PACKAGES.
        "KEY_PACKAGES=allowed" to PARENT,
        "KEY_MODE=mode" to PARENT,
        "KEY_ENABLED=enabled" to PARENT,
        // Bedtime is the one fixed-id entry here now (#12) — see
        // ScheduleStore's own doc comment.
        "KEY_KIND=kind" to PARENT,
        // #83: every-other-week. Replicates the same way every other field
        // here already does.
        "KEY_PARITY_ANCHOR=parityAnchorEpochDay" to PARENT,
    )

    private val contextRules = mapOf(
        "KEY_RULES=rules" to PARENT,
        "KEY_ID=id" to PARENT,
        "KEY_NAME=name" to PARENT,
        "KEY_ENABLED=enabled" to PARENT,
        "KEY_CONDITION=condition" to PARENT,
        "KEY_EFFECT=effect" to PARENT,
        "KEY_TARGET=target" to PARENT,
        "KEY_ANNOUNCE_ARRIVALS=announce_arrivals" to PENDING,
        // A parent can rename, resize or remove a place from any phone, so
        // the list and its radius are Parent — but not where it is.
        "KEY_PLACES=places" to PARENT,
        "KEY_RADIUS=radius" to PARENT,
        // Deliberately never remote, not merely not-yet: see PlaceRef and
        // the README's "coordinates never leave the device" privacy claim.
        "KEY_LAT=lat" to LOCAL_ONLY,
        "KEY_LON=lon" to LOCAL_ONLY,
    )

    // The condition/effect/target codec shared with PolicyReplication, split
    // out of ContextRuleStore — see ContextRuleCodec's own doc comment.
    private val contextRuleCodec = mapOf(
        "KEY_KIND=kind" to PARENT,
        "KEY_VALUE=value" to PARENT,
        "KEY_PARTS=parts" to PARENT,
        "KEY_MINUTES=minutes" to PARENT,
    )

    // #42 closed this store: all nine fields ride PolicySnapshot now, the same
    // flat-scalar/membership-set channel dailyLimitMinutes and the packages
    // sets already use.
    private val webFilter = mapOf(
        "KEY_PROFILE=profile" to PARENT,
        "KEY_CUSTOM_HOST=custom_host" to PARENT,
        "KEY_SAFE_SEARCH=safe_search" to PARENT,
        "KEY_YOUTUBE=youtube" to PARENT,
        "KEY_INCOGNITO=incognito" to PARENT,
        "KEY_BLOCKED=blocked" to PARENT,
        "KEY_ALLOWED=allowed" to PARENT,
        "KEY_SUSPEND_BROWSERS=suspend_browsers" to PARENT,
        "KEY_COUNT_VISITS=count_visits" to PARENT,
    )

    // #42 closed this store: field-per-path with a `deleted` marker, the
    // same shape schedules and contextual rules already use for a list of
    // records.
    private val autoApproval = mapOf(
        "KEY_RULES=rules" to PARENT,
        // A running total the phone keeps about itself, not a rule.
        "KEY_FIRED=fired_total" to NOT_A_RULE,
        "KEY_ID=id" to PARENT,
        "KEY_NAME=name" to PARENT,
        "KEY_ENABLED=enabled" to PARENT,
        "KEY_MAX_MINUTES=maxMinutes" to PARENT,
        "KEY_MAX_PER_WINDOW=maxPerWindow" to PARENT,
        "KEY_WINDOW=window" to PARENT,
    )

    private val goals = mapOf(
        // #42: enabled/max_minutes/prize now ride PolicySnapshot.
        "KEY_ENABLED=enabled" to PARENT,
        "KEY_MAX_MINUTES=max_minutes" to PARENT,
        "KEY_PRIZE=prize" to PARENT,
        // Reclassified alongside #42, not left as PENDING backlog: this is
        // never a choice a parent makes, it's GoalStore.save's own
        // restart-detection recording when progress last reset — the same
        // kind of bookkeeping KEY_VERSION already is, not a rule.
        "KEY_EFFECTIVE_FROM=effective_from" to NOT_A_RULE,
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
        Triple("ContextRuleCodec", ContextRuleCodec::class.java, contextRuleCodec),
        Triple("WebFilterStore", WebFilterStore::class.java, webFilter),
        Triple("AutoApprovalStore", AutoApprovalStore::class.java, autoApproval),
        Triple("GoalStore", GoalStore::class.java, goals),
    )

    // ---------- stores whose settings are enum entries ----------

    /**
     * #42 closed the optional ones — a parent can now switch any of the 14
     * off from their own phone, via [LocalPolicyStore.disabledFeatures].
     * The 3 non-optional entries were never PENDING in truth: `setEnabled`
     * always refused them, on every phone, so there was never a rule there
     * to reach — reclassified to NOT_A_RULE rather than left as backlog.
     */
    private val features: Map<String, RuleReach> =
        Feature.entries.associate { it.name to if (it.optional) PARENT else NOT_A_RULE }

    /**
     * Decided alongside #42: stays local-only. What a phone tells the person
     * holding it is not something the other side of the family should be
     * able to change from a distance — see `docs/RULES-INVENTORY.md`. As
     * with [features], the 6 non-optional kinds were never really PENDING:
     * `setEnabled` always refused them.
     */
    private val notifications: Map<String, RuleReach> =
        NotificationKind.entries.associate { it.name to if (it.optional) LOCAL_ONLY else NOT_A_RULE }

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
            features.count { it.value == PENDING } +
            notifications.count { it.value == PENDING }

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
         * Settings a parent's phone still cannot reach.
         *
         * Was 78 at the start of #37 (9 schedules + 16 contextual rules and
         * places + 9 web filter + 7 auto-approval + 4 goals + 17 features + 16
         * notifications). #41/M3 made the 9 schedule fields reachable (69 left),
         * then the 11 contextual-rule condition/effect/target fields (58
         * left), then the places list itself — id/name/radius, everything
         * except a coordinate, which stays Local only rather than Pending
         * since replicating it is not a goal (54 left): 1 places field
         * (`announce_arrivals`) + 9 web filter + 7 auto-approval + 4 goals +
         * 17 features + 16 notifications. #12 then folded bedtime's four
         * dedicated LocalPolicyStore fields into a StoredSchedule (`kind`
         * joining the schedules map) — both already Parent, so this number
         * didn't move for it. #42 made web filter, the goal's three real
         * fields, and the 14 optional Features reachable (8 left): 1 places
         * field (`announce_arrivals`, unrelated to #42) + 7 auto-approval —
         * the one #42 slice still outstanding, a list of records rather
         * than flat scalars, its own milestone slice. Notifications and the
         * 3 non-optional Features were reclassified rather than closed:
         * never really Pending — see the two maps' own comments. #42's
         * final slice made the 7 auto-approval fields reachable too, the
         * same field-per-path-with-a-`deleted`-marker shape schedules and
         * contextual rules already use (1 left): `announce_arrivals`, tied
         * to the places/coordinates privacy question rather than to #42.
         */
        const val PENDING_AT_M0 = 1
    }
}
