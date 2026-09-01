package dev.schrammel.protego_parentis.model

import java.time.LocalDate
import kotlin.time.Duration

/**
 * A target the child can hit, and what they get for it.
 *
 * Deliberately the mirror image of a limit. A limit says "you may not"; a goal
 * says "if you do, here is something good". The same number can drive both, but
 * only one of them is worth trying to beat.
 *
 * [prize] is free text written by the parent — a promise, a treat, a well wish.
 * The app never interprets it, which is the point: what motivates a particular
 * child is not something software should be guessing at.
 */
data class DailyGoal(
    val enabled: Boolean,
    /** Achieved by staying strictly under this for a whole day. */
    val maxUsage: Duration,
    val prize: String,
    /**
     * The first day this goal can be earned on.
     *
     * Without it, setting a goal would immediately award every quiet day already
     * in history — including days backfilled at install time, before the goal
     * existed. A prize for something you did last Tuesday is not a prize.
     */
    val effectiveFrom: LocalDate,
) {
    init {
        require(!maxUsage.isNegative()) { "a goal cannot ask for negative screen time" }
    }

    companion object {
        fun off(today: LocalDate) = DailyGoal(
            enabled = false,
            maxUsage = Duration.ZERO,
            prize = "",
            effectiveFrom = today,
        )
    }
}

/** A day the goal was met on. */
data class GoalAchievement(
    val day: LocalDate,
    val used: Duration,
    val target: Duration,
    val prize: String,
)
