package dev.schrammel.dementor_watch.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.ui.FamilyDevicesActivity
import dev.schrammel.dementor_watch.ui.ChatActivity
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
            kind = NotificationKind.REQUEST_RECEIVED,
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
            kind = NotificationKind.NEW_APP,
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
            kind = NotificationKind.NEW_APP,
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
            kind = NotificationKind.DEVICE_HEALTH,
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
    /**
     * A message arrived.
     *
     * The sender's name is shown; the message itself is the body. Both have been
     * through the sanitiser before reaching here — a notification is drawn by the
     * system, outside this app's own screens, so anything that could misrepresent
     * itself there is out of reach once it is posted.
     */
    fun notifyChat(sender: String, text: String) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_CHAT,
            kind = NotificationKind.CHAT_MESSAGE,
            title = sender,
            body = text,
            destination = Intent(appContext, ChatActivity::class.java),
        )
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
            destination = FamilyDevicesActivity.intent(appContext),
        )
    }

    /**
     * A device has been added to the family, on this phone.
     *
     * Fires the same way whether a person on this phone just tapped Approve or
     * this phone enrolled the device silently in the background because it
     * already trusted whoever vouched for it — see [Enrolment.shouldAccept] and
     * [dev.schrammel.dementor_watch.sync.SyncCoordinator.applyVouch]. That second
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
            destination = FamilyDevicesActivity.intent(appContext),
        )
    }

    /**
     * A device has been removed from the family, on this phone.
     *
     * Fires the same way whether a person here just tapped Revoke or this phone
     * removed the device because it already trusted whoever revoked it — the
     * mirror of [notifyDeviceAdded]. Never fires for the managed phone itself;
     * see [dev.schrammel.dementor_watch.sync.SyncReceiver] and
     * [Enrolment.shouldAcceptRevoke] for why that one stays a local action.
     */
    fun notifyDeviceRemoved(name: String) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_DEVICE_REMOVED,
            kind = NotificationKind.DEVICE_REMOVED,
            title = appContext.getString(R.string.family_removed_notification_title),
            body = appContext.getString(R.string.family_removed_notification_body, name),
            destination = FamilyDevicesActivity.intent(appContext),
        )
    }

    /**
     * This phone's family no longer exists.
     *
     * The destination differs by whether this phone acted on the disband or
     * only heard about it — see the two call sites in `SyncReceiver`. Either
     * way this is the one place the news reaches whoever is holding the phone.
     */
    fun notifyFamilyDisbanded(destination: Intent) {
        post(
            channel = CHANNEL_INCOMING,
            id = NOTIFICATION_FAMILY_DISBANDED,
            kind = NotificationKind.FAMILY_DISBANDED,
            title = appContext.getString(R.string.family_disbanded_notification_title),
            body = appContext.getString(R.string.family_disbanded_notification_body),
            destination = destination,
        )
    }

    fun notifyLocked() {
        post(
            channel = CHANNEL_DECISION,
            id = NOTIFICATION_LOCKED,
            kind = NotificationKind.LOCKED,
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
            kind = NotificationKind.LOCATED,
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
            kind = NotificationKind.AUTO_APPROVED,
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
            kind = NotificationKind.PLACE_TRANSITION,
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
            kind = NotificationKind.EMERGENCY,
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
            kind = NotificationKind.EMERGENCY,
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
            kind = NotificationKind.REQUEST_ANSWERED,
            title = appContext.getString(R.string.request_decision_title),
            body = withNote,
            destination = RequestsActivity.intent(appContext),
        )
    }

    private fun post(
        channel: String,
        id: Int,
        kind: NotificationKind,
        title: String,
        body: String,
        destination: Intent,
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
                .setContentIntent(openFor(destination, id))
                .build(),
        )
    }

    /**
     * Opens [destination] with the screens above it already on the stack.
     *
     * Built from `parentActivityName` in the manifest, so a notification about a
     * request opens the inbox with the home screen underneath it — and back goes
     * home rather than out of the app. That is what someone expects, and it is
     * what happens when they reach the same screen by tapping through.
     *
     * Falls back to the plain intent if the stack cannot be built, because a
     * notification that opens the right screen with the wrong back behaviour is
     * still far better than one that does not open.
     */
    private fun openFor(destination: Intent, id: Int): PendingIntent? {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        return runCatching {
            TaskStackBuilder.create(appContext)
                .addNextIntentWithParentStack(destination)
                .getPendingIntent(id, flags)
        }.getOrNull() ?: PendingIntent.getActivity(
            appContext,
            id,
            destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
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
