package dev.schrammel.dementor_watch.policy.goal

import dev.schrammel.dementor_watch.model.DailyGoal
import dev.schrammel.dementor_watch.model.GoalAchievement
import dev.schrammel.dementor_watch.model.UsageSnapshot
import java.time.LocalDate

/**
 * Decides whether a finished day beat the goal.
 *
 * Only ever applied to *closed* days. Judging a day still in progress would
 * congratulate a child at breakfast for a target they go on to miss by lunch,
 * which is worse than not having the feature at all.
 */
object GoalEvaluator {

    /**
     * The achievement for [day], or null if there is not one.
     *
     * Strictly under the target, not equal to it: "use less than an hour" should
     * not be satisfied by using exactly an hour.
     */
    fun evaluate(goal: DailyGoal, day: UsageSnapshot): GoalAchievement? {
        if (!goal.enabled) return null
        if (day.policyDay.isBefore(goal.effectiveFrom)) return null
        if (day.deviceTotal >= goal.maxUsage) return null

        return GoalAchievement(
            day = day.policyDay,
            used = day.deviceTotal,
            target = goal.maxUsage,
            prize = goal.prize,
        )
    }

    /**
     * Achievements among [days] that have not already been celebrated, oldest
     * first.
     *
     * [alreadyCelebrated] is passed in rather than inferred so a prize is shown
     * exactly once, even if the same day is re-evaluated after a reboot or a
     * history rebuild.
     */
    fun newAchievements(
        goal: DailyGoal,
        days: List<UsageSnapshot>,
        alreadyCelebrated: Set<LocalDate>,
    ): List<GoalAchievement> = days
        .asSequence()
        .filterNot { it.policyDay in alreadyCelebrated }
        .mapNotNull { evaluate(goal, it) }
        .sortedBy { it.day }
        .toList()
}
