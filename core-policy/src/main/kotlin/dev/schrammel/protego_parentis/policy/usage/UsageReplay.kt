package dev.schrammel.protego_parentis.policy.usage

import dev.schrammel.protego_parentis.model.PackageName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The only event kinds that affect foreground time. Everything else is noise. */
enum class UsageEventKind {
    APP_RESUMED,
    APP_PAUSED,

    /** Screen went off or the keyguard appeared: whatever was open stops counting. */
    SCREEN_OFF,

    /**
     * Screen came back on, or the keyguard was dismissed. Whatever was on screen
     * before is on screen again — see the note on resuming below.
     */
    SCREEN_ON,

    /** Device shut down. Same effect as screen off, but the gap is unobserved. */
    SHUTDOWN,
}

/** A platform usage event, stripped to what the replay needs. */
data class RawUsageEvent(
    val kind: UsageEventKind,
    val packageName: String,
    val atMillis: Long,
)

data class ReplayResult(
    val totals: Map<PackageName, Duration>,
    /** The app still open at the end of the window, if any. */
    val openPackage: PackageName?,
)

/**
 * Turns a stream of resume/pause events into per-app foreground time.
 *
 * Lives here, in pure Kotlin, because every number the product shows comes out of
 * this function and it is far too easy to get quietly wrong. The failure mode is
 * not a crash — it is a screen-time figure that disagrees with the phone's own
 * Digital Wellbeing and destroys the parent's trust in the whole app.
 *
 * Four rules do the real work:
 *
 *  - **Screen off ends every open session.** Android does not always emit a pause
 *    when the screen goes off, and without this a phone left face-down on a table
 *    accrues hours against whatever was last open.
 *  - **Screen on resumes what was interrupted.** This is the mirror of the rule
 *    above and is not optional. Unlocking straight back into the app you were
 *    already in does not always produce a fresh resume event, so without this the
 *    app silently stops counting until you happen to switch apps — which looks
 *    exactly like usage tracking randomly breaking.
 *  - **A resume implicitly ends any other open session.** App switches do not
 *    reliably pause the outgoing app first, and counting two apps at once would
 *    make per-app totals exceed real elapsed time.
 *  - **Only pauses close a session, never stops.** Moving between two screens of
 *    one app emits pause(first), resume(second), stop(first) — in that order. A
 *    stop that closes the session would end it immediately after it reopened,
 *    stopping the clock for an app that is still on screen.
 */
object UsageReplay {

    fun replay(events: List<RawUsageEvent>, untilMillis: Long): ReplayResult {
        val totals = HashMap<String, Long>()
        val openedAt = HashMap<String, Long>()

        /** What was on screen when the device went to sleep, if anything. */
        var interrupted: String? = null

        /** A wake that has not yet been turned into an open session, and from when. */
        var pendingResume: Pair<String, Long>? = null

        fun close(pkg: String, at: Long) {
            val started = openedAt.remove(pkg) ?: return
            val elapsed = at - started
            // Out-of-order timestamps would otherwise subtract time.
            if (elapsed > 0L) totals.merge(pkg, elapsed, Long::plus)
        }

        fun foreground(): String? = openedAt.maxByOrNull { it.value }?.key

        /** Turns a deferred wake into a real open session. */
        fun applyPendingResume() {
            val (pkg, at) = pendingResume ?: return
            pendingResume = null
            openedAt.putIfAbsent(pkg, at)
        }

        fun closeAll(at: Long) {
            openedAt.keys.toList().forEach { close(it, at) }
        }

        for (event in events.sortedBy { it.atMillis }) {
            when (event.kind) {
                UsageEventKind.APP_RESUMED -> {
                    // Settle any deferred wake first: between waking the device and
                    // opening something else, the interrupted app was on screen.
                    applyPendingResume()
                    // Only one app is genuinely in the foreground at a time.
                    openedAt.keys
                        .filterNot { it == event.packageName }
                        .forEach { close(it, event.atMillis) }
                    // A repeated resume for the same package keeps the earlier start.
                    openedAt.putIfAbsent(event.packageName, event.atMillis)
                    interrupted = event.packageName
                }

                UsageEventKind.APP_PAUSED -> {
                    applyPendingResume()
                    close(event.packageName, event.atMillis)
                    // Leaving an app deliberately means the next wake should not
                    // put it back.
                    if (interrupted == event.packageName) interrupted = null
                }

                UsageEventKind.SCREEN_ON -> {
                    // Deferred rather than opened immediately, so a later unlock
                    // can move the start forward: waking to a lock screen is not
                    // using the app behind it. The last wake event before real
                    // activity is the one that counts.
                    pendingResume = interrupted?.let { it to event.atMillis }
                }

                UsageEventKind.SCREEN_OFF,
                UsageEventKind.SHUTDOWN,
                -> {
                    // Time between waking and sleeping again still counts.
                    applyPendingResume()
                    // Remembered so the next wake can pick it up. Null when nothing
                    // was open, so a stale app is never resurrected.
                    interrupted = foreground()
                    closeAll(event.atMillis)
                    pendingResume = null
                }
            }
        }

        applyPendingResume()
        val stillOpen = foreground()
        closeAll(untilMillis)

        return ReplayResult(
            totals = totals
                .filterValues { it > 0L }
                .entries
                .associate { (pkg, millis) -> PackageName(pkg) to millis.milliseconds },
            openPackage = stillOpen?.let(::PackageName),
        )
    }
}
