package dev.schrammel.dementor_watch.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestState
import dev.schrammel.dementor_watch.ui.RequestInboxActivity
import dev.schrammel.dementor_watch.ui.RequestsActivity

/**
 * Tells each side that the other has said something.
 *
 * Two channels, because the two audiences want different things. A parent needs
 * to be interrupted by a request that expires in two hours; a child does not need
 * their evening broken into by a notification about a notification.
 *
 * The inbox notification opens a PIN-gated screen, so it is safe even though the
 * device it lands on is the child's.
 */
class RequestNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val notifications =
        checkNotNull(appContext.getSystemService(NotificationManager::class.java))

    init {
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                appContext.getString(R.string.request_channel_incoming_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.request_channel_incoming_description)
            },
        )
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DECISION,
                appContext.getString(R.string.request_channel_decision_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.request_channel_decision_description)
            },
        )
    }

    /** A new request is waiting for an answer. */
    fun notifyRequestReceived(request: Request) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_INCOMING,
            title = appContext.getString(R.string.request_notification_title),
            body = appContext.resources.getQuantityString(
                R.plurals.request_notification_body,
                request.amount.inWholeMinutes.toInt(),
                request.amount.inWholeMinutes.toInt(),
                request.reason,
            ),
            destination = RequestInboxActivity.intent(appContext),
        )
    }

    /** The request has been answered, one way or the other. */
    fun notifyDecision(request: Request) {
        val body = when (request.state) {
            RequestState.APPROVED -> appContext.getString(R.string.request_decision_approved)
            RequestState.DENIED -> appContext.getString(R.string.request_decision_denied)
            else -> return
        }
        val withNote = request.decisionNote
            ?.takeIf(String::isNotBlank)
            ?.let { "$body — $it" }
            ?: body

        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_DECISION,
            title = appContext.getString(R.string.request_decision_title),
            body = withNote,
            destination = RequestsActivity.intent(appContext),
        )
    }

    private fun post(channel: String, id: Int, title: String, body: String, destination: Intent) {
        notifications.notify(
            id,
            NotificationCompat.Builder(appContext, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        appContext,
                        id,
                        destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_INCOMING = "requests_incoming"
        const val CHANNEL_DECISION = "requests_decision"

        // Fixed ids: a second request replaces the first rather than stacking up.
        const val NOTIFICATION_INCOMING = 3
        const val NOTIFICATION_DECISION = 4
    }
}
