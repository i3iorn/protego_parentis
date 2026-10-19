package dev.schrammel.protego_parentis.enforcement

/**
 * Every [android.app.NotificationManager] id this app posts under, in one place.
 *
 * NotificationManager replaces by id, so two classes independently choosing the
 * same number silently overwrite each other's notification. That happened
 * three times: a goal-earned notification (5) replaced a new-app review, the
 * realtime service's foreground notification (6) was replaceable by a silent
 * family-device update, and goal-earned moved to 22, which the update-available
 * notice already held, because RequestNotifier's ids were kept out of this
 * registry. So every id is here, and NotificationIdsTest checks all of them
 * are distinct.
 *
 * Never renumber an existing id — a notification already on screen from the
 * previous version keeps its number. A new poster takes the next free number
 * above the highest in use.
 */
object NotificationIds {

    /** The enforcement service's permanent, non-dismissible foreground notification. */
    const val ENFORCEMENT_SERVICE = 1

    /** Screen-time warning ("x minutes left"). */
    const val WARNING = 2

    // 3-22: RequestNotifier's — requests, decisions, new apps, SOS, place
    // transitions, chat, device enrolment, family events, updates. A tap on
    // one opens the screen it names, so each has its own fixed number.
    const val INCOMING = 3
    const val DECISION = 4
    const val NEW_APP = 5
    const val SILENT = 6
    const val LOCKED = 7
    const val LOCATED = 8
    const val AUTO_APPROVED = 9
    const val PLACE = 10
    const val SOS = 11
    const val SOS_CLEARED = 12
    const val CHAT = 13
    const val DEVICE_PENDING = 14
    const val DEVICE_ADDED = 15
    const val DEVICE_REMOVED = 16
    const val FAMILY_DISBANDED = 17
    const val BONUS = 18
    const val RULE_CHANGED = 19
    const val APP_DECIDED = 20
    const val AUDIT_TAMPER = 21
    const val UPDATE_AVAILABLE = 22

    /** Realtime-stream foreground notification (the stream's own keep-alive). */
    const val REALTIME_SERVICE = 23

    /** The earned-prize celebration, posted on the next unlock. */
    const val GOAL_EARNED = 24

    /** A restriction just began — budget exhausted, a rule applying, bedtime. */
    const val BLOCK_STARTED = 25
}
