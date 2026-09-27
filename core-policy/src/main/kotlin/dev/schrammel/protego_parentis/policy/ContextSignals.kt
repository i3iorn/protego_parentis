package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule

/**
 * Whether a rule set depends on the phone's surroundings.
 *
 * Wi-Fi, a nearby device, a saved place and battery level change with no
 * clock able to predict it, and the app has no broadcast for any of them:
 * a rule reading one is only re-evaluated when the enforcement tick runs.
 * [PolicyEngine]'s next-change moment covers everything time-driven, so a
 * rule set that reads none of these can idle at a long interval, and one
 * that reads any of them needs the tick kept short to apply promptly.
 */
object ContextSignals {

    fun readsSurroundings(rules: List<ContextRule>): Boolean =
        rules.any { it.enabled && readsSurroundings(it.activeWhen) }

    /**
     * Whether an enabled rule depends on being at a saved place — the one
     * signal the app has to go and fetch rather than read (#290), so the
     * only one worth spending a location fix on.
     */
    fun readsPlaces(rules: List<ContextRule>): Boolean =
        rules.any { it.enabled && readsPlaces(it.activeWhen) }

    /**
     * Whether an enabled rule depends on a glucose level, which the app has to
     * fetch from Nightscout rather than read, so the fetch only happens while
     * something needs it.
     */
    fun readsGlucose(rules: List<ContextRule>): Boolean =
        rules.any { it.enabled && readsGlucose(it.activeWhen) }

    private fun readsGlucose(condition: Condition): Boolean = when (condition) {
        is Condition.GlucoseBelow, is Condition.GlucoseAbove, is Condition.GlucoseStale -> true
        is Condition.AllOf -> condition.conditions.any(::readsGlucose)
        is Condition.AnyOf -> condition.conditions.any(::readsGlucose)
        is Condition.AtLeast -> condition.conditions.any(::readsGlucose)
        is Condition.Not -> readsGlucose(condition.condition)
        Condition.Always,
        is Condition.OnWifi,
        is Condition.NearDevice,
        is Condition.AtPlace,
        is Condition.BatteryBelow,
        is Condition.DuringWindow,
        is Condition.AllowanceSpent,
        -> false
    }

    private fun readsPlaces(condition: Condition): Boolean = when (condition) {
        is Condition.AtPlace -> true
        is Condition.OnWifi,
        is Condition.NearDevice,
        is Condition.BatteryBelow,
        Condition.Always,
        is Condition.DuringWindow,
        is Condition.AllowanceSpent,
        is Condition.GlucoseBelow,
        is Condition.GlucoseAbove,
        is Condition.GlucoseStale,
        -> false
        is Condition.AllOf -> condition.conditions.any(::readsPlaces)
        is Condition.AnyOf -> condition.conditions.any(::readsPlaces)
        is Condition.AtLeast -> condition.conditions.any(::readsPlaces)
        is Condition.Not -> readsPlaces(condition.condition)
    }

    /**
     * Exhaustive on purpose: a new condition kind has to decide here whether
     * a clock can predict it, rather than silently defaulting to "yes".
     */
    private fun readsSurroundings(condition: Condition): Boolean = when (condition) {
        is Condition.OnWifi,
        is Condition.NearDevice,
        is Condition.AtPlace,
        is Condition.BatteryBelow,
        is Condition.GlucoseBelow,
        is Condition.GlucoseAbove,
        is Condition.GlucoseStale,
        -> true

        Condition.Always,
        is Condition.DuringWindow,
        is Condition.AllowanceSpent,
        -> false

        is Condition.AllOf -> condition.conditions.any(::readsSurroundings)
        is Condition.AnyOf -> condition.conditions.any(::readsSurroundings)
        is Condition.AtLeast -> condition.conditions.any(::readsSurroundings)
        is Condition.Not -> readsSurroundings(condition.condition)
    }
}
