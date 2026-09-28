package dev.schrammel.protego_parentis.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs [UpdateChecker] once a day, on either kind of phone.
 *
 * A parent's phone and the managed phone run the check identically; what
 * differs is only what [UpdateChecker] does once it finds something newer —
 * see [ApkInstaller]'s own doc comment for why the managed phone applies it
 * unattended and a parent's phone does not.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result = when (UpdateChecker.checkNow(applicationContext)) {
        // A newer release existed but the download or its checksum failed —
        // worth another attempt on WorkManager's own backoff schedule rather
        // than waiting a full day for the next periodic run.
        UpdateOutcome.DOWNLOAD_FAILED -> Result.retry()
        // Not retried: it verified, so a retry downloads the same wrong APK,
        // over and over on the backoff schedule. The next daily run checks
        // again, by which time the release may have been fixed.
        UpdateOutcome.REJECTED,
        UpdateOutcome.UP_TO_DATE,
        UpdateOutcome.CHECK_FAILED,
        UpdateOutcome.INSTALLED_SILENTLY,
        UpdateOutcome.DOWNLOADED_PENDING_CONFIRMATION,
        -> Result.success()
    }

    companion object {
        private const val NAME = "update-check"
        private const val INTERVAL_HOURS = 24L

        /** Idempotent: safe to call on every process start. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP, the same reasoning as ParentPollWorker: re-launching
                // should not reset the timer.
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build(),
            )
        }
    }
}
