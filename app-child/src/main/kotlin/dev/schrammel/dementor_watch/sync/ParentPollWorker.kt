package dev.schrammel.dementor_watch.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.RequestNotifier
import dev.schrammel.dementor_watch.model.IntegrityProblem
import dev.schrammel.dementor_watch.policy.IntegrityRules
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Keeps a parent's phone listening while the app is closed.
 *
 * ntfy is polled rather than streamed, so without this a parent would only learn
 * about a request by opening the app — which makes a two-hour expiry window a
 * coin flip. Fifteen minutes is the shortest period Android allows for periodic
 * work, and it is the right trade here: a request is not an alarm, and a
 * foreground service with a permanent notification on a parent's own phone would
 * be a much worse bargain.
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
