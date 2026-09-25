package dev.schrammel.protego_parentis.enforcement

/**
 * Every [android.app.NotificationManager] id this app posts under, in one place.
 *
 * NotificationManager replaces by id, so two classes independently choosing the
 * same number silently overwrite each other's notification — and this happened
 * twice before this object existed: a goal-earned notification (5) replaced a
 * new-app review, and the realtime service's foreground notification (6) was
 * replaceable by a silent family-device update. The registry exists so the
 * next collision needs someone to actively write over a number somebody else
 * already owns, rather than nobody noticing.
 *
 * Rules:
 *  - 1 is the enforcement service's permanent foreground notification.
 *  - 2 and the 3-21 range are historical and keep their existing meaning on
 *    upgraded devices — never renumber within a range, only append.
 *  - A new poster takes the next free number above the highest in use, and
 *    adds itself here.
 */
object NotificationIds {

    /** The enforcement service's permanent, non-dismissible foreground notification. */
    const val ENFORCEMENT_SERVICE = 1

    /** Screen-time warning ("x minutes left"). */
    const val WARNING = 2

    /** Realtime-stream foreground notification (the stream's own keep-alive). */
    const val REALTIME_SERVICE = 23

    /** The earned-prize celebration, posted on the next unlock. */
    const val GOAL_EARNED = 22

    // 3-21: owned by RequestNotifier — requests, decisions, new apps, SOS,
    // place transitions, chat, device enrolment, family events. Requested by
    // number there so a tap can open the exact screen the notification names;
    // the full list lives in RequestNotifier's companion.
}
