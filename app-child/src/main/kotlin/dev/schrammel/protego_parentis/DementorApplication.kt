package dev.schrammel.protego_parentis

import android.app.Application
import dev.schrammel.protego_parentis.diagnostics.CrashRecovery

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
    }
}
