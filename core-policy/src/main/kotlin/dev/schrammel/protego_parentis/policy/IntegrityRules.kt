package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.model.IntegrityProblem
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Turns the last heartbeat into a list of things worth telling a parent (TR-2).
 *
 * Pure and separate from the transport so the interesting part — what counts as
 * "too quiet", and how a stale heartbeat differs from one that never arrived — is
 * decided in one place and tested without two phones.
 */
object IntegrityRules {

    /**
     * Problems with [heartbeat] as of [now].
     *
     * A silent device reports only [IntegrityProblem.SILENT]: everything else in
     * the heartbeat is stale by definition, and listing "usage access was missing"
     * beside "we have not heard from this phone since lunchtime" invites a parent
     * to chase the wrong problem.
     */
    fun problems(
        heartbeat: Heartbeat?,
        now: Instant,
        /** Whether the child's history has stopped following from what we were told. */
        historyRewritten: Boolean = false,
        silenceAfter: Duration = Defaults.HEARTBEAT_SILENCE_MINUTES.minutes,
        lowBatteryPercent: Int = Defaults.LOW_BATTERY_PERCENT,
    ): List<IntegrityProblem> {
        if (heartbeat == null) return listOf(IntegrityProblem.NEVER_HEARD)

        val quiet = quietFor(heartbeat, now)
        if (quiet == null || quiet >= silenceAfter) return listOf(IntegrityProblem.SILENT)

        return buildList {
            if (!heartbeat.usageAccessGranted) add(IntegrityProblem.USAGE_ACCESS_MISSING)
            if (!heartbeat.managed) add(IntegrityProblem.NOT_MANAGED)
            if (heartbeat.batteryKnown && heartbeat.batteryPercent <= lowBatteryPercent) {
                add(IntegrityProblem.BATTERY_LOW)
            }
            if (historyRewritten) add(IntegrityProblem.HISTORY_REWRITTEN)
        }
    }

    /**
     * Whether the history described by [latest] could have grown from [previous].
     *
     * An honest log only ever gains entries, so its count rises and its head moves
     * with it. A count that has gone backwards means entries were removed; a head
     * that changed while the count stood still means entries were rewritten in
     * place. Either way the record was rebuilt, not appended to.
     *
     * Two things this deliberately does not treat as tampering: a first sighting,
     * and a count that grew — the head is expected to change then, and it is not
     * this phone's job to verify a chain it does not hold.
     */
    fun historyRewritten(
        previous: Heartbeat?,
        latest: Heartbeat,
        /**
         * How many of that phone's entries this one holds in its own replica.
         *
         * A floor the reporting phone cannot lower. The audit section replicates
         * append-only — the earliest write for a path stands and a later one
         * cannot revise it — so entries that reached here stay here whatever
         * happens to the log they came from. The reported count is a running
         * total that only ever increases, so it can never honestly be less than
         * the number of that phone's entries somebody else is already holding.
         *
         * This is what catches the rewrite the two comparisons below cannot: one
         * that *grows* the log. Remove five entries, add six, and the count is
         * higher and the head is different, which reads as ordinary progress —
         * unless the count has fallen through a floor set by evidence the
         * rewriting phone does not control.
         */
        heldFromDevice: Long = 0L,
    ): Boolean {
        if (latest.historyCount in 1 until heldFromDevice) return true

        if (previous == null) return false
        // A device that has never reported a history yet says nothing either
        // way — the field may simply not be populated on its build. But once it
        // has reported a real, nonzero count, dropping back to zero is not that
        // case: it is a shrink to nothing, same as any other shrink below, and
        // must not be waved through just because zero looks like "no data yet".
        if (previous.historyCount == 0L) return false

        if (latest.historyCount < previous.historyCount) return true
        return latest.historyCount == previous.historyCount &&
            latest.historyHead != previous.historyHead
    }

    /**
     * How long the device has been quiet, or null if it claims to have spoken in
     * the future.
     *
     * A future timestamp means one of the two clocks is wrong, which is a live
     * possibility on a phone whose owner is motivated to change it. Reading it as
     * silence is the safe answer: it refuses to report "heard from a minute ago"
     * on the strength of a number the other side controls.
     */
    fun quietFor(heartbeat: Heartbeat, now: Instant): Duration? {
        val millis = now.toEpochMilli() - heartbeat.sentAt.toEpochMilli()
        return if (millis < 0L) null else millis.milliseconds
    }
}
