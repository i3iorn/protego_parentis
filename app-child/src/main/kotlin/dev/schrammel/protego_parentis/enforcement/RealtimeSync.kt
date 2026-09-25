package dev.schrammel.protego_parentis.enforcement

import dev.schrammel.protego_parentis.context.PlaceTransition
import dev.schrammel.protego_parentis.context.Position
import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.sync.AppTime

/**
 * Everything enforcement tells the family about, without enforcement needing to
 * know that sync is what carries it.
 *
 * `EnforcementService` and `BootReceiver` used to construct a `SyncCoordinator`
 * directly and call `RealtimeSyncService.start()` themselves — six imports
 * across the two files pulling the whole sync layer into `enforcement`, when
 * enforcement only ever needed to say ten specific things.
 * `dev.schrammel.protego_parentis.sync.RealtimeSyncAdapter` is the one
 * implementation (see its own doc comment for why it wraps `SyncCoordinator`
 * rather than `SyncCoordinator` implementing this directly), wired once
 * through `AppRegistry`; this is the allowed direction, `sync` depending on
 * `enforcement` rather than the reverse.
 *
 * `AppTime` stays a `sync` type for now — the three call sites that build one
 * (here, `NewApp`, `IncomingRequest`) are a smaller, separate cleanup, not
 * part of this seam.
 */
interface RealtimeSync {
    /** Opens the family's realtime stream, so a command lands as it's published. */
    fun connectRealtime()

    /** Says the phone is going off, so silence can be told apart from shutdown. */
    fun publishStopping()

    /** Says the child device is still alive and still working (TR-2). */
    fun publishHeartbeat(heartbeat: Heartbeat, force: Boolean = false)

    /** Fetches and applies anything the other phones have sent. */
    fun pollNow(minAgeMillis: Long = 0L, onFinished: (String) -> Unit = {})

    /** Introduces this phone to the family. */
    fun announceSelf(force: Boolean = false)

    /** Tells the parents an app they have not seen appeared on the phone (AC-3). */
    fun publishAppInstalled(packageName: String, label: String)

    /** Sends today's totals to the parents (RPT-1). */
    fun publishDailySummary(
        usedMinutes: Int,
        allowedMinutes: Int,
        bonusMinutes: Int,
        remainingMinutes: Int,
        level: Int,
        topApps: List<AppTime>,
    )

    fun publishSosRaised()

    /** One position, in answer to one request. Never sent unasked, except in an SOS. */
    fun publishLocation(position: Position, forEmergency: Boolean)

    /** Arrived at, or left, a saved place. */
    fun publishPlaceTransition(transition: PlaceTransition)

    /** Today's domains, counted (WF-3). */
    fun publishWebSummary(domains: List<Pair<String, Int>>, blocked: Int)
}
