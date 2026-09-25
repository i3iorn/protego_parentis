package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.context.PlaceTransition
import dev.schrammel.protego_parentis.context.Position
import dev.schrammel.protego_parentis.enforcement.RealtimeSync
import dev.schrammel.protego_parentis.model.Heartbeat

/**
 * [RealtimeSync], delegating to the shared [SyncCoordinator] from [SyncGraph].
 *
 * A thin wrapper rather than `SyncCoordinator` implementing [RealtimeSync]
 * directly: several of `SyncCoordinator`'s own methods (`announceSelf`,
 * `publishHeartbeat`, `pollNow`) have default parameter values that dozens of
 * existing call sites — nearly every screen — rely on, and Kotlin forbids an overriding function from redeclaring a
 * default. Making `SyncCoordinator` itself the implementation would have meant
 * stripping those defaults from the class every one of those call sites uses,
 * to serve the one caller ([dev.schrammel.protego_parentis.AppRegistry]) that
 * only ever goes through the interface. This adapter is the interface's own
 * defaults instead, and touches nothing else.
 */
class RealtimeSyncAdapter(context: Context) : RealtimeSync {

    private val appContext = context.applicationContext
    private val coordinator = SyncGraph.get(appContext)

    override fun connectRealtime() = RealtimeSyncService.start(appContext)

    override fun publishStopping() = coordinator.publishStopping()

    override fun publishHeartbeat(heartbeat: Heartbeat, force: Boolean) =
        coordinator.publishHeartbeat(heartbeat, force)

    override fun pollNow(minAgeMillis: Long, onFinished: (String) -> Unit) =
        coordinator.pollNow(minAgeMillis, onFinished)

    override fun announceSelf(force: Boolean) = coordinator.announceSelf(force)

    override fun publishAppInstalled(packageName: String, label: String) =
        coordinator.publishAppInstalled(packageName, label)

    override fun publishDailySummary(
        usedMinutes: Int,
        allowedMinutes: Int,
        bonusMinutes: Int,
        remainingMinutes: Int,
        level: Int,
        topApps: List<AppTime>,
    ) = coordinator.publishDailySummary(usedMinutes, allowedMinutes, bonusMinutes, remainingMinutes, level, topApps)

    override fun publishSosRaised() = coordinator.publishSosRaised()

    override fun publishLocation(position: Position, forEmergency: Boolean) =
        coordinator.publishLocation(position, forEmergency)

    override fun publishPlaceTransition(transition: PlaceTransition) =
        coordinator.publishPlaceTransition(transition)

    override fun publishWebSummary(domains: List<Pair<String, Int>>, blocked: Int) =
        coordinator.publishWebSummary(domains, blocked)
}
