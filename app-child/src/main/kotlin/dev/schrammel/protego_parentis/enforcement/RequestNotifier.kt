package dev.schrammel.protego_parentis.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestState
import dev.schrammel.protego_parentis.sync.IncomingRequest
import dev.schrammel.protego_parentis.sync.NewApp

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
    private val preferences = NotificationPreferences(appContext)

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
            kind = NotificationKind.REQUEST_RECEIVED,
            title = appContext.getString(R.string.request_notification_title),
            body = appContext.resources.getQuantityString(
                R.plurals.request_notification_body,
                request.amount.inWholeMinutes.toInt(),
                request.amount.inWholeMinutes.toInt(),
                request.reason,
            ),
            destination = AppRegistry.notificationUx.openRequestInbox(NOTIFICATION_INCOMING),
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
            kind = NotificationKind.REQUEST_RECEIVED,
            title = appContext.getString(R.string.request_notification_title),
            body = appContext.resources.getQuantityString(
                R.plurals.request_notification_body,
                request.minutes,
                request.minutes,
                request.reason,
            ),
            destination = AppRegistry.notificationUx.openParentInbox(NOTIFICATION_INCOMING),
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
            kind = NotificationKind.NEW_APP,
            title = appContext.getString(R.string.new_app_notification_title),
            body = appContext.getString(R.string.new_app_notification_body, app.label),
            destination = AppRegistry.notificationUx.openParentDevice(NOTIFICATION_NEW_APP),
        )
    }

    /** The same news on the child's own device, where the review is PIN-gated. */
    fun notifyNewAppLocally(label: String) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_NEW_APP,
            kind = NotificationKind.NEW_APP,
            title = appContext.getString(R.string.new_app_notification_title),
            body = appContext.getString(R.string.new_app_notification_body, label),
            destination = AppRegistry.notificationUx.openAppControl(NOTIFICATION_NEW_APP),
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
            kind = NotificationKind.DEVICE_HEALTH,
            title = appContext.getString(R.string.integrity_notification_title),
            body = body,
            destination = AppRegistry.notificationUx.openParentDevice(NOTIFICATION_SILENT),
        )
    }

    /**
     * A parent locked this phone.
     *
     * Posted before the screen goes dark, so it is waiting on the lock screen. A
     * phone that locks itself with no explanation looks broken, and finding out
     * why should not require unlocking it again.
     */
    /**
     * A message arrived.
     *
     * The sender's name is shown; the message itself is the body. Both have been
     * through the sanitiser before reaching here — a notification is drawn by the
     * system, outside this app's own screens, so anything that could misrepresent
     * itself there is out of reach once it is posted.
     *
     * Skipped while the chat screen is already open — a message arriving while
     * somebody is already watching the conversation update live (#70) does not
     * need a banner telling them the same thing a second time.
     */
    fun notifyChat(sender: String, text: String) {
        if (AppRegistry.notificationUx.isChatVisible()) return
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_CHAT,
            kind = NotificationKind.CHAT_MESSAGE,
            title = sender,
            body = text,
            destination = AppRegistry.notificationUx.openChat(NOTIFICATION_CHAT),
        )
    }

    /**
     * Dismisses a chat notification left over from before this screen opened.
     *
     * Covers the case where a message arrived and was notified while the app
     * was elsewhere, and the conversation is only opened later — auto-cancel
     * already handles a tap on the notification itself, this handles reaching
     * the same screen any other way.
     */
    fun clearChatNotification() {
        notifications.cancel(NOTIFICATION_CHAT)
    }

    /**
     * Somebody wants in.
     *
     * Deliberately says nothing about which phone, beyond that there is one. The
     * name a device announces is chosen on that device, so repeating it in a
     * notification would let whoever is asking write the words a parent reads
     * while deciding. The fingerprint that actually identifies it is on the
     * screen this leads to, next to the phone it belongs to.
     */
    fun notifyDevicePending() {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_DEVICE_PENDING,
            kind = NotificationKind.DEVICE_PENDING,
            title = appContext.getString(R.string.family_pending_notification_title),
            body = appContext.getString(R.string.family_pending_notification_body),
            destination = AppRegistry.notificationUx.openFamilyDevices(NOTIFICATION_DEVICE_PENDING),
        )
    }

    /**
     * A device has been added to the family, on this phone.
     *
     * Fires the same way whether a person on this phone just tapped Approve or
     * this phone enrolled the device silently in the background because it
     * already trusted whoever vouched for it — see [Enrolment.shouldAccept] and
     * [dev.schrammel.protego_parentis.sync.SyncCoordinator.applyVouch]. That second
     * path has no screen open and nobody watching it happen, which is exactly why
     * this one is never optional: an addition nobody was told about is not
     * obvious just because it is visible if somebody goes looking.
     */
    fun notifyDeviceAdded(name: String) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_DEVICE_ADDED,
            kind = NotificationKind.DEVICE_ADDED,
            title = appContext.getString(R.string.family_added_notification_title),
            body = appContext.getString(R.string.family_added_notification_body, name),
            destination = AppRegistry.notificationUx.openFamilyDevices(NOTIFICATION_DEVICE_ADDED),
        )
    }

    /**
     * A device has been removed from the family, on this phone.
     *
     * Fires the same way whether a person here just tapped Revoke or this phone
     * removed the device because it already trusted whoever revoked it — the
     * mirror of [notifyDeviceAdded]. Never fires for the managed phone itself;
     * see [dev.schrammel.protego_parentis.sync.SyncReceiver] and
     * [Enrolment.shouldAcceptRevoke] for why that one stays a local action.
     */
    fun notifyDeviceRemoved(name: String) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_DEVICE_REMOVED,
            kind = NotificationKind.DEVICE_REMOVED,
            title = appContext.getString(R.string.family_removed_notification_title),
            body = appContext.getString(R.string.family_removed_notification_body, name),
            destination = AppRegistry.notificationUx.openFamilyDevices(NOTIFICATION_DEVICE_REMOVED),
        )
    }

    /**
     * This phone's family no longer exists.
     *
     * Where this reopens to differs by whether this phone acted on the disband
     * or only heard about it — see the two call sites in `SyncReceiver`, and
     * [NotificationUx.openFamilyDisbanded]. Either way this is the one place
     * the news reaches whoever is holding the phone.
     */
    fun notifyFamilyDisbanded() {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_FAMILY_DISBANDED,
            kind = NotificationKind.FAMILY_DISBANDED,
            title = appContext.getString(R.string.family_disbanded_notification_title),
            body = appContext.getString(R.string.family_disbanded_notification_body),
            destination = AppRegistry.notificationUx.openFamilyDisbanded(NOTIFICATION_FAMILY_DISBANDED),
        )
    }

    fun notifyLocked() {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_LOCKED,
            kind = NotificationKind.LOCKED,
            title = appContext.getString(R.string.locked_notification_title),
            body = appContext.getString(R.string.locked_notification_body),
            destination = AppRegistry.notificationUx.openAudit(NOTIFICATION_LOCKED),
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
            kind = NotificationKind.LOCATED,
            title = appContext.getString(R.string.located_notification_title),
            body = appContext.getString(R.string.located_notification_body),
            destination = AppRegistry.notificationUx.openAudit(NOTIFICATION_LOCATED),
        )
    }

    /** A standing rule answered a request, on the parent's phone. */
    fun notifyAutoApproved(ruleName: String) {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_AUTO_APPROVED,
            kind = NotificationKind.AUTO_APPROVED,
            title = appContext.getString(R.string.auto_notification_title),
            body = appContext.getString(R.string.auto_notification_body, ruleName),
            destination = AppRegistry.notificationUx.openParentInbox(NOTIFICATION_AUTO_APPROVED),
        )
    }

    /** The child arrived at, or left, a saved place. */
    fun notifyPlaceTransition(place: String, arrived: Boolean) {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_PLACE,
            kind = NotificationKind.PLACE_TRANSITION,
            title = appContext.getString(
                if (arrived) R.string.place_arrived_title else R.string.place_left_title,
            ),
            body = appContext.getString(
                if (arrived) R.string.place_arrived_body else R.string.place_left_body,
                place,
            ),
            destination = AppRegistry.notificationUx.openParentDevice(NOTIFICATION_PLACE),
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
            kind = NotificationKind.EMERGENCY,
            title = appContext.getString(R.string.sos_notification_title),
            body = appContext.getString(
                if (moved) R.string.sos_notification_moved else R.string.sos_notification_body,
            ),
            destination = AppRegistry.notificationUx.openParentDevice(NOTIFICATION_SOS),
            ongoing = true,
        )
    }

    fun notifySosCleared() {
        notifications.cancel(NOTIFICATION_SOS)
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_SOS_CLEARED,
            kind = NotificationKind.EMERGENCY,
            title = appContext.getString(R.string.sos_cleared_title),
            body = appContext.getString(R.string.sos_cleared_body),
            destination = AppRegistry.notificationUx.openParentDevice(NOTIFICATION_SOS_CLEARED),
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
            kind = NotificationKind.REQUEST_ANSWERED,
            title = appContext.getString(R.string.request_decision_title),
            body = withNote,
            destination = AppRegistry.notificationUx.openRequests(NOTIFICATION_DECISION),
        )
    }

    private fun post(
        channel: String,
        id: Int,
        kind: NotificationKind,
        title: String,
        body: String,
        destination: PendingIntent,
        ongoing: Boolean = false,
    ) {
        // One gate, on the way out, rather than a check at each of eleven call
        // sites — the one that gets forgotten would be a notification a family
        // switched off and still received.
        if (!preferences.isEnabled(kind)) return

        notifications.notify(
            id,
            NotificationCompat.Builder(appContext, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setContentIntent(destination)
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
        const val NOTIFICATION_CHAT = 13
        const val NOTIFICATION_DEVICE_PENDING = 14
        const val NOTIFICATION_DEVICE_ADDED = 15
        const val NOTIFICATION_DEVICE_REMOVED = 16
        const val NOTIFICATION_FAMILY_DISBANDED = 17
    }
}
