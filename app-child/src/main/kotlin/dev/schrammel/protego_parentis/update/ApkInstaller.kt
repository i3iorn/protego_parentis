package dev.schrammel.protego_parentis.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Installs a downloaded, already-verified update APK.
 *
 * Two entry points, and the caller — [UpdateCheckWorker], deciding from
 * [dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator.isDeviceOwner],
 * the same seam every other device-owner-gated decision in this app already
 * goes through — picks between them rather than either being tried first:
 *
 * - [installSilently], on the managed phone: a `PackageInstaller` session,
 *   exactly the platform privilege that already lets `DevicePolicyManager`
 *   suspend and restore apps with nobody's confirmation — extended by Android
 *   itself to installing them too, since Android 6, well before this app's
 *   `minSdk`.
 * - [installWithConfirmation], on a parent's own, unmanaged phone: the
 *   ordinary sideloaded-APK flow, since no such privilege exists there and
 *   none should — a phone nobody manages does not get to install things
 *   unattended. Only ever called from a foreground, user-initiated context
 *   (a tap on the update notification or the diagnostics screen) — starting
 *   an install-confirmation activity from a background worker is exactly the
 *   kind of unattended background activity launch modern Android refuses.
 *
 * Neither path re-verifies the APK; [UpdateClient.downloadApk] already refused
 * to hand back anything that failed its checksum, and Android's own installer
 * still refuses a file signed with a different key than the one already on
 * the phone regardless of which path asks it to.
 */
object ApkInstaller {

    fun installSilently(context: Context, apkFile: File, versionName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // Device owner already installs unattended without this — see the
            // class doc comment — but stating it explicitly where the API
            // allows leaves nothing here for the platform default to decide.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite(SESSION_NAME, 0, apkFile.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }

            val callback = Intent(context, UpdateInstallReceiver::class.java)
                .putExtra(UpdateInstallReceiver.EXTRA_VERSION_NAME, versionName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    /**
     * Hands the file to the system installer and lets whoever is holding the
     * phone confirm it, the same as tapping a downloaded APK from anywhere
     * else — this phone has no privilege to skip that, and manifest-declares
     * [android.Manifest.permission.REQUEST_INSTALL_PACKAGES] only to be
     * allowed to ask.
     */
    fun installWithConfirmation(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private const val SESSION_NAME = "update"
}
