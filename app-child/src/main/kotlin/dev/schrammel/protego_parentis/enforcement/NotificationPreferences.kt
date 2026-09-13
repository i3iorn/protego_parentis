package dev.schrammel.protego_parentis.enforcement

import android.content.Context
import androidx.core.content.edit

/**
 * A kind of notification this app can send, and whether it may be silenced.
 *
 * Most can. Seven cannot, and the reason is almost the same in every case: this is
 * an app that watches a person, and the things it tells that person about being
 * watched are not a preference. A locate a child is not told about is
 * surveillance; a phone that locks itself with no explanation looks broken; an
 * emergency nobody hears is the feature failing at the only moment it matters.
 * Offering a switch for those would be offering to make the product something it
 * says it is not. A device joining or leaving the family, or the family itself
 * ending, are the odd ones out in *why* they cannot be silenced rather than
 * *whether*: each is a trust boundary changing, on a phone that might not be the
 * one anyone was looking at when it happened.
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

    /** A request lapsed with nobody answering it in time. */
    REQUEST_EXPIRED(optional = true),

    /** A parent granted extra time outright, without being asked. */
    BONUS_GRANTED(optional = true),

    /** A goal was met and there is a prize waiting. */
    GOAL_EARNED(optional = true),

    /** A parent asked where this phone is. Never silenced — see the class note. */
    LOCATED(optional = false),

    /** A parent locked this phone. Never silenced. */
    LOCKED(optional = false),

    /** A rule changed because another device edited it remotely. */
    RULE_CHANGED(optional = true),

    /** A newly-installed app was allowed or blocked by a parent. */
    APP_DECIDED(optional = true),

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

    /**
     * Evidence that a device's shared history was rewritten.
     *
     * Never silenced, for the same reason as the other six: this is exactly
     * the kind of thing a family member with something to hide would most
     * want gone, which is the one thing that settles whether it may be.
     */
    AUDIT_TAMPER(optional = false),

    // ---------- on either phone ----------

    /**
     * A phone is waiting to be let into the family.
     *
     * On both sides, because both sides approve: a parent approves the phone with
     * the limits, and the person setting that phone up approves the founding
     * parent. Without this the pending list sat on a settings screen nobody had
     * been sent to, and the family looked broken instead of unfinished.
     */
    DEVICE_PENDING(optional = true),

    /**
     * A device has been added to the family, however that happened.
     *
     * On both sides, and never silenced. Most of the time this fires with nobody
     * looking — a device already trusted by one parent is enrolled on every other
     * phone automatically, in the background, the moment it next hears about it.
     * That is the whole point of not asking everyone to approve separately, but it
     * also means the addition would otherwise happen with no one told at all. A
     * family finding out who is in it only by opening a settings screen and
     * counting is not "very obvious"; a notification naming the device is.
     */
    DEVICE_ADDED(optional = false),

    /**
     * A device has been removed from the family, however that happened.
     *
     * The mirror of [DEVICE_ADDED], never silenced for the same reason: a
     * parent's phone can revoke another parent's device, and every other phone
     * that already trusted the revoker removes it too, automatically, in the
     * background. Nobody should find out their phone lost family membership
     * only by noticing something stopped working.
     */
    DEVICE_REMOVED(optional = false),

    /**
     * The family this phone was part of no longer exists.
     *
     * Never silenced, and on both sides — but what it means differs by side.
     * A parent phone acts on this by leaving on its own; the managed phone
     * does not act on it at all, and this notification is the only way
     * whoever is holding it finds out there is now a loose end to close by
     * hand.
     */
    FAMILY_DISBANDED(optional = false),

    /**
     * Somebody in the family said something.
     *
     * Silenceable, and on both sides. A message is not something being done to
     * anyone — it is the one part of this app that is just two people talking, so
     * the ordinary rules for a chat apply rather than the ones about monitoring.
     */
    CHAT_MESSAGE(optional = true),

    /**
     * A newer release exists and this phone could not apply it silently.
     *
     * Only ever fires on a phone that is not device owner — the managed
     * phone's own update is silent and unattended by design (see
     * `docs/RELEASING.md`), so there is nothing here to tell it about; a
     * parent's phone has no such privilege and needs a tap to finish.
     */
    UPDATE_AVAILABLE(optional = true),
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
