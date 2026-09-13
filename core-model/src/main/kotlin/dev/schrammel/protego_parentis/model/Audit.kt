package dev.schrammel.protego_parentis.model

import java.time.Instant

/** Who did the thing. */
enum class AuditActor {
    /** Someone holding the parent PIN, or a paired parent phone. */
    PARENT,

    /** The child, from an ungated screen. */
    CHILD,

    /** The app itself, with nobody at the keyboard. */
    SYSTEM,
}

/**
 * How to read an entry's [AuditEntry.before] and [AuditEntry.after].
 *
 * Values are stored in a locale-independent form and rendered at display time.
 * Storing "45 min" would mean an entry written in English staying English after
 * the family switches the app to Swedish, which is exactly the kind of small
 * wrongness that makes a log feel untrustworthy.
 */
enum class ValueKind {
    /** No value; the action alone says everything. */
    NONE,

    /** Whole minutes, as a decimal string. Zero means "no limit". */
    MINUTES,

    /** Two ISO local times joined by a hyphen, e.g. `22:30-07:00`. */
    TIME_RANGE,

    /** `true` or `false`. */
    FLAG,

    /** A short opaque token, such as a role name. Never free text. */
    TOKEN,
}

/**
 * The things worth recording in the shared log.
 *
 * Deliberately a closed set, for the same reason the diagnostic events are: a log
 * assembled from format strings drifts until nobody can say what it might contain.
 */
enum class AuditAction(val valueKind: ValueKind) {

    // ---------- limits ----------
    DAILY_LIMIT_CHANGED(ValueKind.MINUTES),

    /** A parent's phone replaced every rule at once, rather than one at a time. */
    RULES_REPLACED(ValueKind.NONE),
    APP_LIMIT_CHANGED(ValueKind.MINUTES),
    APP_EXEMPTION_CHANGED(ValueKind.FLAG),
    APP_BLOCK_CHANGED(ValueKind.FLAG),
    APP_REVIEWED(ValueKind.NONE),
    CATEGORY_SAVED(ValueKind.MINUTES),
    CATEGORY_DELETED(ValueKind.NONE),

    // ---------- the shape of the day ----------
    BEDTIME_CHANGED(ValueKind.TIME_RANGE),
    BEDTIME_ENABLED(ValueKind.FLAG),
    CONTEXT_RULE_SAVED(ValueKind.NONE),
    CONTEXT_RULE_DELETED(ValueKind.NONE),
    SCHEDULE_SAVED(ValueKind.TIME_RANGE),
    SCHEDULE_DELETED(ValueKind.NONE),

    /**
     * The target changed. The prize text is deliberately never recorded — it is
     * meant to be a surprise, and a log that spoils it would not be read twice.
     */
    GOAL_CHANGED(ValueKind.MINUTES),
    GOAL_PRIZE_CHANGED(ValueKind.NONE),

    // ---------- the negotiation ----------
    REQUEST_RAISED(ValueKind.MINUTES),
    REQUEST_APPROVED(ValueKind.MINUTES),
    REQUEST_DENIED(ValueKind.MINUTES),
    REQUEST_WITHDRAWN(ValueKind.MINUTES),

    /**
     * Nobody answered before the deadline. Recorded with [AuditActor.SYSTEM]
     * for the same reason as [REQUEST_AUTO_APPROVED]: a lapse is something
     * that happened, not something a parent or child did.
     */
    REQUEST_EXPIRED(ValueKind.MINUTES),
    BONUS_GRANTED(ValueKind.MINUTES),
    BONUS_CLEARED(ValueKind.NONE),

    /**
     * A standing rule answered instead of a person.
     *
     * Recorded with [AuditActor.SYSTEM], because recording it as a parent would be
     * a lie in the one log this product promises is honest.
     */
    REQUEST_AUTO_APPROVED(ValueKind.MINUTES),
    AUTO_RULE_SAVED(ValueKind.MINUTES),
    AUTO_RULE_DELETED(ValueKind.NONE),

    // ---------- the device itself ----------
    PIN_CHANGED(ValueKind.NONE),
    BIOMETRIC_UNLOCK_CHANGED(ValueKind.FLAG),
    SYNC_CONFIGURED(ValueKind.FLAG),
    SYNC_BACKEND_CHANGED(ValueKind.TOKEN),
    FEATURE_CHANGED(ValueKind.FLAG),
    WEB_FILTER_CHANGED(ValueKind.TOKEN),
    BROWSER_POLICY_CHANGED(ValueKind.FLAG),
    DEVICE_APPROVED(ValueKind.TOKEN),
    DEVICE_REVOKED(ValueKind.NONE),
    FAMILY_DISBANDED(ValueKind.NONE),
    INSTALL_POLICY_CHANGED(ValueKind.TOKEN),
    INSTALL_WINDOW_OPENED(ValueKind.MINUTES),
    TAMPER_PROTECTION_CHANGED(ValueKind.FLAG),
    APP_NETWORK_CHANGED(ValueKind.FLAG),
    WEB_LOGGING_CHANGED(ValueKind.FLAG),

    // ---------- where the phone is ----------

    /** A parent asked where the phone was. Recorded whether or not it answered. */
    LOCATE_REQUESTED(ValueKind.NONE),

    /** The phone answered with a position. */
    LOCATION_SHARED(ValueKind.NONE),
    PLACE_ENTERED(ValueKind.NONE),
    PLACE_LEFT(ValueKind.NONE),

    /** A parent named a place from coordinates typed remotely, not stood in. */
    PLACE_ADDED(ValueKind.NONE),
    SOS_RAISED(ValueKind.NONE),
    SOS_CLEARED(ValueKind.NONE),
    DEVICE_RELEASED(ValueKind.NONE),
    DEVICE_LOCKED(ValueKind.NONE),
    ROLE_CHANGED(ValueKind.TOKEN),
}

/**
 * One line of the shared history (TR-3).
 *
 * The log is append-only and identical on both sides of the family. That is the
 * product promise: a child can see every rule change and every answer, with who
 * made it and when, and nothing in it is parent-only.
 *
 * Note the difference from the diagnostic log, which is content-free and safe to
 * hand to a stranger. **This log is not.** [subject] carries app labels, category
 * names and rule names, because a history that will not say *which* app was
 * limited is not a history. It is for the family, not for a support ticket.
 */
data class AuditEntry(
    val id: String,
    val at: Instant,
    val actor: AuditActor,
    val action: AuditAction,
    /** What the action was about: an app label, a category, a rule name. */
    val subject: String? = null,
    val before: String? = null,
    val after: String? = null,
    /**
     * The hash of the entry before this one, and this one's own.
     *
     * Together they chain the history, so an entry edited or removed outside the
     * app breaks every link after it and the screen can say so. See [AuditChain].
     *
     * Defaulted so callers building an entry do not have to think about it; the
     * recorder fills both in before anything is stored.
     */
    val previousHash: String = AuditChain.GENESIS,
    val hash: String = "",
    /**
     * A keyed signature over [hash], from a key this device generated and
     * cannot export (#84) — see `AuditSignature` in app-child, the one thing
     * [AuditChain] cannot do itself since it is pure Kotlin with no keystore
     * to reach. Null on every entry recorded before this existed, and on any
     * entry from a device whose keystore refused; neither is treated as
     * tampering, only as unsigned.
     */
    val sig: String? = null,
)
