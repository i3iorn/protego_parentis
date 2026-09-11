package dev.schrammel.protego_parentis.diagnostics

import android.app.Application

/**
 * Gets the app back to a known-good screen after [CrashRecovery] has caught an
 * uncaught exception, without `diagnostics` needing to import `ui` just to say
 * which screen that is.
 */
fun interface RestartLauncher {
    fun restart(app: Application)
}
