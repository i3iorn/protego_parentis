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
        CrashRecovery.install(this) { AppRegistry.restartLauncher }
        AppRegistry.install(this)
        // Process-lifetime, not screen-lifetime: reopens the realtime stream
        // on the next network flap even if no screen is ever opened.
        RealtimeConnectivityWatcher.install(this)
        // Both roles run the same APK and both should stay current, so this
        // is scheduled unconditionally rather than gated by role the way
        // ParentPollWorker is.
        UpdateCheckWorker.schedule(this)
    }
}
