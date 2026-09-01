package dev.schrammel.protego_parentis.sync

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
     * Parent → child: change the daily allowance.
     *
     * The one rule edit that can be made from a phone other than the child's own,
     * because it is the rule the product exists for and the one most likely to
     * need changing in the moment rather than the next time somebody is standing
     * at the managed phone.
     */
    SET_DAILY_LIMIT,

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
     * Either → everyone: a message somebody typed.
     *
     * The only type whose payload is words a person wrote rather than a number or
     * a decision. It is carried, stored and shown as text and nothing else — see
     * [dev.schrammel.protego_parentis.model.ChatText] for what that is worth and
     * what it deliberately refuses.
     */
    CHAT_MESSAGE,

    /**
     * Either → everyone: this is who I am.
     *
     * Carries a name and a public key so the other phones can offer to enrol it.
     * The only message type whose sender is, by definition, not yet trusted.
     */
    DEVICE_ANNOUNCE,

    /**
     * Parent → everyone: I have removed this device.
     *
     * The mirror of [DEVICE_VOUCHED], and refused under the same rule vouching
     * already refuses under: never for the managed phone. Revoking a parent
     * device propagates everywhere at once, the same way approving one does —
     * revoking the managed phone stays a local, physical, PIN-gated action on
     * that phone, whatever any remote parent's phone says.
     */
    DEVICE_REVOKED,

    /**
     * Parent → everyone: this family is over.
     *
     * Every other parent phone leaves automatically on receiving one, the same
     * "one parent's word is enough" trust every other family-wide action here
     * already runs on. The managed phone does not: it is told, so whoever is
     * holding it knows what happened, but it does not un-enrol itself — that is
     * the same local, physical, PIN-gated step [DEVICE_REVOKED] insists on.
     */
    FAMILY_DISBANDED,

    /**
     * Either → everyone: a batch of replicated-state writes.
     *
     * The workhorse of the current design, and what replaced asking one phone
     * for its whole policy. Carries only the registers that actually changed —
     * see [StateOp] — so a limit being nudged is a message of one line rather
     * than every rule the family has.
     *
     * Both roles send these, because every device is a replica of the same
     * state: an edit made standing at the managed phone reaches the parents the
     * same way a parent's edit reaches it. What that does *not* relax is who may
     * be believed about what — see [ReplicationRules].
     */
    STATE_OPS,

    /**
     * Either → everyone: a summary of everything I hold.
     *
     * Small and fixed-size regardless of how much state there is. Published on
     * a slow timer so a replica that missed ops entirely — off for longer than
     * the relay keeps them — finds that out on its own rather than staying
     * quietly wrong. See [StateDigest].
     */
    STATE_DIGEST,

    /**
     * Either → everyone: send me what you have in these buckets.
     *
     * The answer arrives as ordinary [STATE_OPS]. Only the buckets that actually
     * disagreed are asked for, so recovering from a weekend offline costs a
     * fraction of the state rather than all of it.
     */
    STATE_REPAIR,

    /**
     * Parent → child: send your current rules.
     *
     * **Superseded by [STATE_OPS].** Kept so that a phone still on the older
     * build can be understood rather than refused outright: a family does not
     * update every handset at once, and an unrecognised type is dropped
     * silently, which would look like sync simply not working.
     */
    POLICY_REQUESTED,

    /**
     * Child → parents: here is every rule this phone currently enforces.
     *
     * Sent in reply to [POLICY_REQUESTED], and only then — unlike a heartbeat,
     * nobody needs this the moment something changes, only when a parent has
     * actually opened the rules screen to look.
     */
    POLICY_SNAPSHOT,

    /**
     * Parent → child: replace the rules with these.
     *
     * Carries the version the parent's copy was based on, so the child can
     * refuse rather than silently discard an edit made locally in the meantime
     * — see where this is applied for the exact check.
     */
    POLICY_UPDATED,

    /** Parent → child: send the list of apps on this phone. */
    APP_INVENTORY_REQUESTED,

    /**
     * Child → parents: every launchable app on this phone, by name.
     *
     * No icons and no usage — a parent phone needs this list only to choose
     * which app a rule is about, the same information already shown once an app
     * is installed and reported through [APP_INSTALLED].
     */
    APP_INVENTORY,
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
