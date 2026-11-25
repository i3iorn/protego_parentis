package dev.schrammel.protego_parentis

import android.app.Application
import dev.schrammel.protego_parentis.diagnostics.CrashRecovery
import dev.schrammel.protego_parentis.sync.RealtimeConnectivityWatcher
import dev.schrammel.protego_parentis.update.UpdateCheckWorker

/**
 * Installs crash recovery and the composition root before anything else in
 * the app runs.
 *
 * Beyond those two, everything else — role, sync, enforcement — is
 * initialised lazily by whichever screen or service needs it first, the same
 * as before this class existed; this exists purely to have an `onCreate` that
 * runs earlier than any of that.
 */
class DementorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // The crash reporter is a supplier too: it resolves AppRegistry's
        // publisher lazily, so a crash during installation still recovers —
        // it just cannot publish itself yet, and the pending flush below
        // becomes its second chance on the next start.
        CrashRecovery.install(
            this,
            { AppRegistry.restartLauncher },
            { AppRegistry.crashReportPublisher },
        )
        AppRegistry.install(this)
        // A crash that could not publish itself (offline, or the process died
        // first) left a pending report; this start is the second chance. The
        // check is synchronous and returns immediately when nothing is pending,
        // so an ordinary start does no background work here at all.
        AppRegistry.crashReportPublisher?.flushPending()
        // Process-lifetime, not screen-lifetime: reopens the realtime stream
        // on the next network flap even if no screen is ever opened.
        RealtimeConnectivityWatcher.install(this)
        // Both roles run the same APK and both should stay current, so this
        // is scheduled unconditionally rather than gated by role the way
        // ParentPollWorker is.
        UpdateCheckWorker.schedule(this)
    }
}
