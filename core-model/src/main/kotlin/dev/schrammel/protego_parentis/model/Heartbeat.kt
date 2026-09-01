package dev.schrammel.protego_parentis.model

import java.time.Instant

/**
 * The child device saying it is still alive and still working (TR-2).
 *
 * The point of this feature is that silence is informative. A parental control
 * app that quietly stops enforcing looks exactly like a well-behaved teenager, and
 * the difference is only discoverable if the device is expected to check in.
 *
 * Everything here is a fact about the *app and the device*, never about what was
 * used or when — the same rule the diagnostic log follows. A heartbeat is the one
 * message that travels on a schedule rather than because something happened, so it
 * is also the one most worth keeping content-free.
 */
data class Heartbeat(
    val sentAt: Instant,
    /** Which version of the rules the device is enforcing, for spotting drift. */
    val policyVersion: Long,
    /** 0..100, or -1 when the level could not be read. */
    val batteryPercent: Int,
    /** Whether the app is still device owner. False means enforcement is advisory. */
    val managed: Boolean,
    /** Without this every measurement reads zero and limits never bite. */
    val usageAccessGranted: Boolean,
    /** How many apps the last evaluation was holding suspended. */
    val suspendedCount: Int,
    /**
     * The head of the child's history chain, and how many entries have ever been
     * written to it (TR-3).
     *
     * A hash chain kept only on the phone that writes it can always be recomputed
     * by whoever holds that phone, so trimming the oldest entries away is
     * indistinguishable from the log simply being full. Sending the head and the
     * running count to the parents puts a copy somewhere the child cannot rewrite:
     * a count that goes backwards, or a head that changes without the count
     * moving, means the record was rebuilt rather than appended to.
     */
    val historyHead: String = "",
    val historyCount: Long = 0L,
) {
    val batteryKnown: Boolean get() = batteryPercent in 0..100
}

/** Something a parent should be told about. */
enum class IntegrityProblem {
    /** Nothing has ever been heard from the child device. */
    NEVER_HEARD,

    /** It has been quiet for longer than [Defaults.HEARTBEAT_SILENCE_MINUTES]. */
    SILENT,

    /** Usage access was revoked, so nothing is being measured. */
    USAGE_ACCESS_MISSING,

    /**
     * The app is no longer device owner. Limits are still shown and still counted,
     * but nothing is being enforced.
     */
    NOT_MANAGED,

    /** The battery is low enough that the phone may be about to go quiet (LOC-4). */
    BATTERY_LOW,

    /**
     * The child's shared history no longer follows from what the parents were
     * previously told it was.
     */
    HISTORY_REWRITTEN,
    ;

    /**
     * Whether this is worth interrupting a parent for.
     *
     * A low battery is worth showing on a screen; it is not worth a notification
     * every fifteen minutes, and treating it as urgent is how a parent learns to
     * dismiss these without reading them.
     */
    val isUrgent: Boolean
        get() = this == SILENT ||
            this == USAGE_ACCESS_MISSING ||
            this == NOT_MANAGED ||
            this == HISTORY_REWRITTEN
}
