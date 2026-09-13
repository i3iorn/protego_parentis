package dev.schrammel.protego_parentis.update

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Process
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import kotlin.system.exitProcess

/**
 * Hears back from the silent `PackageInstaller` session [ApkInstaller] commits.
 *
 * `commit` only means the session was accepted for processing — this is where
 * "it actually worked" or "it did not" arrives, some time later and off the
 * caller's stack, which is why it is a receiver rather than a return value.
 *
 * A successful install here is the one case that still needs a restart nobody
 * asked for. `PackageInstaller` replaces the APK on disk; it does not touch
 * the process already running from the old one, whose loaded classes,
 * resources, and every already-open screen keep right on being the old
 * version — which is exactly the "still shows old information and versions"
 * a silent update produced before this existed. Restarted the same way
 * [dev.schrammel.protego_parentis.diagnostics.CrashRecovery] already does for
 * an uncaught exception: [AppRegistry.restartLauncher] to a clean task, then
 * kill this process outright, never trying to keep it running into a state
 * half of it no longer matches.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> {
                Diagnostics.record(context, DiagnosticEvent.UPDATE_INSTALLED)
                Audit.record(
                    context = context,
                    actor = AuditActor.SYSTEM,
                    action = AuditAction.APP_UPDATED,
                    after = intent.getStringExtra(EXTRA_VERSION_NAME),
                )
                restart(context.applicationContext as Application)
            }

            // Should not happen on a device-owner session — see
            // ApkInstaller.installSilently — but if the platform asks anyway,
            // there is nobody here to answer it. The update is simply not
            // applied; the next scheduled check tries again from scratch.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit

            else -> Diagnostics.record(context, DiagnosticEvent.UPDATE_INSTALL_FAILED)
        }
    }

    private fun restart(app: Application) {
        AppRegistry.restartLauncher.restart(app)
        Process.killProcess(Process.myPid())
        exitProcess(1)
    }

    companion object {
        const val EXTRA_VERSION_NAME = "versionName"
    }
}
