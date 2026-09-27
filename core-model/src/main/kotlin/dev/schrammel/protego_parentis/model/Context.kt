package dev.schrammel.protego_parentis.model

import java.time.Instant

/** A saved place, identified only by an id once it reaches the rules. */
@JvmInline
value class PlaceId(val value: String) {
    override fun toString(): String = value
}

/**
 * A Bluetooth device, by address, that the phone can see it is connected to —
 * a car, headphones, a watch. Connected, not merely in range: there is no scan
 * (#289).
 */
@JvmInline
value class DeviceTag(val value: String) {
    override fun toString(): String = value
}

/**
 * A place the family has named, stored only on this device.
 *
 * Coordinates live here and nowhere else. They are never put into a rule, never
 * written to the diagnostic log, and never leave this device — see
 * [DeviceContext] for why that is structural rather than a promise, and
 * [PlaceRef] for the part of a place that *does* replicate.
 */
data class Place(
    val id: PlaceId,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMetres: Int,
) {
    init {
        require(name.isNotBlank()) { "a place needs a name to show in a rule" }
        // Never the value itself, even here: this is an invariant check, not a
        // debug aid, and a position belongs in no log this app writes,
        // including a crash log an uncaught version of this exception would
        // otherwise land in.
        require(latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude in -180.0..180.0) { "longitude out of range" }
        require(radiusMetres >= MINIMUM_RADIUS_METRES) {
            "a radius under $MINIMUM_RADIUS_METRES m flaps constantly on consumer GPS"
        }
    }

    companion object {
        /**
         * Two reasons, not one. Below this, ordinary GPS jitter puts the phone in
         * and out of the place every few seconds, and rules would flicker with
         * it — the original reason this existed. #56 added the second: places
         * never carry a coordinate off this device (see this class's own doc
         * comment), but a radius small enough pins a position down almost as
         * precisely as one would, the moment anything — a fired rule, a
         * notification — reveals "inside" versus "outside". 100m keeps a family
         * able to tell home from school from the mall without being able to
         * tell which room, or which building, someone is in.
         *
         * Raising this number does not reject an existing place with a smaller
         * radius — see [dev.schrammel.protego_parentis.rules.ContextRuleStore]'s
         * decode, which clamps up instead — but it does mean an existing family
         * gets more protection than they explicitly asked for. Worth being
         * deliberate about the number for that reason.
         */
        const val MINIMUM_RADIUS_METRES = 100
    }
}

/**
 * A place's name and radius, without where it is.
 *
 * What [dev.schrammel.protego_parentis.sync.PolicyReplication] puts on the
 * wire for a [Place]: enough for a parent's own phone to see the places a
 * rule can reference, and to rename, resize or remove one, without that
 * phone ever receiving a coordinate back. *Moving* a place still only
 * happens on whichever device holds its [Place.latitude]/[Place.longitude] —
 * ordinarily the managed phone, since that is where a saved position is
 * turned into "inside" or "outside" for the rules engine — there is no
 * remote "move" operation at all, only rename/resize/delete on this
 * coordinate-free shape. *Creating* one has a single deliberate exception: a
 * parent can provide a coordinate — typed by hand, or their own phone's
 * current position — for somewhere the managed phone is not standing, and
 * ask it to create the place, over
 * [dev.schrammel.protego_parentis.sync.core.SyncMessageType.PLACE_CREATE_REQUESTED]
 * rather than through this type — that message carries a coordinate exactly
 * once, one-way, parent to child, and the place it creates is from then on
 * exactly as local as one saved by hand on the managed phone: never
 * replicated back out as a coordinate, and never movable remotely either.
 */
data class PlaceRef(
    val id: PlaceId,
    val name: String,
    val radiusMetres: Int,
) {
    init {
        require(name.isNotBlank()) { "a place needs a name to show in a rule" }
        require(radiusMetres >= Place.MINIMUM_RADIUS_METRES) {
            "a radius under ${Place.MINIMUM_RADIUS_METRES} m flaps constantly on consumer GPS"
        }
    }
}

/**
 * What is true about the phone's surroundings right now.
 *
 * Deliberately *resolved*: no coordinates, no signal strengths, no scan results.
 * The Android layer turns a location into the set of places it falls inside and
 * hands over only that. The consequence is that the whole rules engine — the part
 * that is easiest to get wrong and most likely to be extended later — is
 * structurally incapable of handling, logging or transmitting a position.
 *
 * A null [wifiSsid] means "not on Wi-Fi, or we were not allowed to look". The two
 * are deliberately indistinguishable to a rule, so a missing permission can never
 * make a restriction silently disappear.
 *
 * [batteryPercent] follows the same convention: null means "could not be read",
 * not "battery is fine" — a [Condition.BatteryBelow] rule simply does not fire
 * rather than firing on a guess.
 */
data class DeviceContext(
    val wifiSsid: String? = null,
    val nearbyDevices: Set<DeviceTag> = emptySet(),
    val insidePlaces: Set<PlaceId> = emptySet(),
    val batteryPercent: Int? = null,
    /** The newest glucose reading this phone has, or null if it has none. */
    val glucose: GlucoseReading? = null,
) {
    companion object {
        /** Nothing known: no Wi-Fi, nothing nearby, nowhere recognised. */
        val UNKNOWN = DeviceContext()
    }
}

/**
 * One glucose reading, from the family's own Nightscout server.
 *
 * Always in mg/dL, the unit Nightscout itself stores; conversion to mmol/L is
 * only ever for display. [at] is when the sensor took the reading, not when
 * this phone fetched it, so a server that stopped receiving data reads as
 * stale rather than current.
 */
data class GlucoseReading(val mgdl: Int, val at: Instant) {
    companion object {
        /**
         * How old a reading may be and still count as the current level. A
         * CGM reports every five minutes; three missed readings is the point
         * where "below 4.0" is a guess about now rather than a fact.
         */
        const val FRESH_FOR_MINUTES = 15L
    }
}

/**
 * When a contextual rule applies.
 *
 * Composable on purpose. "At school on the school Wi-Fi", "anywhere except home",
 * and "at Grandma's or when Dad's phone is nearby" are all things a real family
 * asks for, and none of them is expressible with a flat list of toggles.
 */
sealed interface Condition {

    /** Always true. A rule with no context, which is simply an always-on rule. */
    data object Always : Condition

    /** Connected to a Wi-Fi network with this exact name. */
    data class OnWifi(val ssid: String) : Condition

    /** A known Bluetooth device is connected (classic or LE). */
    data class NearDevice(val tag: DeviceTag) : Condition

    /** Inside a saved place. */
    data class AtPlace(val place: PlaceId) : Condition

    /** Battery at or under this percentage. Never fires if the level is unknown. */
    data class BatteryBelow(val percent: Int) : Condition {
        init {
            require(percent in 1..100) { "a battery threshold must be between 1 and 100" }
        }
    }

    /**
     * Inside a recurring time window (#101).
     *
     * The condition a schedule becomes once schedules are rules rather than
     * their own system — see #72. It carries the same [RecurringWindow] a
     * [ScheduleBlock] does, evaluated by the same resolver, so a rule written
     * this way and a schedule written the old way cannot drift apart on
     * midnight-crossing or alternating weeks.
     *
     * Unlike every other condition here, this one cannot be answered from
     * [DeviceContext] alone — it needs to know when "now" is. That is why
     * `ContextRules.holds` takes the moment explicitly rather than reading a
     * clock: the evaluator stays pure, and a caller cannot forget to supply
     * one without failing to compile.
     */
    data class DuringWindow(val window: RecurringWindow) : Condition

    /**
     * The device has already used this many minutes of screen time today (#102).
     *
     * The condition a flat daily limit becomes once it is a rule rather than its
     * own dedicated field — see #72. It reads the same device usage total the
     * standalone `Policy.deviceBudget` path already compares against, so a rule
     * written this way and the dedicated budget cannot disagree about what
     * counts as "used": both see grants and bonus time the same way, since that
     * accounting happens once, in `PolicyEngine`, before either ever sees a
     * number.
     */
    data class AllowanceSpent(val minutes: Int) : Condition {
        init {
            require(minutes >= 0) { "minutes spent cannot be negative" }
        }
    }

    /**
     * The current glucose level is under [mgdl]. Never holds without a
     * reading from the last [GlucoseReading.FRESH_FOR_MINUTES] minutes: an
     * old number is not the level now. [GlucoseStale] is how a rule handles
     * that case.
     */
    data class GlucoseBelow(val mgdl: Int) : Condition {
        init {
            require(mgdl in GLUCOSE_RANGE) { "a glucose level must be between 20 and 600 mg/dL" }
        }
    }

    /** The current glucose level is over [mgdl]. Freshness as [GlucoseBelow]. */
    data class GlucoseAbove(val mgdl: Int) : Condition {
        init {
            require(mgdl in GLUCOSE_RANGE) { "a glucose level must be between 20 and 600 mg/dL" }
        }
    }

    /**
     * No glucose reading from the last [minutes] minutes, including never
     * having had one: no internet, Nightscout down, a sensor change.
     */
    data class GlucoseStale(val minutes: Int) : Condition {
        init {
            require(minutes in 5..24 * 60) { "a gap must be between 5 minutes and a day" }
        }
    }

    data class AllOf(val conditions: List<Condition>) : Condition

    data class AnyOf(val conditions: List<Condition>) : Condition

    /**
     * At least [count] of [conditions] hold: "any two of these three".
     *
     * [count] of 1 is [AnyOf] and [count] equal to the list's size is
     * [AllOf]; both stay as their own shapes, since older app versions read
     * those and not this one.
     */
    data class AtLeast(val count: Int, val conditions: List<Condition>) : Condition {
        init {
            require(count >= 1) { "at least 1 condition has to hold, or the group means nothing" }
        }
    }

    data class Not(val condition: Condition) : Condition

    companion object {
        /** What a CGM can report, in mg/dL. */
        val GLUCOSE_RANGE = 20..600
    }
}

/** What a contextual rule does while it applies. */
sealed interface RuleEffect {

    /**
     * Blocks the target outright.
     *
     * [hidden] chooses between `setPackagesSuspended` (icon greys out, data
     * intact — the default) and `setApplicationHidden` (icon disappears), the
     * same distinction `AppRule.Blocked.hidden` drew before per-app blocking
     * became a rule like any other.
     *
     * The weakest of this engine's three block-or-stronger tiers: a plain
     * [Allow] rule elsewhere always frees a target this settles. [AlwaysBlock]
     * is the override that survives that.
     */
    data class Block(val hidden: Boolean = false) : RuleEffect

    /**
     * Exempts the target from every other contextual rule and from any budget
     * that would otherwise apply to it, as though it were always allowed.
     * Weaker than [SafetyExempt]: it settles only what [ContextRule]s decide
     * between themselves, so it has no bearing on network blocking, DNS
     * filtering, or anything outside this resolution — see [SafetyExempt] for
     * the stronger form. Also weaker than [AlwaysBlock]: a family that wants
     * an app blocked no matter what else the ruleset says reaches for that
     * instead of a plain [Block].
     */
    data object Allow : RuleEffect

    /**
     * Blocks the target regardless of any [Allow] rule that would otherwise
     * spare it — the override a family reaches for when "no, this one is
     * blocked, always" has to survive whatever else the ruleset says
     * elsewhere, the one hole a plain [Block] cannot close.
     *
     * Still weaker than [SafetyExempt]: the built-in-safety-allowlist tier
     * frees a target held here exactly as it frees one held by a plain
     * [Block]. [hidden] carries the same meaning as [Block.hidden].
     */
    data class AlwaysBlock(val hidden: Boolean = false) : RuleEffect

    /**
     * Exempts the target from everything, at the same tier as the built-in
     * safety allowlist (the dialer, and so on): every budget, every other
     * contextual rule (an [AlwaysBlock] override included), network blocking,
     * and DNS filtering. What `exemptPackages`/`AppRule.AlwaysAllowed` used to
     * mean before the rule engine could express it directly — a
     * parent-configured rule now, not a fixed invariant, so it can be as
     * narrow or as broad a condition/target as any other rule.
     */
    data object SafetyExempt : RuleEffect

    /**
     * Replaces the allowance for whatever its [RuleAction.target] resolves to:
     * the whole device under [RuleTarget.Everything], one or more apps under
     * [RuleTarget.Apps] (or the app half of a [RuleTarget.Mixed]), one or more
     * categories under [RuleTarget.Categories] (or its category half). With
     * several active rules naming the same device, app, or category, the
     * smallest wins for that one — a rule can always tighten, never loosen
     * past another rule, independently for each.
     *
     * Generalised from a device-only effect: `AppRule.Budgeted` and
     * `Category.budget` were their own dedicated fields with their own copy of
     * this same "tightest wins" resolution before per-app and per-category
     * limits became rules like any other, going through the one process that
     * already decided the device-wide case.
     */
    data class SetBudget(val budget: WeeklyBudget) : RuleEffect
}

/** What a contextual rule acts on. */
sealed interface RuleTarget {
    data object Everything : RuleTarget
    data class Apps(val packages: Set<PackageName>) : RuleTarget
    data class Categories(val categories: Set<CategoryId>) : RuleTarget

    /**
     * Both at once (#90) — a category plus a couple of apps that don't belong
     * to it, in one rule, rather than needing two rules that happen to share
     * a condition and effect. [Apps] and [Categories] stay as the plain single
     * kind each already was; this is only reached once a rule genuinely mixes
     * the two, so a rule that only ever picked one kind still round-trips
     * through the simpler variant unchanged.
     */
    data class Mixed(val packages: Set<PackageName>, val categories: Set<CategoryId>) : RuleTarget

    /**
     * Every installed app except these (#105).
     *
     * The migrated shape of a `StoredSchedule` in `EXCLUSIVE` mode — "block
     * everything except the alarm clock" — which [Apps]/[Mixed] cannot
     * express: both name what a rule covers, never what it excludes. Resolved
     * against installed packages at evaluation time, the same as [Everything]
     * already is, so it stays correct as apps are installed or removed rather
     * than freezing the complement at the moment a schedule was migrated.
     */
    data class EverythingExcept(val packages: Set<PackageName>) : RuleTarget
}

/** One "do this, to that" line of a rule. */
data class RuleAction(val effect: RuleEffect, val target: RuleTarget)

/**
 * A rule that only applies in some circumstances.
 *
 * The existing budgets and bedtime are unchanged and still do the everyday work.
 * These sit on top for the cases a clock cannot express: block games on the school
 * network, let homework apps run freely at home, tighten the allowance anywhere
 * that is not one of those.
 *
 * A rule applies while [condition] holds and [exceptWhen], if set, does not
 * ([activeWhen]). While it applies, every one of [actions] does. The
 * exception is kept apart from [condition] rather than folded into it, so the
 * editor can show it as the separate clause the parent wrote.
 */
data class ContextRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val condition: Condition,
    val actions: List<RuleAction>,
    val exceptWhen: Condition? = null,
) {
    /** A rule with one action, the only shape a rule could have before V2. */
    constructor(
        id: String,
        name: String,
        enabled: Boolean,
        condition: Condition,
        effect: RuleEffect,
        target: RuleTarget,
    ) : this(id, name, enabled, condition, listOf(RuleAction(effect, target)))

    init {
        require(id.isNotBlank()) { "a rule needs an id" }
        require(name.isNotBlank()) { "a rule needs a name to show on the block screen" }
        require(actions.isNotEmpty()) { "a rule needs something to do" }
    }

    /**
     * The first action's effect and target.
     *
     * Only for code that handles rules which have exactly one action by
     * construction, such as the per-app block or the daily limit the app
     * creates itself. Anything that reads arbitrary rules goes through
     * [actions].
     */
    val effect: RuleEffect get() = actions.first().effect
    val target: RuleTarget get() = actions.first().target

    /** When this rule applies, with [exceptWhen] folded in. */
    val activeWhen: Condition
        get() = exceptWhen?.let { Condition.AllOf(listOf(condition, Condition.Not(it))) } ?: condition
}
