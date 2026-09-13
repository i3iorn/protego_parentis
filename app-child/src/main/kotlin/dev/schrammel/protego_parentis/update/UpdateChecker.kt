package dev.schrammel.protego_parentis.update

import android.content.Context
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.sync.appVersionCode
import java.io.File

/** What one check for an update actually did. */
enum class UpdateOutcome {
    /** Nothing newer than what is already installed. */
    UP_TO_DATE,

    /** Could not reach or parse the release feed. */
    CHECK_FAILED,

    /** A newer release exists, but its APK failed to download or verify. */
    DOWNLOAD_FAILED,

    /** Downloaded, verified, and applied without anyone's confirmation. */
    INSTALLED_SILENTLY,

    /** Downloaded and verified, waiting on a tap to actually install. */
    DOWNLOADED_PENDING_CONFIRMATION,
}

/**
 * The one place "is there an update, and what do we do about it" is decided —
 * shared by [UpdateCheckWorker]'s daily run and a person tapping "check for
 * updates" on the diagnostics screen, so the two can never quietly diverge.
 */
object UpdateChecker {

    fun checkNow(context: Context): UpdateOutcome {
        val client = UpdateClient()

        val manifest = client.fetchManifest()
        if (manifest == null) {
            Diagnostics.record(context, DiagnosticEvent.UPDATE_CHECK_FAILED)
            return UpdateOutcome.CHECK_FAILED
        }
        if (!manifest.isNewerThan(appVersionCode(context))) return UpdateOutcome.UP_TO_DATE

        val apkFile = File(context.cacheDir, "update-${manifest.versionCode}.apk")
        if (!client.downloadApk(manifest, apkFile)) {
            Diagnostics.record(context, DiagnosticEvent.UPDATE_DOWNLOAD_FAILED)
            return UpdateOutcome.DOWNLOAD_FAILED
        }

        return if (EnforcementCoordinator(context).isDeviceOwner()) {
            ApkInstaller.installSilently(context, apkFile, manifest.versionName)
            UpdateOutcome.INSTALLED_SILENTLY
        } else {
            PendingUpdateStore(context).pending = PendingUpdate(apkFile.absolutePath, manifest.versionName)
            RequestNotifier(context).notifyUpdateAvailable(manifest.versionName)
            UpdateOutcome.DOWNLOADED_PENDING_CONFIRMATION
        }
    }
}
