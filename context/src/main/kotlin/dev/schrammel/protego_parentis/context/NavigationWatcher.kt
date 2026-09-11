package dev.schrammel.protego_parentis.context

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.schrammel.protego_parentis.model.PackageName
import java.util.concurrent.ConcurrentHashMap

/**
 * Which app, if any, is running a live navigation session.
 *
 * There is no platform API that answers this. What there is, is the ongoing
 * notification a navigation app posts for the duration of a route, tagged
 * [Notification.CATEGORY_NAVIGATION]. Listening for it is the honest signal: it
 * appears when the route starts and goes when it ends, and the app posting it
 * says so itself rather than us inferring it from what happens to be on screen.
 *
 * Only the posting package, the category and the ongoing flag are ever read. No
 * title, no text, no sender — the listener sees every notification on the phone,
 * so what it declines to look at matters as much as what it does.
 */
class NavigationWatcher : NotificationListenerService() {

    override fun onListenerConnected() {
        // The listener can connect long after a route started, so the state is
        // rebuilt from what is on screen rather than assumed empty.
        NavigationSessions.reset()
        activeNotifications?.forEach(::track)
    }

    override fun onListenerDisconnected() {
        // Without a connection we know nothing, and knowing nothing has to mean
        // "not navigating" rather than whatever we last saw.
        NavigationSessions.reset()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = track(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NavigationSessions.removed(key(sbn))
    }

    private fun track(sbn: StatusBarNotification) {
        val notification = sbn.notification
        NavigationSessions.posted(
            key = key(sbn),
            pkg = sbn.packageName,
            category = notification?.category,
            ongoing = notification != null &&
                (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )
    }

    private fun key(sbn: StatusBarNotification) = sbn.key ?: "${sbn.packageName}/${sbn.id}"

    companion object {
        /** The app navigating right now, or null. */
        fun current(): PackageName? = NavigationSessions.current()

        /**
         * Notification access is a special access, like usage access: there is no
         * dialog, and not even a device owner can grant it to itself. Without it
         * the watcher never connects and [current] is always null, which is the
         * safe direction — every rule then applies exactly as written.
         */
        fun isGranted(context: Context): Boolean {
            val component = ComponentName(context, NavigationWatcher::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
        }

        /** Opens the system screen where the user turns notification access on. */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}

/**
 * The bookkeeping behind [NavigationWatcher], kept free of Android types so the
 * rule about what counts as navigating can be tested without a device.
 *
 * State lives in memory rather than in a file. It is a fact about right now,
 * worth nothing after a reboot, and a stale "still navigating" read back from
 * disk would hold a suspension open indefinitely.
 */
internal object NavigationSessions {

    private val routes = ConcurrentHashMap<String, String>()

    fun reset() = routes.clear()

    fun posted(key: String, pkg: String, category: String?, ongoing: Boolean) {
        // Ongoing matters as much as the category: a "heavy traffic ahead" alert
        // is CATEGORY_NAVIGATION too, and dismissing it does not end a route that
        // was never running.
        if (category == Notification.CATEGORY_NAVIGATION && ongoing) {
            routes[key] = pkg
        } else {
            // An update that drops the ongoing flag is how a route ends without
            // the notification ever being removed.
            routes.remove(key)
        }
    }

    fun removed(key: String) {
        routes.remove(key)
    }

    /**
     * When two apps are somehow navigating at once we spare neither, rather than
     * guessing. Sparing the wrong one is worse than sparing nothing: the map
     * actually being followed still closes, and an unrelated app gets a free pass.
     */
    fun current(): PackageName? = routes.values.distinct().singleOrNull()?.let(::PackageName)
}
