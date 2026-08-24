package dev.schrammel.dementor_watch.enforcement

import android.content.Context
import androidx.core.content.edit

/**
 * A kind of notification this app can send, and whether it may be silenced.
 *
 * Most can. Four cannot, and the reason is the same in every case: this is an app
 * that watches a person, and the things it tells that person about being watched
 * are not a preference. A locate a child is not told about is surveillance; a
 * phone that locks itself with no explanation looks broken; an emergency nobody
 * hears is the feature failing at the only moment it matters. Offering a switch
 * for those would be offering to make the product something it says it is not.
 *
 * They are listed on the settings screen anyway, shown as always-on with the
 * reason. A setting that is absent looks like an oversight; one that is present
 * and explained looks like a decision, which it is.
 */
enum class NotificationKind(
    /** False when this is part of what makes the product honest. */
    val optional: Boolean,
) {
    // ---------- on the managed phone ----------

    /** Ten and five minutes left. */
    TIME_WARNING(optional = true),

    /** A request was answered, one way or the other. */
    REQUEST_ANSWERED(optional = true),

    /** A goal was met and there is a prize waiting. */
    GOAL_EARNED(optional = true),

    /** A parent asked where this phone is. Never silenced — see the class note. */
    LOCATED(optional = false),

    /** A parent locked this phone. Never silenced. */
    LOCKED(optional = false),

    // ---------- on a parent's phone ----------

    /** Somebody is asking for more time. */
    REQUEST_RECEIVED(optional = true),

    /** A standing rule answered instead of a person. */
    AUTO_APPROVED(optional = true),

    /** An app appeared that nobody has reviewed. */
    NEW_APP(optional = true),

    /** The other phone has gone quiet, or is nearly out of battery. */
    DEVICE_HEALTH(optional = true),

    /** Arrived at, or left, a saved place. */
    PLACE_TRANSITION(optional = true),

    /** An emergency. Never silenced. */
    EMERGENCY(optional = false),

    // ---------- on either phone ----------

    /**
     * Somebody in the family said something.
     *
     * Silenceable, and on both sides. A message is not something being done to
     * anyone — it is the one part of this app that is just two people talking, so
     * the ordinary rules for a chat apply rather than the ones about monitoring.
     */
    CHAT_MESSAGE(optional = true),
}

/**
 * Which notifications this phone sends.
 *
 * A companion to Android's own per-channel controls rather than a replacement:
 * the system settings can silence a channel and this app cannot stop that. What
 * this adds is finer grain — several kinds share a channel — and somewhere to say
 * plainly which ones the app will not offer to silence, and why.
 *
 * Everything optional defaults to on. A family that has just set this up should
 * hear about a request; the ones who want quiet will come looking for this screen,
 * and the ones who never do should not miss the thing they set the app up for.
 */
class NotificationPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(kind: NotificationKind): Boolean {
        if (!kind.optional) return true
        return prefs.getBoolean(kind.name, true)
    }

    /**
     * Silences or restores a kind.
     *
     * Refuses on the four that may not be silenced, rather than storing a
     * preference that the read path then ignores. A stored value nothing honours
     * is a bug waiting for somebody to trust it.
     */
    fun setEnabled(kind: NotificationKind, enabled: Boolean) {
        if (!kind.optional) return
        prefs.edit { putBoolean(kind.name, enabled) }
    }

    private companion object {
        const val FILE_NAME = "notifications"
    }
}
