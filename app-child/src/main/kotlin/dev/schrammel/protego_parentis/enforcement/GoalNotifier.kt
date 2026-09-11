package dev.schrammel.protego_parentis.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.GoalAchievement
import dev.schrammel.protego_parentis.format.formatDuration

/**
 * Announces an earned prize on the next unlock.
 *
 * A notification rather than the screen appearing by itself: since Android 10 an
 * app cannot launch an activity from the background, and a foreground service is
 * not an exemption. Trying anyway would work on some devices and silently fail on
 * others, which is worse than a notification that always arrives. Opening the app
 * shows the same screen directly.
 */
class GoalNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val notifications =
        checkNotNull(appContext.getSystemService(NotificationManager::class.java))

    init {
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.goal_channel_name),
                // The one notification in this app that is unambiguously good news.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.goal_channel_description)
            },
        )
    }

    fun notifyEarned(achievement: GoalAchievement) {
        // The celebration screen still appears on the next unlock; this is only
        // whether the phone also buzzes about it.
        if (!NotificationPreferences(appContext).isEnabled(NotificationKind.GOAL_EARNED)) return

        notifications.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appContext.getString(R.string.goal_notification_title))
                .setContentText(
                    appContext.getString(
                        R.string.goal_notification_body,
                        appContext.formatDuration(achievement.used),
                        appContext.formatDuration(achievement.target),
                    ),
                )
                .setAutoCancel(true)
                .setContentIntent(AppRegistry.notificationUx.openCelebration(NOTIFICATION_ID))
                .build(),
        )
    }

    fun clear() {
        notifications.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "goal_earned"
        const val NOTIFICATION_ID = 5
    }
}
