package dev.schrammel.dementor_watch.sync

import java.time.Instant

/** What a message is for. Unknown values are ignored rather than guessed at. */
enum class SyncMessageType {
    /** Child → parents: someone is asking for more time. */
    REQUEST_RAISED,

    /** Parent → child: a request was answered. */
    REQUEST_DECIDED,

    /** Parent → child: extra time, granted without being asked. */
    BONUS_GRANTED,

    /** Child → parents: still alive, here is the state of things. */
    HEARTBEAT,

    /** Child → parents: an app appeared on this phone that was not here before. */
    APP_INSTALLED,

    /** Parent → child: that new app is fine, or it is not. */
    APP_DECIDED,

    /** Child → parents: today's totals, for the parent's one-glance view (RPT-1). */
    DAILY_SUMMARY,

    /**
     * Child → parents: this phone is switching off (LOC-4).
     *
     * Best effort. A phone being shut down has seconds and may lose the network
     * first, so its absence means nothing; its arrival is what distinguishes
     * "switched off" from "lost signal".
     */
    DEVICE_STOPPING,

    /** Parent → child: lock the screen now. */
    LOCK_NOW,

    /**
     * Child → parents: a standing rule answered a request, so nobody needs to.
     *
     * Its own type rather than REQUEST_DECIDED, which only a parent may send —
     * relaxing that would hand a compromised child device the power to forge every
     * parent decision, at a saving of one enum entry.
     */
    REQUEST_AUTO_APPROVED,

    /** Parent → child: where are you? Always announced to the child. */
    LOCATE_REQUEST,

    /** Child → parents: here, once. Never a stream. */
    LOCATION_REPORT,

    /** Child → parents: arrived at, or left, a named place. Carries no position. */
    PLACE_TRANSITION,

    /** Child → parents: help. */
    SOS_RAISED,

    /** Child → parents: the emergency is over. */
    SOS_CLEARED,

    /** Child → parents: today's domains, counted and named, never timed. */
    WEB_SUMMARY,

    /**
     * Parent → everyone: I have let this device in.
     *
     * The whole enrolment model rests on this one message. A parent approving a
     * new phone tells the rest of the family, and everyone who already trusts the
     * voucher adds it. The child never approves anybody — its roster is written
     * by the parents, which is the right way round: deciding who may set the rules
     * is not a job for the person the rules are about.
     */
    DEVICE_VOUCHED,

    /**
     * Either → everyone: this is who I am.
     *
     * Carries a name and a public key so the other phones can offer to enrol it.
     * The only message type whose sender is, by definition, not yet trusted.
     */
    DEVICE_ANNOUNCE,
}

/**
 * One decrypted message.
 *
 * [sentAt] and [id] are not decoration: on a public topic anyone can capture a
 * message and publish it again later. Together they let the receiver reject a
 * replay — see [ReplayGuard].
 *
 * [fields] is deliberately a flat string map. The devices in a family will not
 * all be on the same build, and a strict schema would mean an older phone
 * refusing a message it could have partly understood.
 */
data class SyncMessage(
    val id: String,
    val type: SyncMessageType,
    /**
     * The sender's device id — the fingerprint of [senderKey].
     *
     * Not a name and not a choice: a device that claims an id whose fingerprint
     * does not match the key it signed with is refused. See [RosterRules].
     */
    val sender: String,
    val sentAt: Instant,
    val fields: Map<String, String> = emptyMap(),
    /** SPKI public key, base64. Absent only on messages from a build before signing. */
    val senderKey: String? = null,
    /** What the sender says it is. Believed only while enrolling it. */
    val senderRole: SyncRole? = null,
    /** What the sender calls itself, for showing in a list of family devices. */
    val senderName: String? = null,
) {
    fun field(name: String): String? = fields[name]?.takeIf(String::isNotBlank)

    fun longField(name: String): Long? = field(name)?.toLongOrNull()
}
