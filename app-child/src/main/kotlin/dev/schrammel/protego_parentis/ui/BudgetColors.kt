package dev.schrammel.protego_parentis.ui

import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.BudgetLevel

/**
 * The colour a budget level reads as, wherever one is shown — the child's own
 * dashboard and a parent's synced summary agree on this because they call the
 * same function, not because two independent copies happen to still match.
 */
internal fun colourFor(level: BudgetLevel): Int = when (level) {
    BudgetLevel.NORMAL -> R.color.state_ok
    BudgetLevel.NOTICE, BudgetLevel.GRACE -> R.color.state_warning
    BudgetLevel.EXHAUSTED -> R.color.state_critical
}

/**
 * How much of a budget is gone, as a percentage bar can use.
 *
 * Whole minutes rather than the child dashboard's previous `Duration` ratio: the
 * two used to compute this independently, in floating point on one side and
 * integer minutes on the other, which could disagree by a point right at a
 * rounding boundary. There is now exactly one answer to "what percent is that".
 */
internal fun percentUsed(usedMinutes: Int, totalMinutes: Int): Int {
    if (totalMinutes <= 0) return 0
    return ((usedMinutes.toLong() * 100L) / totalMinutes).coerceIn(0L, 100L).toInt()
}
