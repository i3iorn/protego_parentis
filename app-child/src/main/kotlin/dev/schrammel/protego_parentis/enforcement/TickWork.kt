package dev.schrammel.protego_parentis.enforcement

import android.content.Context
import android.util.Log
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.context.BatteryReader
import dev.schrammel.protego_parentis.context.ContextProvider
import dev.schrammel.protego_parentis.context.LocationSharing
import dev.schrammel.protego_parentis.context.PlaceWatcher
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.BudgetLevel
import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.policy.ContextSignals
import dev.schrammel.protego_parentis.rules.AppReviewStore
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.rules.SosStore
import dev.schrammel.protego_parentis.sync.AppTime
import dev.schrammel.protego_parentis.usage.InstalledApps
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executor

/**
 * Everything one enforcement tick does besides deciding and applying the rules.
 *
 * [EnforcementService] used to own all of this inline — heartbeats, the daily
 * summary, the SOS repeat, place announcements, the browsing summary, the
 * app sweep, the lapsed-request check — which made the service an
 * untestable second god-object: each behaviour needed a live Service with a
 * foreground notification to exercise. They live here now, behind plain
 * constructor parameters, so a test can drive a tick against a fake
 * [RealtimeSync] and nothing Android at all.
 *
 * The service keeps only lifecycle, threading, receivers and the permanent
 * notification; it calls [run] on its worker and reschedules on the returned
 * delay. Ordering inside [run] is load-bearing — the SOS repeat publishes
 * before the poll so the position is on its way in the same pass that noticed
 * the emergency, and today's summary rides the heartbeat's throttle — so the
 * sequence is kept in one place and left in this order.
 */
internal class TickWork(
    private val context: Context,
    private val coordinator: EnforcementCoordinator,
    private val sync: RealtimeSync,
    private val warnings: WarningNotifier,
    private val review: AppReviewStore,
    private val installedApps: InstalledApps,
    private val requestNotifier: RequestNotifier,
    private val sos: SosStore,
    private val places: PlaceWatcher,
    private val locator: Executor,
    /** The service's own notification; a tick reports through this. */
    private val onNotification: (String) -> Unit,
) {

    /** Runs one pass and returns how long to wait before doing it again. */
    fun run(now: Instant): Long {
        val snapshot = coordinator.evaluate(now)
        if (snapshot == null) {
            onNotification(context.getString(R.string.service_needs_permission))
            Diagnostics.record(context, DiagnosticEvent.USAGE_ACCESS_MISSING)
            // Still say hello. Without this, revoked usage access is indistinguishable
            // from a phone that has been switched off — and the two want very
            // different responses from a parent.
            runCatching {
                val (head, count) = Audit.witness(context)
                sync.publishHeartbeat(
                    Heartbeat(
                        sentAt = now,
                        policyVersion = 0L,
                        batteryPercent = batteryPercent(),
                        managed = coordinator.isDeviceOwner(),
                        usageAccessGranted = false,
                        suspendedCount = 0,
                        historyHead = head,
                        historyCount = count,
                    ),
                )
            }.onFailure { Log.w(TAG, "Could not send heartbeat", it) }
            sync.pollNow(minAgeMillis = TICK_POLL_MIN_AGE_MILLIS)
            return MAX_INTERVAL_MILLIS
        }

        warnings.onEvaluated(snapshot.enforcement.deviceBudget, snapshot.usage.snapshot.policyDay)
        onNotification(summarise(snapshot))

        // Counts only: enough to see that a day is being measured and how much is
        // being enforced, without recording which apps or when they were used.
        Diagnostics.record(
            context,
            DiagnosticEvent.EVALUATED,
            mapOf(
                "usedMinutes" to snapshot.usage.snapshot.deviceTotal.inWholeMinutes,
                "appsWithTime" to snapshot.usage.snapshot.byPackage.size.toLong(),
                "wouldSuspend" to snapshot.enforcement.suspend.size.toLong(),
                "level" to snapshot.enforcement.deviceBudget.level.ordinal.toLong(),
                "managed" to if (snapshot.monitorOnly) 0L else 1L,
            ),
        )

        Log.d(
            TAG,
            "used=${snapshot.usage.snapshot.deviceTotal} " +
                    "level=${snapshot.enforcement.deviceBudget.level} " +
                    "wouldSuspend=${snapshot.enforcement.suspend.size} " +
                    "monitorOnly=${snapshot.monitorOnly}",
        )

        // The broadcast is the fast path; this catches anything installed while
        // the service was not running, which on a rebooting phone is a real window.
        runCatching { sweepForNewApps() }
            .onFailure { Log.w(TAG, "Could not check for new apps", it) }

        // Nothing else calls this: a request expiring is not an event with a
        // moment of its own to hook (see RequestCoordinator.notifyLapsedRequests),
        // only something a periodic check can notice — this tick is that check.
        runCatching { RequestCoordinator(context).notifyLapsedRequests(now) }
            .onFailure { Log.w(TAG, "Could not check for lapsed requests", it) }

        // Throttled inside the coordinator: this tick can fire every fifteen
        // seconds when a limit is close, and a heartbeat that often is a battery bug.
        val saidHello = runCatching { sync.publishHeartbeat(heartbeatOf(snapshot, now)) }
            .onFailure { Log.w(TAG, "Could not send heartbeat", it) }
            .isSuccess

        // Rides the heartbeat's throttle: the two answer the same question from a
        // parent, and there is no sense in one being fresh while the other is a
        // quarter of an hour stale.
        if (saidHello) {
            runCatching { publishToday(snapshot) }
                .onFailure { Log.w(TAG, "Could not send today's summary", it) }
        }

        // An emergency repeats until it is ended, so a parent who missed the
        // first one still finds out. Runs before the poll, so the position is on
        // its way in the same pass that noticed the emergency is still running.
        runCatching { repeatEmergency(now) }
            .onFailure { Log.w(TAG, "Could not repeat the emergency", it) }

        runCatching { announceArrivals() }
            .onFailure { Log.w(TAG, "Could not check saved places", it) }

        runCatching { publishBrowsing(snapshot.usage.snapshot.policyDay) }
            .onFailure { Log.w(TAG, "Could not send the browsing summary", it) }

        // Introduces this phone to any parent that has not enrolled it yet.
        // Throttled inside the coordinator to once an hour.
        runCatching { sync.announceSelf() }
            .onFailure { Log.w(TAG, "Could not announce this device", it) }

        // Piggybacks on the same tick rather than holding a connection open.
        // Failures are reported through the callback and never reach here.
        // Gated by the last poll's age: the realtime stream already delivers
        // everything the moment it is published, so this is a safety net for a
        // stream that is down, not the primary path — see TICK_POLL_MIN_AGE_MILLIS.
        sync.pollNow(minAgeMillis = TICK_POLL_MIN_AGE_MILLIS)

        return nextDelay(
            snapshot.enforcement.nextChangeAt,
            now,
            emergencyActive = sos.isActive,
            watchingSurroundings = watchingSurroundings(snapshot),
        )
    }

    /**
     * Whether this pass read anything no clock can predict — see
     * [ContextSignals]. Place announcements count too: [announceArrivals]
     * only notices an arrival when a tick runs.
     */
    private fun watchingSurroundings(snapshot: EnforcementSnapshot): Boolean {
        if (ContextSignals.readsSurroundings(snapshot.policy.contextRules)) return true
        val rules = AppRegistry.childStores.contextRules
        return rules.announceArrivals && rules.places.isNotEmpty()
    }

    /**
     * Notices apps that were not on the phone last time we looked (AC-3).
     *
     * Called from the tick and from the service's package-changed receiver (the
     * one path that invalidates the app cache first). The very first sweep
     * seeds instead of reporting — see [AppReviewStore]. Runs on the worker
     * thread; walking the package list is slow.
     */
    fun sweepForNewApps() {
        // The cache is invalidated by whoever needs a cold read — the package
        // broadcast before its sweep, never this tick path (see the receiver's
        // comment for why the tick must not pay a cold walk every evaluation).
        // A browser that has just been reinstalled may have lost its managed
        // configuration, and the desired configuration has not changed, so the
        // reconciler has to be told to push it again rather than diff it away.
        coordinator.forgetAppliedRestrictions()

        val installedPackages = installedApps.launchable().map { it.pkg.value }.toSet()
        val fresh = review.noticeInstalled(installedPackages)
        fresh.forEach { pkg ->
            requestNotifier.notifyNewAppLocally(
                installedApps.labelFor(
                    PackageName(pkg)
                )
            )
        }

        // An uninstall never reaches noticeInstalled — it only ever compares
        // what is newly present — so a pending app that disappeared before a
        // parent answered would otherwise sit forever, and now be repeated by
        // the retry below on every tick.
        (review.pending - installedPackages).forEach(review::forgetPending)

        // Announces every app still waiting on a parent's decision, not only
        // what is fresh this sweep. publishAppInstalled is a single,
        // non-durable publish (see SyncPublisher.publish) with no queue behind
        // it, so a phone that was offline or mid-handshake the one time an app
        // was noticed would otherwise never tell a parent about it again —
        // review.pending only clears once a parent actually answers, so this
        // keeps retrying until one does. NewAppStore.remember on the receiving
        // end already ignores a package it is already holding, so repeating
        // this once the first attempt did land costs nothing.
        review.pending.forEach { pkg ->
            sync.publishAppInstalled(pkg, installedApps.labelFor(PackageName(pkg)))
        }
    }

    /**
     * What the child device tells its parents about itself (TR-2).
     *
     * Facts about the app and the phone, never about what was used. The one field
     * that comes close is the count of suspended apps, which says how much is being
     * enforced without saying what.
     */
    private fun heartbeatOf(snapshot: EnforcementSnapshot, now: Instant): Heartbeat {
        val (head, count) = Audit.witness(context)
        return Heartbeat(
            sentAt = now,
            policyVersion = snapshot.policy.version,
            batteryPercent = batteryPercent(),
            managed = !snapshot.monitorOnly,
            // Unconditionally true: a snapshot only exists when usage access is
            // granted. The other branch of run() sends the false case.
            usageAccessGranted = true,
            suspendedCount = snapshot.enforcement.suspend.size,
            historyHead = head,
            historyCount = count,
        )
    }

    /**
     * Today's totals, for the parent's one-glance view (RPT-1).
     *
     * A summary, not a log: totals, and the handful of apps that account for most
     * of them. No times, no sessions, nothing about *when*. The child sees the
     * identical figures on their own dashboard, and the privacy screen names this
     * message so nobody has to guess what leaves the phone.
     */
    private fun publishToday(snapshot: EnforcementSnapshot) {
        val budget = snapshot.enforcement.deviceBudget
        val top = snapshot.usage.snapshot.byPackage
            .entries
            .sortedByDescending { it.value }
            .take(context.resources.getInteger(R.integer.week_top_apps))
            .filter { it.value.inWholeMinutes > 0 }
            .map { (pkg, time) ->
                AppTime(installedApps.labelFor(pkg), time.inWholeMinutes.toInt())
            }

        sync.publishDailySummary(
            usedMinutes = budget.used.inWholeMinutes.toInt(),
            // Infinite means no limit; zero is how that travels.
            allowedMinutes = if (budget.isUnlimited) 0 else budget.allowed.inWholeMinutes.toInt(),
            bonusMinutes = budget.bonus.inWholeMinutes.toInt(),
            remainingMinutes = budget.remaining.inWholeMinutes.toInt(),
            level = budget.level.ordinal,
            topApps = top,
        )
    }

    /**
     * Keeps telling the parents while an emergency is running (LOC-3).
     *
     * Every [SOS_REPEAT_MILLIS], with a fresh position each time. A single
     * message that arrived while a phone was in a pocket would be the whole
     * feature failing quietly. Only runs when a tick does, which is why
     * [nextDelay] keeps ticks this close together while one is active.
     */
    private fun repeatEmergency(now: Instant) {
        if (!sos.isActive) return

        val last = sos.lastSentAt
        val due = last == null ||
                now.toEpochMilli() - last.toEpochMilli() >= SOS_REPEAT_MILLIS ||
                now.isBefore(last)
        if (!due) return

        sos.lastSentAt = now
        sync.publishSosRaised()
        // A fix can block up to LocationSharing's latch timeout. Waiting here
        // stalled the entire enforcement loop — reconcile, heartbeat, poll — for
        // as long as the fix took, on every repeat. The position is published
        // from a side thread instead; the tick keeps moving regardless.
        locator.execute {
            runCatching {
                LocationSharing(context).locate()?.let { position ->
                    sync.publishLocation(position, forEmergency = true)
                }
            }.onFailure { Log.w(TAG, "Could not publish an emergency position", it) }
        }
    }

    /**
     * Tells the parents about arrivals at saved places (LOC-1).
     *
     * The name of the place and nothing else. Where the phone actually is stays
     * something a parent has to ask for, visibly, one answer at a time.
     */
    private fun announceArrivals() {
        val rules = AppRegistry.childStores.contextRules
        if (!rules.announceArrivals) return

        val saved = rules.places
        if (saved.isEmpty()) return

        val inside = ContextProvider(context).current(saved).insidePlaces
        places.transitions(saved, inside).forEach { transition ->
            Audit.record(
                context = context,
                actor = AuditActor.SYSTEM,
                action = if (transition.arrived) {
                    AuditAction.PLACE_ENTERED
                } else {
                    AuditAction.PLACE_LEFT
                },
                subject = transition.place.name,
            )
            sync.publishPlaceTransition(transition)
        }
    }

    /**
     * Today's domains, counted, on the same throttle as the heartbeat (WF-3).
     *
     * [today] is the policy day the tick just evaluated, not [LocalDate.now] —
     * a family with a day-boundary hour set (a budget day that runs, say,
     * 4am-to-4am) must see this summary reset in step with the budget it is
     * reported alongside, not at real midnight.
     */
    private fun publishBrowsing(today: LocalDate) {
        if (!AppRegistry.childStores.webFilter.countVisits) return

        val store = AppRegistry.childStores.webActivity
        val visits = store.visits(today)
        if (visits.isEmpty()) return

        sync.publishWebSummary(
            domains = visits.map { it.domain to it.count },
            blocked = store.blockedAttempts(today),
        )
    }

    /** -1 when the level cannot be read, which the parent's screen shows as unknown. */
    private fun batteryPercent(): Int = BatteryReader.percent(context) ?: -1

    private fun summarise(snapshot: EnforcementSnapshot): String {
        val budget = snapshot.enforcement.deviceBudget
        return when {
            budget.isUnlimited ->
                context.getString(R.string.service_no_limit, context.formatDuration(budget.used))

            budget.level == BudgetLevel.EXHAUSTED ->
                context.getString(R.string.service_used_up)

            else ->
                context.getString(
                    R.string.service_remaining,
                    context.formatDuration(budget.remaining)
                )
        }
    }

    companion object {
        private const val TAG = "DementorTick"

        /** Never re-evaluate more often than this, however close the next change is. */
        const val MIN_INTERVAL_MILLIS = 15_000L

        /** Never sleep longer than this; the service's own doc explains the value. */
        const val MAX_INTERVAL_MILLIS = EnforcementService.MAX_INTERVAL_MILLIS

        /** The tick's poll is a dead-stream fallback, not a second copy of the traffic. */
        const val TICK_POLL_MIN_AGE_MILLIS = 3 * 60_000L

        /** How often an unresolved emergency repeats itself to the parents. */
        const val SOS_REPEAT_MILLIS = 30_000L

        /**
         * The longest a surroundings-based rule waits to apply — the tick's
         * cadence before the idle interval was raised to five minutes.
         */
        const val SURROUNDINGS_INTERVAL_MILLIS = 60_000L

        /**
         * How long to sleep before the next tick.
         *
         * Until the engine says the decision would change, within bounds. The
         * engine already computes the exact moment a limit bites or a warning is
         * due, so there is no reason to wake every minute. With the screen off
         * and nothing running, the next change is usually the 4am reset and we
         * idle at the maximum interval instead of burning battery on pointless
         * evaluations.
         *
         * The engine's next-change moment knows nothing that isn't driven by the
         * clock, so two things hold the sleep shorter:
         * - an active emergency, to [SOS_REPEAT_MILLIS] — the repeat only runs
         *   when a tick does;
         * - rules that read the phone's surroundings (Wi-Fi, a place, a nearby
         *   device, battery), or place announcements, to
         *   [SURROUNDINGS_INTERVAL_MILLIS] — nothing broadcasts those changes,
         *   so a tick is the only thing that notices them.
         *
         * Exact alarms would be the theoretical ideal, but `SCHEDULE_EXACT_ALARM`
         * is a restricted permission that Play grants to alarm-clock apps, and a
         * minute of slack on a screen-time warning does not justify that fight.
         */
        internal fun nextDelay(
            nextChange: Instant?,
            now: Instant,
            emergencyActive: Boolean,
            watchingSurroundings: Boolean = false,
        ): Long {
            var delay = if (nextChange == null) {
                MAX_INTERVAL_MILLIS
            } else {
                (nextChange.toEpochMilli() - now.toEpochMilli())
                    .coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
            }
            if (watchingSurroundings) delay = delay.coerceAtMost(SURROUNDINGS_INTERVAL_MILLIS)
            if (emergencyActive) delay = delay.coerceAtMost(SOS_REPEAT_MILLIS)
            return delay
        }
    }
}
