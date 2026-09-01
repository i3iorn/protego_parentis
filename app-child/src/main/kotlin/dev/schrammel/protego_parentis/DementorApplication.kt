package dev.schrammel.protego_parentis

import android.app.Application
import dev.schrammel.protego_parentis.diagnostics.CrashRecovery

/**
 * Installs crash recovery before anything else in the app runs.
 *
 * The only thing this class does. Everything else — role, sync, enforcement —
 * is initialised lazily by whichever screen or service needs it first, the
 * same as before this class existed; this exists purely to have an `onCreate`
 * that runs earlier than any of that, so recovery is in place for the whole
 * process rather than for whatever happened to start it.
 */
class DementorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this)
    }
}
