package dev.schrammel.protego_parentis.model

/** A saved place, identified only by an id once it reaches the rules. */
@JvmInline
value class PlaceId(val value: String) {
    override fun toString(): String = value
}

/** A device the phone can recognise being near — a parent's phone, a beacon. */
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
 * phone ever receiving a coordinate. Creating a place, or moving it, still
 * only happens on whichever device holds its [Place.latitude]/
 * [Place.longitude] — ordinarily the managed phone, since that is where a
 * saved position is turned into "inside" or "outside" for the rules engine.
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
) {
    companion object {
        /** Nothing known: no Wi-Fi, nothing nearby, nowhere recognised. */
        val UNKNOWN = DeviceContext()
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

    /** A known device is within range. */
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

    data class AllOf(val conditions: List<Condition>) : Condition

    data class AnyOf(val conditions: List<Condition>) : Condition

    data class Not(val condition: Condition) : Condition
}

/** What a contextual rule does while it applies. */
sealed interface RuleEffect {

    /** Blocks the target outright. */
    data object Block : RuleEffect

    /** Exempts the target from every limit, as though it were always allowed. */
    data object Allow : RuleEffect

    /**
     * Replaces the device's daily allowance. With several active, the smallest
     * wins — a rule can always tighten, never loosen past another rule.
     */
    data class SetDeviceBudget(val budget: WeeklyBudget) : RuleEffect
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
}

/**
 * A rule that only applies in some circumstances.
 *
 * The existing budgets and bedtime are unchanged and still do the everyday work.
 * These sit on top for the cases a clock cannot express: block games on the school
 * network, let homework apps run freely at home, tighten the allowance anywhere
 * that is not one of those.
 */
data class ContextRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val condition: Condition,
    val effect: RuleEffect,
    val target: RuleTarget,
) {
    init {
        require(id.isNotBlank()) { "a rule needs an id" }
        require(name.isNotBlank()) { "a rule needs a name to show on the block screen" }
    }
}
