package dev.schrammel.dementor_watch.rules

import android.content.Context
import androidx.core.content.edit
import dev.schrammel.dementor_watch.model.DailyGoal
import dev.schrammel.dementor_watch.model.GoalAchievement
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * The family's daily goal, and which days have already been celebrated.
 *
 * The celebrated set is what stops a prize being shown twice — after a reboot,
 * after the app is reopened, or if history is ever recomputed.
 */
class GoalStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(today: LocalDate): DailyGoal {
        val storedFrom = prefs.getString(KEY_EFFECTIVE_FROM, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        return DailyGoal(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            maxUsage = prefs.getInt(KEY_MAX_MINUTES, 0).minutes,
            prize = prefs.getString(KEY_PRIZE, null).orEmpty(),
            effectiveFrom = storedFrom ?: today,
        )
    }

    /**
     * Saves the goal. [effectiveFrom] is only moved when the goal is switched on
     * or its target changes, so editing the prize text does not silently reset
     * progress toward a day already under way.
     */
    /**
     * Synchronized on [prefs]: whether to restart progress depends on reading the
     * previous goal first, and without a lock a concurrent save (UI thread vs. a
     * sync callback applying a parent's remote change) could read a value the
     * other was mid-write on, or have its own write overwritten in between.
     */
    fun save(enabled: Boolean, maxMinutes: Int, prize: String, today: LocalDate) = synchronized(prefs) {
        val previous = load(today)
        val restart = enabled && (!previous.enabled || previous.maxUsage != maxMinutes.minutes)

        prefs.edit {
            putBoolean(KEY_ENABLED, enabled)
            putInt(KEY_MAX_MINUTES, maxMinutes.coerceAtLeast(0))
            putString(KEY_PRIZE, prize.trim())
            if (restart) putString(KEY_EFFECTIVE_FROM, today.toString())
        }
    }

    // ---------- celebrations ----------

    fun celebratedDays(): Set<LocalDate> =
        prefs.getStringSet(KEY_CELEBRATED, emptySet())
            .orEmpty()
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .toSet()

    /** The prize waiting to be shown, if any. */
    fun pending(): GoalAchievement? {
        val day = prefs.getString(KEY_PENDING_DAY, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return null

        return GoalAchievement(
            day = day,
            used = prefs.getInt(KEY_PENDING_USED, 0).minutes,
            target = prefs.getInt(KEY_PENDING_TARGET, 0).minutes,
            prize = prefs.getString(KEY_PENDING_PRIZE, null).orEmpty(),
        )
    }

    fun setPending(achievement: GoalAchievement) {
        prefs.edit {
            putString(KEY_PENDING_DAY, achievement.day.toString())
            putInt(KEY_PENDING_USED, achievement.used.inWholeMinutes.toInt())
            putInt(KEY_PENDING_TARGET, achievement.target.inWholeMinutes.toInt())
            putString(KEY_PENDING_PRIZE, achievement.prize)
        }
    }

    /**
     * Marks the prize as shown. The day joins the celebrated set in the same write
     * as the pending slot is cleared, so a crash between the two cannot lose the
     * record and award it again.
     */
    fun markCelebrated(day: LocalDate) = synchronized(prefs) {
        prefs.edit {
            putStringSet(KEY_CELEBRATED, (celebratedDays() + day).map(LocalDate::toString).toSet())
            remove(KEY_PENDING_DAY)
            remove(KEY_PENDING_USED)
            remove(KEY_PENDING_TARGET)
            remove(KEY_PENDING_PRIZE)
        }
    }

    private companion object {
        const val FILE_NAME = "goal"
        const val KEY_ENABLED = "enabled"
        const val KEY_MAX_MINUTES = "max_minutes"
        const val KEY_PRIZE = "prize"
        const val KEY_EFFECTIVE_FROM = "effective_from"
        const val KEY_CELEBRATED = "celebrated_days"
        const val KEY_PENDING_DAY = "pending_day"
        const val KEY_PENDING_USED = "pending_used"
        const val KEY_PENDING_TARGET = "pending_target"
        const val KEY_PENDING_PRIZE = "pending_prize"
    }
}
