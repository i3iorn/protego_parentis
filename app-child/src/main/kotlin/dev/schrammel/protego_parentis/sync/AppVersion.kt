package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.content.pm.PackageManager

/**
 * This build's version name, as the family sees it.
 *
 * Read from `PackageManager` rather than `BuildConfig`, which this module does
 * not generate — enabling the `buildConfig` feature to reach one string would be
 * a build change for no gain.
 *
 * Empty when it cannot be read. Callers treat empty as "unknown" and say
 * nothing, because an unknown version is not a mismatch.
 */
internal fun appVersionName(context: Context): String =
    runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            .orEmpty()
    }.getOrDefault("")

/**
 * This build's `versionCode`, the same way [appVersionName] reads
 * `versionName` — from `PackageManager` rather than `BuildConfig`, for the
 * same reason: enabling the `buildConfig` feature to reach one number this
 * module can already read another way would be a build change for no gain.
 *
 * `PackageInfo.versionCode` is deprecated in favour of `longVersionCode`,
 * which folds in a high-word app-bundle split id this project's single APK
 * never sets — the low 32 bits [Long.toInt] keeps are exactly
 * `versionCode` as declared in `build.gradle.kts`.
 *
 * 0 when it cannot be read, which compares as older than every real release
 * — see [dev.schrammel.protego_parentis.update.UpdateManifest.isNewerThan],
 * the one caller that needs this, and its own doc comment.
 */
internal fun appVersionCode(context: Context): Int =
    runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .longVersionCode
            .toInt()
    }.getOrDefault(0)

/**
 * Whether two devices are on versions worth telling the family about.
 *
 * Unknown on either side is not a mismatch. A device that has not been heard
 * from since this feature shipped reports nothing, and warning about it would
 * make every family see a problem during the rollout that introduced the
 * warning.
 */
internal fun versionsDiffer(mine: String, theirs: String?): Boolean =
    mine.isNotEmpty() && !theirs.isNullOrEmpty() && mine != theirs
