package dev.schrammel.protego_parentis.ui

import dev.schrammel.protego_parentis.model.Condition

/**
 * Which kind of thing a rule is about — the badge on a rule card, the first
 * sort the eye makes before reading a word. This is not
 * [dev.schrammel.protego_parentis.ui.rules.sectionOf]'s grouping of a whole
 * rules list: it answers only "what is this one rule about", from its first
 * leaf, reading through negations — a rule "not on school Wi-Fi" is still
 * about Wi-Fi.
 */
internal enum class RuleKind {
    ALWAYS,
    WINDOW,
    ALLOWANCE,
    WIFI,
    PLACE,
    DEVICE,
    BATTERY,
    GLUCOSE,
    ;

    /** The first leaf's kind — see [RuleDescriber.RuleParts.kind]. */
    companion object {
        fun of(condition: Condition): RuleKind = when (condition) {
            is Condition.AllOf -> condition.conditions.firstOrNull()?.let(::of) ?: ALWAYS
            is Condition.AnyOf -> condition.conditions.firstOrNull()?.let(::of) ?: ALWAYS
            is Condition.AtLeast ->
                if (condition.conditions.isEmpty()) ALWAYS else of(condition.conditions.first())
            is Condition.Not -> of(condition.condition)
            is Condition.Always -> ALWAYS
            is Condition.DuringWindow -> WINDOW
            is Condition.AllowanceSpent -> ALLOWANCE
            is Condition.OnWifi -> WIFI
            is Condition.AtPlace -> PLACE
            is Condition.NearDevice -> DEVICE
            is Condition.BatteryBelow -> BATTERY
            is Condition.GlucoseBelow -> GLUCOSE
            is Condition.GlucoseAbove -> GLUCOSE
            is Condition.GlucoseStale -> GLUCOSE
        }
    }
}
