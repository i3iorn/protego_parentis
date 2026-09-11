package dev.schrammel.protego_parentis.diagnostics

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.core.content.edit
import dev.schrammel.protego_parentis.AppRegistry
import kotlin.system.exitProcess

/**
 * Catches whatever the rest of the app did not, so a bug reads as "the app
 * restarted" rather than the platform's own "App has stopped" dialog.
 *
 * This matters more here than in most apps: a phone that measures and limits
 * someone else's screen time going dark because of an unhandled exception is a
 * rule silently not being enforced, on the one device where the person it is
 * enforcing against is the last one who should have to notice and report it.
 * [dev.schrammel.protego_parentis.enforcement.EnforcementService] already
 * recovers from a failure inside its own tick without going through this at
 * all — see its `tick` — this exists for everything that isn't already
 * wrapped that narrowly: the UI thread, and anything else that throws outside
 * a guarded loop.
 *
 * Recording, restarting into a clean task, and terminating the old process —
 * never trying to keep the throwing thread running. A thread that just threw
 * has already left something in a state nobody planned for, and pretending
 * otherwise is how a masked bug becomes a worse one silently.
 */
object CrashRecovery {

    private const val TAG = "DementorCrash"
    private const val PREFS_FILE = "crash_recovery"
    private const val KEY_LAST_CRASH_AT = "last_crash_at_millis"

    /**
     * Two crashes closer together than this are a loop, not a bug worth
     * restarting into — a phone stuck relaunching every few hundred
     * milliseconds is worse than the platform's own dialog, which at least
     * stops and tells somebody. When this fires, the exception is handed to
     * whatever handler was installed before this one (the platform default,
     * ordinarily), so the phone shows the crash rather than hiding a loop.
     */
    private const val LOOP_WINDOW_MILLIS = 10_000L

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Every step here runs during the crash itself, on a JVM that has
            // already shown it can throw from somewhere unexpected — each is
            // wrapped so a failure recording the crash cannot prevent
            // recovering from it.
            runCatching {
                Log.e(TAG, "Uncaught exception on '${thread.name}'; recovering", throwable)
            }
            runCatching { Diagnostics.record(app, DiagnosticEvent.APP_CRASHED) }

            if (runCatching { isLooping(app) }.getOrDefault(false)) {
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }

            runCatching { AppRegistry.restartLauncher.restart(app) }
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }

    private fun isLooping(app: Application): Boolean {
        val prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CRASH_AT, 0L)
        prefs.edit { putLong(KEY_LAST_CRASH_AT, now) }
        return last > 0L && now - last < LOOP_WINDOW_MILLIS
    }
}
