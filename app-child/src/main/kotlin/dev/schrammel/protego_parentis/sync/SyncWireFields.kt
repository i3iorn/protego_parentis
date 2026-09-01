package dev.schrammel.protego_parentis.sync

/**
 * Field names and bounds for [SyncMessage.fields], shared by [SyncPublisher]
 * (which writes them) and [SyncReceiver] (which reads them back).
 *
 * Kept as plain string/int constants rather than a schema: the devices in a
 * family will not all be on the same build, and a strict schema would mean an
 * older phone refusing a message it could have partly understood.
 */
internal object SyncWireFields {
    const val FIELD_REQUEST_ID = "requestId"
    const val FIELD_MINUTES = "minutes"
    const val FIELD_REASON = "reason"
    const val FIELD_TARGET = "target"
    const val FIELD_TARGET_LABEL = "targetLabel"
    const val FIELD_EXPIRES_AT = "expiresAt"
    const val FIELD_STATE = "state"
    const val FIELD_NOTE = "note"
    const val FIELD_PACKAGE = "package"
    const val FIELD_BLOCKED = "blocked"
    const val FIELD_POLICY_VERSION = "policyVersion"
    const val FIELD_BATTERY = "battery"
    const val FIELD_MANAGED = "managed"
    const val FIELD_USAGE_ACCESS = "usageAccess"
    const val FIELD_SUSPENDED = "suspended"
    const val FIELD_HISTORY_HEAD = "historyHead"
    const val FIELD_HISTORY_COUNT = "historyCount"
    const val FIELD_USED = "used"
    const val FIELD_ALLOWED = "allowed"
    const val FIELD_BONUS = "bonus"
    const val FIELD_REMAINING = "remaining"
    const val FIELD_LEVEL = "level"
    const val FIELD_APP_PREFIX = "app"
    const val MAX_SUMMARY_APPS = 10

    const val FIELD_RULE_NAME = "ruleName"
    const val FIELD_VOUCHED_ID = "vouchedId"
    const val FIELD_VOUCHED_KEY = "vouchedKey"
    const val FIELD_VOUCHED_ROLE = "vouchedRole"
    const val FIELD_REVOKED_ID = "revokedId"
    const val FIELD_LATITUDE = "lat"
    const val FIELD_LONGITUDE = "lon"
    const val FIELD_ACCURACY = "accuracy"
    const val FIELD_EMERGENCY = "emergency"
    const val FIELD_PLACE = "place"
    const val FIELD_ARRIVED = "arrived"
    const val FIELD_BLOCKED_COUNT = "blockedCount"
    const val FIELD_DOMAIN_PREFIX = "domain"
    const val FIELD_TEXT = "text"
    const val MAX_SUMMARY_DOMAINS = 40

    const val FIELD_POLICY_JSON = "policyJson"
    const val FIELD_APPS_JSON = "appsJson"

    /** A batch of replicated-state writes, encoded by [StateCodec]. */
    const val FIELD_OPS = "ops"

    /** One number per digest bucket. */
    const val FIELD_DIGEST = "digest"

    /** Which digest buckets a repair is being asked for. */
    const val FIELD_BUCKETS = "buckets"

    /** Minutes, then the label — so a label containing a digit cannot confuse it. */
    const val APP_SEPARATOR = ':'

    /** Longer than any real app name or reason, short enough to render. */
    const val MAX_WIRE_TEXT = 200
}
