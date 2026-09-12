package dev.schrammel.protego_parentis.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.IntegrityProblem
import dev.schrammel.protego_parentis.policy.IntegrityRules
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Keeps a parent's phone converging with the family while the app is closed.
 *
 * Real-time delivery now runs through [RealtimeSyncService]'s long-lived stream,
 * so a request lands the moment it is published rather than on the next poll.
 * This worker is the reconciliation safety net in case the stream was down or
 * the app restarted before reconnecting: a periodic poll picks up anything
 * missed, and replays the guard filters the duplicates. Fifteen minutes is the
 * shortest period Android allows for periodic work, which is frequent enough to
 * stay within a request's expiry and cheap enough to run forever.
 *
 * It also re-arms the stream itself (see the `RealtimeSyncService.start` call
 * in [doWork]) — the one restart trigger that runs on a timer rather than
 * waiting for a reboot or an opened screen, so a service the OS killed
 * outright is never dead for longer than one of these intervals.
 *
 * The child device does not use this. Its enforcement service already ticks, and
 * polling is folded into that tick.
 */
class ParentPollWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (RoleStore(context).roleOrChild != DeviceRole.PARENT) return Result.success()

        val sync = SyncCoordinator(context)
        if (!sync.settings().isUsable) return Result.success()

        // Idempotent, and the cheapest re-arm available for a stream the OS
        // killed outright between polls: it bounds how long the realtime
        // service can stay dead to this worker's own interval instead of
        // however long the phone goes without a reboot or an opened screen.
        RealtimeSyncService.start(context)

        // pollNow hands its work to the sync executor and returns at once, while
        // a Worker must not return until the work is done or the process may be
        // killed mid-poll. Waiting here is the correct shape for this API.
        // A parent phone has no enforcement tick, so this is where it introduces
        // itself to the child device that has to enrol it.
        sync.announceSelf()

        val finished = CountDownLatch(1)
        sync.pollNow { finished.countDown() }

        return if (finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            checkForSilence(context)
            Result.success()
        } else {
            // A stuck network call, not a permanent failure. Let WorkManager back
            // off and try again rather than giving up on this phone entirely.
            Result.retry()
        }
    }

    /**
     * Raises the alert if the child device has stopped checking in.
     *
     * Once per stretch of silence, not once per poll. An alert that repeats every
     * fifteen minutes for a phone that is simply flat teaches a parent to dismiss
     * it without reading, which is the opposite of what this feature is for.
     */
    private fun checkForSilence(context: Context) {
        val store = HeartbeatStore(context)
        val problems = IntegrityRules.problems(
            heartbeat = store.latest,
            now = Instant.now(),
            historyRewritten = store.historySuspect,
        )

        val worth = when {
            IntegrityProblem.SILENT in problems -> if (store.stoppedAfterLastHeartbeat()) {
                // Not silence: the phone said goodbye. Worth telling a parent
                // once, because "switched off" and "gone quiet" call for very
                // different responses.
                R.string.integrity_switched_off_hint
            } else {
                R.string.integrity_silent_hint
            }

            IntegrityProblem.HISTORY_REWRITTEN in problems -> R.string.integrity_history_hint
            IntegrityProblem.BATTERY_LOW in problems -> R.string.integrity_battery_notification
            else -> null
        }

        if (worth == null) {
            store.silenceAlerted = false
            return
        }
        // Once per stretch, not once per poll. An alert that repeats every fifteen
        // minutes for a phone that is simply flat teaches a parent to dismiss it
        // without reading, which is the opposite of what this is for.
        if (store.silenceAlerted) return

        store.silenceAlerted = true
        RequestNotifier(context).notifyDeviceSilent(context.getString(worth))
    }

    companion object {
        private const val NAME = "parent-poll"
        private const val TIMEOUT_SECONDS = 45L
        private const val INTERVAL_MINUTES = 15L

        /** Idempotent: safe to call on every launch. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP, so re-launching does not reset the timer and leave a phone
                // that is opened often polling less than one that is opened rarely.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ParentPollWorker>(
                    INTERVAL_MINUTES,
                    TimeUnit.MINUTES,
                ).setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).build(),
            )
        }

        /** Stops the polling when this phone is no longer a parent's. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
