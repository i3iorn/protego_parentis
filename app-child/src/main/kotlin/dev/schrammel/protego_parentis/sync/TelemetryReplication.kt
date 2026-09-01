package dev.schrammel.protego_parentis.sync

import android.content.Context
import java.time.Instant

/**
 * What the managed phone reports about itself.
 *
 * The highest-churn thing this app replicates, and so the one where restraint
 * pays most. The enforcement tick runs every few seconds; a naive replica would
 * publish "used minutes" on every one of them and spend more on saying nothing
 * changed than on everything else combined.
 *
 * Three things keep it cheap:
 *
 * - **Coarse values.** Screen time is reported in whole minutes, battery in
 *   steps of five percent. A tick that moves neither writes nothing, and
 *   [ReplicatedStateStore.writeAll] then queues nothing and publishes nothing —
 *   so an idle phone is silent rather than chatty.
 * - **Latest-value only.** Each figure is one register, overwritten. There is no
 *   history here; the day's shape lives in the child's own local store, where it
 *   is not worth a byte of anybody's data allowance to duplicate.
 * - **Out of the digest.** Telemetry is [ReplicationRules.ReplicationMode.VOLATILE],
 *   so it is never compared and never repaired. It could not usefully be: by the
 *   time a repair arrived the value would already be stale, and the next tick
 *   replaces it anyway.
 *
 * Only the managed phone may write any of this — see [ReplicationRules.mayWrite].
 * A parent's phone inventing a quiet afternoon would be a lie the child had no
 * way to contradict.
 */
class TelemetryReplication(context: Context) {

    private val appContext = context.applicationContext
    private val state = ReplicatedStateStore(appContext)

    /**
     * Records the current picture, writing only what actually moved.
     *
     * Null figures are left alone rather than written as absent: a phone whose
     * usage access has been revoked should not appear to have reported zero
     * minutes, which reads as "a quiet day" instead of "we cannot see".
     */
    fun report(
        usedMinutes: Int? = null,
        allowedMinutes: Int? = null,
        remainingMinutes: Int? = null,
        bonusMinutes: Int? = null,
        level: Int? = null,
        batteryPercent: Int? = null,
        charging: Boolean? = null,
        place: String? = null,
        /**
         * The day's biggest few apps, or null to leave what is there.
         *
         * Minutes and label are separate registers because they change at
         * completely different rates: the minutes tick over all day, the label
         * never does. Splitting them means an app whose usage moved costs one
         * short op rather than re-sending its name with it.
         */
        topApps: List<AppTime>? = null,
    ): List<StateOp> {
        val origin = LocalDeviceId.current() ?: return emptyList()

        val values = buildMap<String, String?> {
            usedMinutes?.let { put(path(USED), it.toString()) }
            allowedMinutes?.let { put(path(ALLOWED), it.toString()) }
            remainingMinutes?.let { put(path(REMAINING), it.toString()) }
            bonusMinutes?.let { put(path(BONUS), it.toString()) }
            level?.let { put(path(LEVEL), it.toString()) }
            // Rounded, so a battery ticking down one percent at a time is one
            // write every five rather than five.
            batteryPercent?.let { put(path(BATTERY), coarseBattery(it).toString()) }
            charging?.let { put(path(CHARGING), it.toString()) }
            place?.let { put(path(PLACE), it) }

            topApps?.let { apps ->
                apps.take(MAX_APPS).forEach { app ->
                    put("${appRoot(app.label)}/m", app.minutes.toString())
                }
                // An app that has dropped out of the top few is cleared, or a
                // parent's dashboard would keep showing yesterday's list padded
                // with whatever used to be on it.
                val live = apps.take(MAX_APPS).map { key(it.label) }.toSet()
                state.children(path(APPS)).keys.forEach { gone ->
                    if (gone.substringBefore('/') !in live) put("${path(APPS)}${ReplicationRules.SEPARATOR}$gone", null)
                }
            }
        }
        return state.writeAll(values, origin)
    }

    /**
     * Says how the last request for a position turned out.
     *
     * A locate that cannot be answered used to produce nothing at all — the
     * managed phone wrote a line to logcat and stayed quiet, so a parent saw
     * "Sent" and then waited on an answer that was never coming, unable to tell
     * that from one still on its way. Recording the outcome is what turns those
     * two apart.
     *
     * Telemetry rather than a message of its own: it is the managed phone
     * describing its own state, it is only ever the latest one that matters, and
     * it wants exactly the write rules this section already has.
     */
    fun reportLocate(outcome: LocateOutcome, at: Instant = Instant.now()): List<StateOp> {
        val origin = LocalDeviceId.current() ?: return emptyList()
        return state.writeAll(
            mapOf(
                path(LOCATE_RESULT) to outcome.name,
                path(LOCATE_AT) to at.epochSecond.toString(),
            ),
            origin,
        )
    }

    /** How the last request for a position turned out, and when. */
    fun lastLocate(): Pair<LocateOutcome, Instant>? {
        val outcome = state.value(path(LOCATE_RESULT))
            ?.let { name -> LocateOutcome.entries.firstOrNull { it.name == name } }
            ?: return null
        val at = state.value(path(LOCATE_AT))?.toLongOrNull() ?: return null
        return outcome to Instant.ofEpochSecond(at)
    }

    /** The day's biggest few apps, as last reported. */
    fun topApps(): List<AppTime> {
        val root = "${path(APPS)}${ReplicationRules.SEPARATOR}"
        return state.entries
            .filterKeys { it.startsWith(root) && it.endsWith("/m") }
            .mapNotNull { (key, entry) ->
                val minutes = entry.value?.toIntOrNull() ?: return@mapNotNull null
                val label = key.removePrefix(root).removeSuffix("/m")
                AppTime(label = label, minutes = minutes)
            }
            .sortedByDescending { it.minutes }
    }

    /** The last figures the managed phone reported, for a parent's dashboard. */
    fun read(): Telemetry = Telemetry(
        usedMinutes = number(USED),
        allowedMinutes = number(ALLOWED),
        remainingMinutes = number(REMAINING),
        bonusMinutes = number(BONUS),
        level = number(LEVEL),
        batteryPercent = number(BATTERY),
        charging = state.value(path(CHARGING))?.toBooleanStrictOrNull(),
        place = state.value(path(PLACE)),
    )

    private fun number(field: String): Int? = state.value(path(field))?.toIntOrNull()

    private fun path(field: String) = "${ReplicationRules.TELEMETRY}${ReplicationRules.SEPARATOR}$field"

    private fun appRoot(label: String) =
        "${path(APPS)}${ReplicationRules.SEPARATOR}${key(label)}"

    /**
     * An app label is chosen by the app, so it can contain anything — including
     * the path separator, which would silently graft a fake extra level onto the
     * state tree. Replaced rather than rejected: a mangled label on a dashboard
     * is a cosmetic problem, an injected path is not.
     */
    private fun key(label: String) = label
        .replace(ReplicationRules.SEPARATOR, ' ')
        // Bounded as well as sanitised. An app chooses its own label, and one
        // long enough to push the path over the limit would be dropped by the
        // local write guard and then silently missing from the dashboard.
        .take(MAX_LABEL)
        .ifBlank { UNNAMED }

    private fun coarseBattery(percent: Int): Int =
        (percent.coerceIn(0, 100) / BATTERY_STEP) * BATTERY_STEP

    private companion object {
        const val USED = "used"
        const val ALLOWED = "allowed"
        const val REMAINING = "remaining"
        const val BONUS = "bonus"
        const val LEVEL = "level"
        const val BATTERY = "battery"
        const val CHARGING = "charging"
        const val PLACE = "place"
        const val APPS = "apps"
        const val LOCATE_RESULT = "locate/result"
        const val LOCATE_AT = "locate/at"

        /** The same handful the old daily summary carried. */
        const val MAX_APPS = 10

        /** Longer than any real app name, short enough to keep the path legal. */
        const val MAX_LABEL = 64

        /** A label that sanitised away entirely still needs a key. */
        const val UNNAMED = "?"

        /** Five points. Finer than a parent can act on, coarser than it changes. */
        const val BATTERY_STEP = 5
    }
}

/**
 * How a request for a position turned out on the managed phone.
 *
 * A closed set, and deliberately a small one: it says whether an answer is
 * coming, and if not whose problem it is to fix. It carries nothing about where
 * the phone was — that travels as a position or not at all.
 */
enum class LocateOutcome {
    /** A position was found and sent. */
    ANSWERED,

    /** Locating is switched off for this family. Nothing will arrive. */
    SWITCHED_OFF,

    /**
     * Locating is on, but no position was available — permission refused, or no
     * fix yet. Worth asking again; the previous two are not.
     */
    UNAVAILABLE,
}

/** The last picture the managed phone reported of itself. */
data class Telemetry(
    val usedMinutes: Int?,
    val allowedMinutes: Int?,
    val remainingMinutes: Int?,
    val bonusMinutes: Int?,
    val level: Int?,
    val batteryPercent: Int?,
    val charging: Boolean?,
    /** The name of a saved place, never coordinates. */
    val place: String?,
) {
    /** Null until the managed phone has reported anything at all. */
    val hasReported: Boolean get() = usedMinutes != null
}
