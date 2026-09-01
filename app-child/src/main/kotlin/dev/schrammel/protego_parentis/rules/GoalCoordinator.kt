package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.model.DailyGoal
import dev.schrammel.protego_parentis.model.GoalAchievement
import dev.schrammel.protego_parentis.policy.PolicyClock
import dev.schrammel.protego_parentis.policy.goal.GoalEvaluator
import dev.schrammel.protego_parentis.usage.UsageHistoryStore
import java.time.Instant
import java.time.LocalDate

/**
 * Works out whether a prize has been earned.
 *
 * Runs against recorded history only, never against today. A day is judged once
 * it has closed, which is what makes the prize appear the morning after rather
 * than congratulating a child at breakfast for a target they miss by lunch.
 */
class GoalCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val goals = GoalStore(appContext)
    private val history = UsageHistoryStore(appContext)
    private val policyStore = LocalPolicyStore(appContext)

    fun goal(now: Instant = Instant.now()): DailyGoal = goals.load(today(now))

    fun saveGoal(enabled: Boolean, maxMinutes: Int, prize: String, now: Instant = Instant.now()) {
        goals.save(enabled, maxMinutes, prize, today(now))
    }

    /** A prize that has been earned but not yet shown. */
    fun pending(): GoalAchievement? = goals.pending()

    fun markShown(day: LocalDate) = goals.markCelebrated(day)

    /**
     * Looks for newly earned prizes and queues the oldest.
     *
     * One at a time: if several quiet days pass unnoticed, showing four
     * congratulation screens in a row devalues all of them. The rest stay in
     * history and surface on following days.
     */
    fun refresh(now: Instant = Instant.now()): GoalAchievement? {
        goals.pending()?.let { return it }

        val fresh = GoalEvaluator.newAchievements(
            goal = goals.load(today(now)),
            days = history.all(),
            alreadyCelebrated = goals.celebratedDays(),
        )
        val earned = fresh.firstOrNull() ?: return null
        goals.setPending(earned)
        return earned
    }

    private fun today(now: Instant): LocalDate {
        val policy = policyStore.load()
        return PolicyClock.policyDay(now, policy.timeZone, policy.dayBoundaryHour)
    }
}
