package dev.schrammel.protego_parentis.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * Whether a downloaded APK is an update to this app and nothing else.
 *
 * The checksum [UpdateClient] verifies comes from the same release as the APK,
 * so it proves the download is intact, not that it is ours: whoever can publish
 * a release can publish both. The platform's same-signer rule only covers an
 * update to the *same* package. A device owner's install session with no
 * package name pinned installs a different package as a new app, silently, on
 * every managed phone. So before anything reaches an installer, the archive
 * has to name this package and be signed by a certificate this app is signed
 * with (current or earlier in its rotation lineage, which is what
 * [PackageManager.hasSigningCertificate] checks).
 */
object ApkIdentity {

    fun isOurs(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        ) ?: return false
        if (info.packageName != context.packageName) return false
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        signers.isNotEmpty() && signers.all { signer ->
            pm.hasSigningCertificate(context.packageName, signer.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)
        }
    }.getOrDefault(false)
}
