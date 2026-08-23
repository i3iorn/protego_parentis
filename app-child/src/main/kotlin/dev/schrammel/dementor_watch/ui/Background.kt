package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Runs slow work off the main thread and delivers the result back on it.
 *
 * Reading preferences, walking the installed-app list and replaying usage events
 * are all fast enough to get away with on a developer's phone and slow enough to
 * freeze someone else's. Doing them during `onResume` was an ANR waiting for a
 * device with a few hundred apps on it.
 *
 * A plain executor rather than coroutines: this module has no coroutine
 * dependency, and what is needed here is one background thread and a way home.
 */
object Background {

    /**
     * Single-threaded on purpose. The work queued here reads and writes the same
     * preference files, and running two of those at once would race.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dementor-background").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Computes [work] in the background and hands it to [onResult] on the main
     * thread — but only if [activity] is still alive. Delivering into a finished
     * activity is a crash at worst and a leak at best.
     */
    fun <T> load(activity: Activity, work: () -> T, onResult: (T) -> Unit) {
        worker.execute {
            val result = runCatching(work)
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess(onResult)
            }
        }
    }
}
