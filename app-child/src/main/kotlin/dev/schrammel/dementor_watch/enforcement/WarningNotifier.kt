package dev.schrammel.dementor_watch.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.model.BudgetStatus
import dev.schrammel.dementor_watch.ui.HomeActivity
import dev.schrammel.dementor_watch.ui.formatDuration
import java.time.LocalDate

/**
 * Drives the warning ladder (ST-3).
 *
 * Fires once per level change, not once per evaluation — a notification every
 * minute for the last ten minutes of the day would train the teen to swipe them
 * away without reading, which defeats the point of warning at all.
 */
class WarningNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val notifications =
        checkNotNull(appContext.getSystemService(NotificationManager::class.java))
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    init {
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.warning_channel_name),
                // HIGH: a limit about to bite is the one thing that must be seen
                // before it happens, not discovered afterwards.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.warning_channel_description)
            },
        )
    }

    fun onEvaluated(budget: BudgetStatus, policyDay: LocalDate) {
        val level = budget.level
        val lastLevel = lastLevelFor(policyDay)

        // Only ever announce going *down* the ladder. Bonus time or a new day
        // resets quietly.
        if (lastLevel != null && level.ordinal <= lastLevel.ordinal) {
            remember(level, policyDay)
            return
        }
        remember(level, policyDay)

        val text = when (level) {
            BudgetLevel.NORMAL -> return
            BudgetLevel.NOTICE ->
                appContext.getString(R.string.warn_notice, appContext.formatDuration(budget.remaining))
            BudgetLevel.GRACE ->
                appContext.getString(R.string.warn_grace, appContext.formatDuration(budget.remaining))
            BudgetLevel.EXHAUSTED -> appContext.getString(R.string.warn_exhausted)
        }

        // Silenced by choice rather than by not being built: a family that would
        // rather not be warned still gets the limit, just not the countdown.
        if (!NotificationPreferences(appContext).isEnabled(NotificationKind.TIME_WARNING)) return

        notifications.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appContext.getString(R.string.warn_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        appContext,
                        0,
                        Intent(appContext, HomeActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build(),
        )
    }

    private fun lastLevelFor(day: LocalDate): BudgetLevel? {
        if (prefs.getString(KEY_DAY, null) != day.toString()) return null
        val name = prefs.getString(KEY_LEVEL, null) ?: return null
        return runCatching { BudgetLevel.valueOf(name) }.getOrNull()
    }

    private fun remember(level: BudgetLevel, day: LocalDate) {
        prefs.edit {
            putString(KEY_DAY, day.toString())
            putString(KEY_LEVEL, level.name)
        }
    }

    private companion object {
        const val FILE_NAME = "warnings"
        const val KEY_DAY = "day"
        const val KEY_LEVEL = "level"
        const val CHANNEL_ID = "warnings"
        const val NOTIFICATION_ID = 2
    }
}
