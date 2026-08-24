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
import dev.schrammel.dementor_watch.sync.IncomingRequest
import dev.schrammel.dementor_watch.sync.NewApp
import dev.schrammel.dementor_watch.ui.AppControlActivity
import dev.schrammel.dementor_watch.ui.AuditActivity
import dev.schrammel.dementor_watch.ui.ParentDeviceActivity
import dev.schrammel.dementor_watch.ui.ParentInboxActivity
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
                CHANNEL_SOS,
                appContext.getString(R.string.sos_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.sos_channel_description)
                enableVibration(true)
                setBypassDnd(true)
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

    /**
     * The same news, arriving on a parent's own phone over sync.
     *
     * Separate from [notifyRequestReceived] because the destination differs: this
     * one opens the parent phone's inbox, which reads from the sync store rather
     * than from the child's local requests.
     */
    fun notifyIncomingRequest(request: IncomingRequest) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_INCOMING,
            title = appContext.getString(R.string.request_notification_title),
            body = appContext.resources.getQuantityString(
                R.plurals.request_notification_body,
                request.minutes,
                request.minutes,
                request.reason,
            ),
            destination = ParentInboxActivity.intent(appContext),
        )
    }

    /**
     * A new app appeared on the child's phone (AC-3).
     *
     * Uses the same channel as an incoming request, and a different notification
     * id so one does not replace the other — a new app and a request for more time
     * are two separate things a parent may want to act on.
     */
    fun notifyNewApp(app: NewApp) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_NEW_APP,
            title = appContext.getString(R.string.new_app_notification_title),
            body = appContext.getString(R.string.new_app_notification_body, app.label),
            destination = ParentDeviceActivity.intent(appContext),
        )
    }

    /** The same news on the child's own device, where the review is PIN-gated. */
    fun notifyNewAppLocally(label: String) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_NEW_APP,
            title = appContext.getString(R.string.new_app_notification_title),
            body = appContext.getString(R.string.new_app_notification_body, label),
            destination = AppControlActivity.intent(appContext),
        )
    }

    /**
     * The child device has stopped checking in (TR-2).
     *
     * Deliberately not on the high-importance channel used for requests: this is
     * usually a flat battery or a school bag, and a full-volume alert for the
     * common case is how a parent learns to swipe these away unread.
     */
    fun notifyDeviceSilent(body: String) {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_SILENT,
            title = appContext.getString(R.string.integrity_notification_title),
            body = body,
            destination = ParentDeviceActivity.intent(appContext),
        )
    }

    /**
     * A parent locked this phone.
     *
     * Posted before the screen goes dark, so it is waiting on the lock screen. A
     * phone that locks itself with no explanation looks broken, and finding out
     * why should not require unlocking it again.
     */
    fun notifyLocked() {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_LOCKED,
            title = appContext.getString(R.string.locked_notification_title),
            body = appContext.getString(R.string.locked_notification_body),
            destination = AuditActivity.intent(appContext),
        )
    }

    /**
     * A parent asked where this phone is (LOC-2).
     *
     * Every time, without exception, and whether or not a position was found. A
     * child told only about the successful ones has been misled about how often
     * they are looked for — and that symmetry is the only thing separating this
     * feature from the tracking the product refuses to be.
     */
    fun notifyLocated() {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_LOCATED,
            title = appContext.getString(R.string.located_notification_title),
            body = appContext.getString(R.string.located_notification_body),
            destination = AuditActivity.intent(appContext),
        )
    }

    /** A standing rule answered a request, on the parent's phone. */
    fun notifyAutoApproved(ruleName: String) {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_AUTO_APPROVED,
            title = appContext.getString(R.string.auto_notification_title),
            body = appContext.getString(R.string.auto_notification_body, ruleName),
            destination = ParentInboxActivity.intent(appContext),
        )
    }

    /** The child arrived at, or left, a saved place. */
    fun notifyPlaceTransition(place: String, arrived: Boolean) {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_PLACE,
            title = appContext.getString(
                if (arrived) R.string.place_arrived_title else R.string.place_left_title,
            ),
            body = appContext.getString(
                if (arrived) R.string.place_arrived_body else R.string.place_left_body,
                place,
            ),
            destination = ParentDeviceActivity.intent(appContext),
        )
    }

    /**
     * The child raised an emergency (LOC-3).
     *
     * The one notification in this app that is allowed to be loud, and the one
     * that is never throttled: everything else here is tuned not to teach a parent
     * to swipe without reading, and this is what that restraint was being saved
     * for.
     */
    fun notifySos(moved: Boolean) {
        post(
            channel = CHANNEL_SOS,
            id = NOTIFICATION_SOS,
            title = appContext.getString(R.string.sos_notification_title),
            body = appContext.getString(
                if (moved) R.string.sos_notification_moved else R.string.sos_notification_body,
            ),
            destination = ParentDeviceActivity.intent(appContext),
            ongoing = true,
        )
    }

    fun notifySosCleared() {
        notifications.cancel(NOTIFICATION_SOS)
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_SOS_CLEARED,
            title = appContext.getString(R.string.sos_cleared_title),
            body = appContext.getString(R.string.sos_cleared_body),
            destination = ParentDeviceActivity.intent(appContext),
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

    private fun post(
        channel: String,
        id: Int,
        title: String,
        body: String,
        destination: Intent,
        ongoing: Boolean = false,
    ) {
        notifications.notify(
            id,
            NotificationCompat.Builder(appContext, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
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

        /** Its own channel, so a family can silence everything else and not this. */
        const val CHANNEL_SOS = "sos"

        // Fixed ids: a second request replaces the first rather than stacking up.
        const val NOTIFICATION_INCOMING = 3
        const val NOTIFICATION_DECISION = 4
        const val NOTIFICATION_NEW_APP = 5
        const val NOTIFICATION_SILENT = 6
        const val NOTIFICATION_LOCKED = 7
        const val NOTIFICATION_LOCATED = 8
        const val NOTIFICATION_AUTO_APPROVED = 9
        const val NOTIFICATION_PLACE = 10
        const val NOTIFICATION_SOS = 11
        const val NOTIFICATION_SOS_CLEARED = 12
    }
}
