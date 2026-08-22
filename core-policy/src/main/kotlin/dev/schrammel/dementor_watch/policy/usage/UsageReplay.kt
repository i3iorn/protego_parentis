package dev.schrammel.dementor_watch.policy.usage

import dev.schrammel.dementor_watch.model.PackageName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The only event kinds that affect foreground time. Everything else is noise. */
enum class UsageEventKind {
    APP_RESUMED,
    APP_PAUSED,

    /** Screen went off or the keyguard appeared: whatever was open stops counting. */
    SCREEN_OFF,

    /** Device shut down. Same effect, but the gap afterwards is unobserved. */
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
 * Two rules do the real work:
 *
 *  - **Screen off ends every open session.** Android does not always emit a pause
 *    when the screen goes off, and without this a phone left face-down on a table
 *    accrues hours against whatever was last open.
 *  - **A resume implicitly ends any other open session.** App switches do not
 *    reliably pause the outgoing app first, and counting two apps at once would
 *    make per-app totals exceed real elapsed time.
 */
object UsageReplay {

    fun replay(events: List<RawUsageEvent>, untilMillis: Long): ReplayResult {
        val totals = HashMap<String, Long>()
        val openedAt = HashMap<String, Long>()

        fun close(pkg: String, at: Long) {
            val started = openedAt.remove(pkg) ?: return
            val elapsed = at - started
            // Out-of-order timestamps would otherwise subtract time.
            if (elapsed > 0L) totals.merge(pkg, elapsed, Long::plus)
        }

        fun closeAll(at: Long) {
            openedAt.keys.toList().forEach { close(it, at) }
        }

        for (event in events.sortedBy { it.atMillis }) {
            when (event.kind) {
                UsageEventKind.APP_RESUMED -> {
                    // Only one app is genuinely in the foreground at a time.
                    openedAt.keys
                        .filterNot { it == event.packageName }
                        .forEach { close(it, event.atMillis) }
                    // A repeated resume for the same package keeps the earlier start.
                    openedAt.putIfAbsent(event.packageName, event.atMillis)
                }

                UsageEventKind.APP_PAUSED -> close(event.packageName, event.atMillis)

                UsageEventKind.SCREEN_OFF,
                UsageEventKind.SHUTDOWN,
                -> closeAll(event.atMillis)
            }
        }

        // Whatever is still open has been running up to the end of the window.
        val stillOpen = openedAt.maxByOrNull { it.value }?.key
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
