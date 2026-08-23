package dev.schrammel.dementor_watch.model

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
    BONUS_GRANTED(ValueKind.MINUTES),
    BONUS_CLEARED(ValueKind.NONE),

    // ---------- the device itself ----------
    PIN_CHANGED(ValueKind.NONE),
    SYNC_CONFIGURED(ValueKind.FLAG),
    DEVICE_APPROVED(ValueKind.TOKEN),
    DEVICE_REVOKED(ValueKind.NONE),
    INSTALL_POLICY_CHANGED(ValueKind.TOKEN),
    INSTALL_WINDOW_OPENED(ValueKind.MINUTES),
    TAMPER_PROTECTION_CHANGED(ValueKind.FLAG),
    DEVICE_RELEASED(ValueKind.NONE),
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
)
