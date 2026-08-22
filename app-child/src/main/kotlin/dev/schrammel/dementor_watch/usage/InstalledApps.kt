package dev.schrammel.dementor_watch.usage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dev.schrammel.dementor_watch.model.PackageName

data class AppInfo(
    val pkg: PackageName,
    val label: String,
    val icon: Drawable?,
)

/**
 * The apps a person can actually open.
 *
 * Launchable apps only. The full package list includes hundreds of system
 * components that no one recognises, which would make both the parent's app list
 * and the teen's dashboard useless.
 */
class InstalledApps(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun launchable(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager
            .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            .map { resolved ->
                AppInfo(
                    pkg = PackageName(resolved.activityInfo.packageName),
                    label = resolved.loadLabel(packageManager).toString(),
                    icon = runCatching { resolved.loadIcon(packageManager) }.getOrNull(),
                )
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun labelFor(pkg: PackageName): String = runCatching {
        packageManager
            .getApplicationInfo(pkg.value, PackageManager.ApplicationInfoFlags.of(0L))
            .loadLabel(packageManager)
            .toString()
    }.getOrDefault(pkg.value)
}
