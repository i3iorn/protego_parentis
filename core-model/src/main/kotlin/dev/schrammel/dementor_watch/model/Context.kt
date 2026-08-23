package dev.schrammel.dementor_watch.model

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
 * written to the diagnostic log, and never transmitted — see [DeviceContext] for
 * why that is structural rather than a promise.
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
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
        require(radiusMetres >= MINIMUM_RADIUS_METRES) {
            "a radius under $MINIMUM_RADIUS_METRES m flaps constantly on consumer GPS"
        }
    }

    companion object {
        /**
         * Below this, ordinary GPS jitter puts the phone in and out of the place
         * every few seconds, and rules would flicker with it.
         */
        const val MINIMUM_RADIUS_METRES = 75
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
 */
data class DeviceContext(
    val wifiSsid: String? = null,
    val nearbyDevices: Set<DeviceTag> = emptySet(),
    val insidePlaces: Set<PlaceId> = emptySet(),
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
