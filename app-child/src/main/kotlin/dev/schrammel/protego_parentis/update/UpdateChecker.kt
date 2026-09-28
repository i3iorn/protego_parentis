package dev.schrammel.protego_parentis.update

import android.content.Context
import dev.schrammel.protego_parentis.diagnostics.DiagnosticEvent
import dev.schrammel.protego_parentis.diagnostics.Diagnostics
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.sync.appVersionCode
import java.io.File

/**
 * Everything [UpdateChecker.checkNow] needs from the network.
 *
 * Declared here, not just implemented by [UpdateClient] directly, so the
 * decision that follows — install silently, or wait for a tap — can be
 * exercised against a fake feed instead of a real GitHub download every time.
 */
interface UpdateSource {
    fun fetchManifest(): UpdateManifest?
    fun downloadApk(manifest: UpdateManifest, destination: File): Boolean
}

/** What one check for an update actually did. */
enum class UpdateOutcome {
    /** Nothing newer than what is already installed. */
    UP_TO_DATE,

    /** Could not reach or parse the release feed. */
    CHECK_FAILED,

    /** A newer release exists, but its APK failed to download or verify. */
    DOWNLOAD_FAILED,

    /**
     * A newer release's APK downloaded intact but isn't this app, or isn't
     * signed by our key. Downloading it again gets the same file.
     */
    REJECTED,

    /** Downloaded, verified, and applied without anyone's confirmation. */
    INSTALLED_SILENTLY,

    /** Downloaded and verified, waiting on a tap to actually install. */
    DOWNLOADED_PENDING_CONFIRMATION,
}

/**
 * The one place "is there an update, and what do we do about it" is decided —
 * shared by [UpdateCheckWorker]'s daily run and a person tapping "check for
 * updates" on the diagnostics screen, so the two can never quietly diverge.
 *
 * [checkNow]'s collaborators are all parameters with production defaults, not
 * hardcoded `new`s: [source] because a real check should never hit the network
 * in a test, and [isDeviceOwner]/[installSilently] because the branch they
 * gate — installing an update completely unattended — is the one security
 * decision in this file and could previously only ever be exercised on a real,
 * provisioned device.
 */
object UpdateChecker {

    fun checkNow(
        context: Context,
        source: UpdateSource = UpdateClient(),
        isDeviceOwner: (Context) -> Boolean = { EnforcementCoordinator(it).isDeviceOwner() },
        installSilently: (Context, File, String) -> Unit = ApkInstaller::installSilently,
        isOurs: (Context, File) -> Boolean = ApkIdentity::isOurs,
        pendingUpdateStore: PendingUpdateStore = PendingUpdateStore(context),
        notifier: RequestNotifier = RequestNotifier(context),
    ): UpdateOutcome {
        val manifest = source.fetchManifest()
        if (manifest == null) {
            Diagnostics.record(context, DiagnosticEvent.UPDATE_CHECK_FAILED)
            return UpdateOutcome.CHECK_FAILED
        }
        if (!manifest.isNewerThan(appVersionCode(context))) return UpdateOutcome.UP_TO_DATE

        val apkFile = File(context.cacheDir, "update-${manifest.versionCode}.apk")
        if (!source.downloadApk(manifest, apkFile)) {
            Diagnostics.record(context, DiagnosticEvent.UPDATE_DOWNLOAD_FAILED)
            return UpdateOutcome.DOWNLOAD_FAILED
        }
        if (!isOurs(context, apkFile)) {
            apkFile.delete()
            Diagnostics.record(context, DiagnosticEvent.UPDATE_REJECTED)
            return UpdateOutcome.REJECTED
        }

        return if (isDeviceOwner(context)) {
            installSilently(context, apkFile, manifest.versionName)
            UpdateOutcome.INSTALLED_SILENTLY
        } else {
            pendingUpdateStore.pending = PendingUpdate(apkFile.absolutePath, manifest.versionName)
            notifier.notifyUpdateAvailable(manifest.versionName)
            UpdateOutcome.DOWNLOADED_PENDING_CONFIRMATION
        }
    }
}
